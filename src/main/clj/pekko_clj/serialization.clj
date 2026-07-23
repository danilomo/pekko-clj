(ns pekko-clj.serialization
  "Transit-based serialization of Clojure data for Pekko.

   Pekko's default serializers cannot encode Clojure values, so remoting, cluster
   sharding and persistence fall back to Java serialization (slow, insecure, and
   disabled by default in production). This namespace provides:

   - `write-bytes` / `read-bytes` — Transit encode/decode of arbitrary Clojure data.
     `ActorRef`s embedded anywhere in the value survive the round trip: they are
     written as a full remote path and resolved back through the system's provider.
   - `transit-config` — a `com.typesafe.config.Config` that registers
     `pekko_clj.actor.CljTransitSerializer` (the Pekko `SerializerWithStringManifest`
     that calls into this namespace) and binds the Clojure data classes to it.
   - `serialization-config` — the general `serializers` / `serialization-bindings` /
     `serialization-identifiers` builder `transit-config` is written on top of.

   Example:
     ;; Cluster system that speaks Transit and has Java serialization turned off
     (cluster/create-system \"my-app\" {:port 7355 :transit-serialization true})

     ;; Or merge the config yourself
     (ActorSystem/create \"my-app\"
       (.withFallback (serialization/transit-config {:format :msgpack})
                      (ConfigFactory/load)))

     ;; Direct use (e.g. writing Clojure data to an external store)
     (-> (serialization/write-bytes {:a [1 2 #{:x}]}) (serialization/read-bytes))
     ;; => {:a [1 2 #{:x}]}

   Notes:
   - Transit is self-describing, so every payload carries one constant manifest.
   - Records need a per-type handler and are therefore **opt-in**: list them under
     `:records` (on `transit-config`, or per call on `write-bytes`/`read-bytes`)
     and they round trip as themselves. Without that, Transit writes a record as a
     plain map and it comes back as a plain map — a silent type erasure, which is
     why pekko-clj's own wire types use plain data instead (the sharding envelope,
     `sharding/entity-message`, is a map with namespaced keys).
   - The default Transit format is `:json`; `:msgpack` is more compact,
     `:json-verbose` is human-readable."
  (:require [cognitect.transit :as transit]
            [clojure.string :as str])
  (:import [clojure.lang ExceptionInfo]
           [com.cognitect.transit DefaultReadHandler]
           [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.lang.ref WeakReference]
           [java.util Collections WeakHashMap]
           [java.util.function Function]
           [org.apache.pekko.actor ActorRef ExtendedActorSystem]
           [com.typesafe.config Config ConfigFactory]))

(def serializer-class
  "Fully-qualified name of the Pekko serializer bridging to this namespace."
  "pekko_clj.actor.CljTransitSerializer")

(def default-identifier
  "Serializer identifier used unless `:identifier` says otherwise.
   Must match `CljTransitSerializer/DEFAULT_IDENTIFIER` and be unique per system."
  9001)

(def default-bindings
  "Classes bound to the Transit serializer by default — the Clojure data types.
   `IPersistentCollection` covers maps, vectors, lists, sets and seqs."
  ["clojure.lang.IPersistentCollection"
   "clojure.lang.Keyword"
   "clojure.lang.Symbol"
   "clojure.lang.Ratio"
   "clojure.lang.BigInt"])

(def ^:private actor-ref-tag "pekko/ref")

(def records-path
  "Config path listing the record classes bound to the Transit serializer.
   Written by `transit-config`'s `:records` option, read back per ActorSystem."
  "pekko-clj.serialization.transit.records")

(def ^:private formats #{:json :json-verbose :msgpack})

(defn- ->format
  "Coerce a Transit format (keyword or string) and validate it."
  [format]
  (let [f (keyword format)]
    (when-not (formats f)
      (throw (IllegalArgumentException.
              (str "Transit format must be one of " formats ", got " format))))
    f))

;; ---------------------------------------------------------------------------
;; ActorRef handlers
;; ---------------------------------------------------------------------------

(defn- ref->str
  [^ExtendedActorSystem system ^ActorRef ref]
  (.toSerializationFormatWithAddress (.path ref)
                                     (.getDefaultAddress (.provider system))))

(defn- str->ref
  [^ExtendedActorSystem system ^String path]
  (.resolveActorRef (.provider system) path))

(defn- ref-handler-entries
  "Handler entries round-tripping ActorRefs through `system`.

   The system is reached through a WeakReference so a cached entry never strongly
   references the ActorSystem it is keyed by (see `handler-cache`). The handlers
   are only ever invoked while someone is serializing *with* that system, so it is
   always still reachable at call time."
  [system]
  (let [weak (WeakReference. system)
        current #(.get weak)]
    {:write {ActorRef (transit/write-handler
                       (constantly actor-ref-tag)
                       (fn [ref] (ref->str (current) ref)))}
     :read  {actor-ref-tag (transit/read-handler
                            (fn [path] (str->ref (current) path)))}}))

;; ---------------------------------------------------------------------------
;; Record handlers
;; ---------------------------------------------------------------------------

(defn- ->record-class
  "Coerce a record class, class name or symbol to a Class, validating it is a record."
  ^Class [r]
  (let [^Class klass
        (if (class? r)
          r
          (let [n (if (instance? clojure.lang.Named r) (name r) (str r))]
            (try
              (Class/forName n true (clojure.lang.RT/baseLoader))
              (catch ClassNotFoundException _
                (throw (IllegalArgumentException.
                        (str "Record class not found: " n)))))))]
    (when-not (contains? (supers klass) clojure.lang.IRecord)
      (throw (IllegalArgumentException.
              (str (.getName klass) " is not a defrecord type; :records only takes records"))))
    klass))

(defn- record-read-handler
  "Read handler rebuilding a record from the map Transit decoded it into.

   Goes through the record's generated static `create(IPersistentMap)` factory
   rather than transit-clj's own `record-read-handler`, which resolves the
   `ns/map->Rec` var and so needs the defining namespace to already be loaded —
   not guaranteed when the class name comes from an ActorSystem's config."
  [^Class klass]
  (let [ctor (.getMethod klass "create" (into-array Class [clojure.lang.IPersistentMap]))]
    (transit/read-handler (fn [m] (.invoke ctor nil (object-array [m]))))))

(defn- record-handler-entries
  "Write/read handler entries for `records`, tagged with each record's class name."
  [records]
  (reduce (fn [acc r]
            (let [klass (->record-class r)]
              (-> acc
                  (assoc-in [:write klass] (transit/record-write-handler klass))
                  (assoc-in [:read (.getName klass)] (record-read-handler klass)))))
          {:write {} :read {}}
          records))

(def ^:private unknown-tag-handler
  "Read handler for a tag nothing is registered for.

   Transit's default returns a `TaggedValue`, which then flows on into user code as
   if it were a message; the overwhelmingly likely cause is a record written by a
   node that registered it and read by one that did not, so fail loudly instead."
  (reify DefaultReadHandler
    (fromRep [_ tag rep]
      (throw (ex-info (str "No Transit read handler for tag \"" tag "\". If it names a "
                           "record type, register it via the :records option of "
                           "transit-config (or of read-bytes).")
                      {::unknown-tag true :tag tag :rep rep})))))

(defn- rethrow-unwrapped
  "Transit wraps whatever a read handler throws in a bare `RuntimeException`;
   surface our own unknown-tag error unchanged so callers can inspect its data."
  [^RuntimeException e]
  (let [cause (.getCause e)]
    (if (and (instance? ExceptionInfo cause) (::unknown-tag (ex-data cause)))
      (throw cause)
      (throw e))))

;; ---------------------------------------------------------------------------
;; Handler cache
;; ---------------------------------------------------------------------------

(defn- configured-records
  "Record class names registered on `system` by `transit-config`'s `:records`."
  [^ExtendedActorSystem system]
  (let [config (.config (.settings system))]
    (when (.hasPath config records-path)
      (.getStringList config records-path))))

;; Handler maps are built once per (ActorSystem, extra records) pair: the outer
;; cache has weak keys so a terminated system can be collected, the inner atom maps
;; the explicitly-passed record set to its handler maps. Cached values must never
;; strongly reference the outer key — see `ref-handler-entries`.
(defonce ^:private handler-cache (Collections/synchronizedMap (WeakHashMap.)))

(def ^:private no-system
  "Outer cache key standing in for a nil system (WeakHashMap keys must be non-nil)."
  ::no-system)

(defn- build-handlers
  [system records]
  (let [refs (when system (ref-handler-entries system))
        recs (record-handler-entries
              (concat (when system (configured-records system)) records))]
    {:write (transit/write-handler-map (merge (:write refs) (:write recs)))
     :read  (transit/read-handler-map (merge (:read refs) (:read recs)))}))

(defn- handlers-for
  "Transit handler maps for `system` (nil ⇒ no ActorRef support) plus `records`."
  [system records]
  (let [entry (.computeIfAbsent ^java.util.Map handler-cache
                                (or system no-system)
                                (reify Function (apply [_ _] (atom {}))))
        k (set records)]
    (or (get @entry k)
        (let [hs (build-handlers system records)]
          (swap! entry assoc k hs)
          hs))))

;; ---------------------------------------------------------------------------
;; Encode / decode
;; ---------------------------------------------------------------------------

(defn write-bytes
  "Serialize `obj` to a Transit-encoded byte array.

   Arguments:
   - obj: any Transit-writable value (Clojure data, ActorRefs, Java scalars)
   - system: an ExtendedActorSystem enabling ActorRef round-tripping (optional)
   - format: :json (default), :json-verbose or :msgpack
   - records: record classes (or class names) to write as themselves rather than
     as plain maps; added to whatever `system`'s config already registers

   Returns: byte[]

   Example:
     (write-bytes {:cmd :inc :by 2})
     (write-bytes (->Point 1 2) nil :json [Point])"
  (^bytes [obj] (write-bytes obj nil :json nil))
  (^bytes [obj system] (write-bytes obj system :json nil))
  (^bytes [obj system format] (write-bytes obj system format nil))
  (^bytes [obj system format records]
   (let [out (ByteArrayOutputStream. 256)
         writer (transit/writer out (->format format)
                                {:handlers (:write (handlers-for system records))})]
     (transit/write writer obj)
     (.toByteArray out))))

(defn read-bytes
  "Deserialize a Transit-encoded byte array produced by `write-bytes`.

   Arguments:
   - bytes: byte[] holding the Transit payload
   - system: the ExtendedActorSystem used to resolve ActorRefs (optional)
   - format: must match the one used to write (:json by default)
   - records: record classes (or class names) to rebuild from their tag; must
     cover every record the writing side registered, or the read throws

   Returns: the decoded value

   Example:
     (read-bytes (write-bytes [1 :two \"three\"]))  ;; => [1 :two \"three\"]"
  ([bytes] (read-bytes bytes nil :json nil))
  ([bytes system] (read-bytes bytes system :json nil))
  ([bytes system format] (read-bytes bytes system format nil))
  ([^bytes bytes system format records]
   (try
     (transit/read (transit/reader (ByteArrayInputStream. bytes) (->format format)
                                   {:handlers (:read (handlers-for system records))
                                    :default-handler unknown-tag-handler}))
     (catch RuntimeException e (rethrow-unwrapped e)))))

;; ---------------------------------------------------------------------------
;; Configuration helpers
;; ---------------------------------------------------------------------------

(defn- hocon-string
  "Quote a value as a HOCON string literal. Keywords/symbols render as their name,
   so aliases and class names may be given either way."
  [s]
  (let [text (if (instance? clojure.lang.Named s) (name s) (str s))]
    (str \" (str/replace text "\"" "\\\"") \")))

(defn serialization-config
  "Build a Config registering Pekko serializers, bindings and identifiers.

   Options map:
   - :serializers  - map of alias → serializer class name
   - :bindings     - map of class name → alias (bind interfaces or classes;
                     Pekko picks the most specific match)
   - :identifiers  - map of serializer class name → unique int identifier
   - :allow-java-serialization - when non-nil, sets
                     `pekko.actor.allow-java-serialization` (and silences the
                     warning when turning it on)

   Returns: com.typesafe.config.Config — merge it with `.withFallback` or pass it
   to `cluster/create-system` as `:extra-config`.

   Example:
     (serialization-config {:serializers {\"transit\" serializer-class}
                            :bindings {\"clojure.lang.Keyword\" \"transit\"}
                            :identifiers {serializer-class 9001}
                            :allow-java-serialization false})"
  ^Config [{:keys [serializers bindings identifiers allow-java-serialization]}]
  (let [base "pekko.actor."
        lines (concat
               (for [[alias klass] serializers]
                 (str base "serializers." (hocon-string alias) " = " (hocon-string klass)))
               (for [[klass alias] bindings]
                 (str base "serialization-bindings." (hocon-string klass) " = " (hocon-string alias)))
               (for [[klass id] identifiers]
                 (str base "serialization-identifiers." (hocon-string klass) " = " (long id)))
               (when (some? allow-java-serialization)
                 [(str base "allow-java-serialization = " (if allow-java-serialization "on" "off"))
                  (str base "warn-about-java-serializer-usage = "
                       (if allow-java-serialization "off" "on"))]))]
    (ConfigFactory/parseString (str/join "\n" lines))))

(defn transit-config
  "Build a Config wiring the Transit serializer for Clojure data.

   Options map:
   - :alias      - serializer alias in the config (default \"transit\")
   - :identifier - unique serializer id (default 9001); change it only if it
                   collides with another serializer in the same system
   - :format     - :json (default), :json-verbose or :msgpack
   - :bindings   - collection of class names to bind (default `default-bindings`)
   - :extra-bindings - additional class names, bound alongside the defaults
   - :records    - record classes (or class names) that should round trip as
                   themselves instead of decaying to plain maps. Every node
                   exchanging them needs the same list. Supplying any also binds
                   `clojure.lang.IRecord`, so records reach this serializer even
                   with a custom `:bindings` (the record classes themselves are
                   deliberately *not* bound: without AOT they live in Clojure's
                   DynamicClassLoader and Pekko cannot resolve them by name).
   - :allow-java-serialization - default false: once Clojure data has a real
                   serializer, Java serialization is no longer needed. Set true to
                   keep it on (e.g. while migrating a system message by message).

   Returns: com.typesafe.config.Config

   Example:
     (transit-config {:format :msgpack
                      :extra-bindings [\"my.app.SomeIface\"]
                      :records [my.app.Point]})"
  (^Config [] (transit-config {}))
  (^Config [{:keys [alias identifier format bindings extra-bindings records
                    allow-java-serialization]
             :or {alias "transit"
                  identifier default-identifier
                  format :json
                  allow-java-serialization false}}]
   (let [record-names (mapv #(.getName (->record-class %)) records)
         klasses (concat (or bindings default-bindings) extra-bindings
                         (when (seq record-names) ["clojure.lang.IRecord"]))]
     (.withFallback
      (ConfigFactory/parseString
       (str/join "\n"
                 (cond-> [(str "pekko-clj.serialization.transit.format = "
                               (hocon-string (name (->format format))))]
                   (seq record-names)
                   (conj (str records-path " = ["
                              (str/join ", " (map hocon-string record-names))
                              "]")))))
      (serialization-config
       {:serializers {alias serializer-class}
        :bindings (into {} (map (fn [k] [k alias])) klasses)
        :identifiers {serializer-class identifier}
        :allow-java-serialization allow-java-serialization})))))
