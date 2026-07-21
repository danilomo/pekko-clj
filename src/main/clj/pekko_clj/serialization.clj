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
   - Records are NOT handled out of the box (Transit needs a per-type handler);
     use plain maps in messages and persisted events.
   - The default Transit format is `:json`; `:msgpack` is more compact,
     `:json-verbose` is human-readable."
  (:require [cognitect.transit :as transit]
            [clojure.string :as str])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.lang.ref WeakReference]
           [java.util Collections WeakHashMap]
           [org.apache.pekko.actor ActorRef ExtendedActorSystem]
           [com.typesafe.config Config ConfigFactory]))

(set! *warn-on-reflection* true)

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

;; Handler maps are built once per ActorSystem. The cache has weak keys so a
;; terminated system can be collected — which only works if the cached value never
;; strongly references the key, so the handlers reach the system through a
;; WeakReference. They are only ever invoked while someone is serializing *with*
;; that system, so it is always still reachable at call time.
(defonce ^:private handler-cache (Collections/synchronizedMap (WeakHashMap.)))

(def ^:private plain-handlers
  {:write (transit/write-handler-map {})
   :read  (transit/read-handler-map {})})

(defn- handlers-for
  "Transit handler maps for `system` (nil ⇒ no ActorRef support)."
  [system]
  (if (nil? system)
    plain-handlers
    (or (.get ^java.util.Map handler-cache system)
        (let [weak (WeakReference. system)
              current #(.get weak)
              hs {:write (transit/write-handler-map
                          {ActorRef (transit/write-handler
                                     (constantly actor-ref-tag)
                                     (fn [ref] (ref->str (current) ref)))})
                  :read  (transit/read-handler-map
                          {actor-ref-tag (transit/read-handler
                                          (fn [path] (str->ref (current) path)))})}]
          (.put ^java.util.Map handler-cache system hs)
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

   Returns: byte[]

   Example:
     (write-bytes {:cmd :inc :by 2})"
  (^bytes [obj] (write-bytes obj nil :json))
  (^bytes [obj system] (write-bytes obj system :json))
  (^bytes [obj system format]
   (let [out (ByteArrayOutputStream. 256)
         writer (transit/writer out (->format format)
                                {:handlers (:write (handlers-for system))})]
     (transit/write writer obj)
     (.toByteArray out))))

(defn read-bytes
  "Deserialize a Transit-encoded byte array produced by `write-bytes`.

   Arguments:
   - bytes: byte[] holding the Transit payload
   - system: the ExtendedActorSystem used to resolve ActorRefs (optional)
   - format: must match the one used to write (:json by default)

   Returns: the decoded value

   Example:
     (read-bytes (write-bytes [1 :two \"three\"]))  ;; => [1 :two \"three\"]"
  ([bytes] (read-bytes bytes nil :json))
  ([bytes system] (read-bytes bytes system :json))
  ([^bytes bytes system format]
   (transit/read (transit/reader (ByteArrayInputStream. bytes) (->format format)
                                 {:handlers (:read (handlers-for system))}))))

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
   - :allow-java-serialization - default false: once Clojure data has a real
                   serializer, Java serialization is no longer needed. Set true to
                   keep it on (e.g. while migrating a system message by message).

   Returns: com.typesafe.config.Config

   Example:
     (transit-config {:format :msgpack
                      :extra-bindings [\"my.app.SomeIface\"]})"
  (^Config [] (transit-config {}))
  (^Config [{:keys [alias identifier format bindings extra-bindings
                    allow-java-serialization]
             :or {alias "transit"
                  identifier default-identifier
                  format :json
                  allow-java-serialization false}}]
   (let [klasses (concat (or bindings default-bindings) extra-bindings)]
     (.withFallback
      (ConfigFactory/parseString
       (str "pekko-clj.serialization.transit.format = "
            (hocon-string (name (->format format)))))
      (serialization-config
       {:serializers {alias serializer-class}
        :bindings (into {} (map (fn [k] [k alias])) klasses)
        :identifiers {serializer-class identifier}
        :allow-java-serialization allow-java-serialization})))))

(set! *warn-on-reflection* false)
