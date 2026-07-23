(ns pekko-clj.serialization-test
  "Tests for N4: the Transit-backed Clojure-data serializer and its config helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.serialization :as ser]
            [pekko-clj.test-support :as ts])
  (:import [clojure.lang ExceptionInfo]
           [com.typesafe.config Config ConfigFactory]
           [org.apache.pekko.actor ActorRef ActorSystem]
           [org.apache.pekko.serialization SerializationExtension Serializers]
           [pekko_clj.actor CljTransitSerializer]))

;; ---------------------------------------------------------------------------
;; Encode / decode (no ActorSystem needed)
;; ---------------------------------------------------------------------------

(core/defactor echo-actor
  "Stores the last value it was given and replies with it on demand."
  (init [_] {:last nil})
  (handle [:put v] (assoc state :last v))
  (handle [:get] (do (core/reply (:last state)) state)))

(def sample-data
  {:keyword :a-keyword
   "string" ["vector" 1 2.5 true nil]
   'symbol #{:set-elem 42}
   :nested {:list (list 1 2 3)
            :ratio 3/4
            :big (bigint 12345678901234567890N)
            :inst #inst "2026-07-21T00:00:00.000-00:00"}})

(deftest round-trip-clojure-data-test
  (let [bytes (ser/write-bytes sample-data)]
    (is (bytes? bytes))
    (is (= sample-data (ser/read-bytes bytes))))
  ;; Types, not just values: keywords/symbols/sets survive as themselves.
  (let [back (ser/read-bytes (ser/write-bytes sample-data))]
    (is (keyword? (:keyword back)))
    (is (set? (get back 'symbol)))
    (is (ratio? (get-in back [:nested :ratio])))))

(deftest round-trip-every-format-test
  (doseq [format [:json :json-verbose :msgpack]]
    (testing (str "format " format)
      (is (= sample-data
             (ser/read-bytes (ser/write-bytes sample-data nil format) nil format))))))

(deftest msgpack-differs-from-json-test
  ;; Sanity check that the format argument actually reaches Transit.
  (is (not= (seq (ser/write-bytes sample-data nil :json))
            (seq (ser/write-bytes sample-data nil :msgpack)))))

(deftest unknown-format-throws-test
  (is (thrown? IllegalArgumentException (ser/write-bytes {:a 1} nil :edn)))
  (is (thrown? IllegalArgumentException (ser/read-bytes (ser/write-bytes {:a 1}) nil :edn))))

(deftest actor-ref-round-trips-through-a-system-test
  (let [sys (core/actor-system "transit-ref")]
    (try
      (let [probe (core/spawn sys echo-actor nil)
            msg {:reply-to probe :cmd :ping}
            back (ser/read-bytes (ser/write-bytes msg sys :json) sys :json)]
        (is (instance? ActorRef (:reply-to back)))
        (is (= probe (:reply-to back)))
        (is (= :ping (:cmd back))))
      (finally (core/shutdown-system sys)))))

;; ---------------------------------------------------------------------------
;; N17: record support
;; ---------------------------------------------------------------------------

(defrecord Point [x y])
(defrecord Segment [from to label])

(def point-classes [Point Segment])

(deftest records-round-trip-when-registered-test
  (let [p (->Point 1 2)
        back (ser/read-bytes (ser/write-bytes p nil :json point-classes)
                             nil :json point-classes)]
    (is (instance? Point back) "comes back as the record type, not a map")
    (is (= p back))
    (is (= 1 (:x back))))
  (testing "every format"
    (doseq [format [:json :json-verbose :msgpack]]
      (let [p (->Point :a #{1 2})]
        (is (= p (ser/read-bytes (ser/write-bytes p nil format point-classes)
                                 nil format point-classes))
            (str "format " format))))))

(deftest unregistered-record-decays-to-a-map-test
  ;; The documented default: Transit writes a record as a plain map, so the type
  ;; is silently erased. This is what makes :records opt-in rather than automatic.
  (let [back (ser/read-bytes (ser/write-bytes (->Point 1 2)))]
    (is (map? back))
    (is (not (instance? Point back)))
    (is (= {:x 1 :y 2} back))))

(deftest nested-records-round-trip-test
  (let [seg (->Segment (->Point 0 0) (->Point 3 4) :diagonal)
        payload {:shapes [seg] :by-name {:first (->Point 9 9)} :set #{(->Point 1 1)}}
        back (ser/read-bytes (ser/write-bytes payload nil :json point-classes)
                             nil :json point-classes)]
    (is (= payload back))
    (is (instance? Segment (first (:shapes back))))
    (is (instance? Point (:from (first (:shapes back)))) "a record inside a record")
    (is (instance? Point (get-in back [:by-name :first])))
    (is (instance? Point (first (:set back))))))

(deftest record-with-extra-keys-round-trips-test
  ;; assoc'ing a non-basis key keeps it a record; the ext map must survive too.
  (let [p (assoc (->Point 1 2) :label "origin")
        back (ser/read-bytes (ser/write-bytes p nil :json point-classes)
                             nil :json point-classes)]
    (is (instance? Point back))
    (is (= p back))
    (is (= "origin" (:label back)))))

(deftest reading-an-unknown-record-tag-throws-test
  ;; Written by a peer that registered Point, read by one that did not: transit's
  ;; own default would hand back a TaggedValue, which then flows on as if it were
  ;; the message. We fail loudly and name the tag instead.
  (let [bytes (ser/write-bytes (->Point 1 2) nil :json point-classes)
        ex (is (thrown? ExceptionInfo (ser/read-bytes bytes)))]
    (is (= "pekko_clj.serialization_test.Point" (:tag (ex-data ex))))
    (is (re-find #":records" (ex-message ex)) "the message points at the fix")))

(deftest non-record-classes-are-rejected-test
  (is (thrown-with-msg? IllegalArgumentException #"not a defrecord"
        (ser/write-bytes {:a 1} nil :json [String])))
  (is (thrown-with-msg? IllegalArgumentException #"not a defrecord"
        (ser/transit-config {:records ["clojure.lang.Keyword"]})))
  (is (thrown-with-msg? IllegalArgumentException #"not found"
        (ser/transit-config {:records ["no.such.Record"]}))))

(deftest records-accept-class-names-as-well-as-classes-test
  (let [p (->Point 1 2)
        names ["pekko_clj.serialization_test.Point"]]
    (is (= p (ser/read-bytes (ser/write-bytes p nil :json names) nil :json names)))
    ;; and the two spellings are interchangeable across the wire
    (is (= p (ser/read-bytes (ser/write-bytes p nil :json [Point]) nil :json names)))))

(deftest transit-config-records-test
  (let [^Config cfg (ser/transit-config {:records point-classes})]
    (is (= ["pekko_clj.serialization_test.Point" "pekko_clj.serialization_test.Segment"]
           (vec (.getStringList cfg ser/records-path))))
    ;; IRecord is bound too, so records reach the serializer even with custom
    ;; :bindings. The record classes themselves are not bound: without AOT they
    ;; live in Clojure's DynamicClassLoader and Pekko cannot resolve them by name.
    (is (= "transit" (.getString cfg "pekko.actor.serialization-bindings.\"clojure.lang.IRecord\"")))
    (is (not (.hasPath cfg (str "pekko.actor.serialization-bindings.\""
                                "pekko_clj.serialization_test.Point\"")))))
  (testing "absent unless asked for"
    (let [^Config cfg (ser/transit-config)]
      (is (not (.hasPath cfg ser/records-path)))
      (is (not (.hasPath cfg "pekko.actor.serialization-bindings.\"clojure.lang.IRecord\""))))))

;; ---------------------------------------------------------------------------
;; Config helpers
;; ---------------------------------------------------------------------------

(deftest serialization-config-shape-test
  (let [^Config cfg (ser/serialization-config
                     {:serializers {"transit" ser/serializer-class}
                      :bindings {"clojure.lang.Keyword" "transit"}
                      :identifiers {ser/serializer-class 4242}
                      :allow-java-serialization false})]
    (is (instance? Config cfg))
    (is (= ser/serializer-class (.getString cfg "pekko.actor.serializers.transit")))
    (is (= "transit" (.getString cfg "pekko.actor.serialization-bindings.\"clojure.lang.Keyword\"")))
    (is (= 4242 (.getInt cfg (str "pekko.actor.serialization-identifiers.\"" ser/serializer-class "\""))))
    (is (false? (.getBoolean cfg "pekko.actor.allow-java-serialization")))
    (is (true? (.getBoolean cfg "pekko.actor.warn-about-java-serializer-usage")))))

(deftest transit-config-defaults-test
  (let [^Config cfg (ser/transit-config)]
    (is (= ser/serializer-class (.getString cfg "pekko.actor.serializers.transit")))
    (is (= "json" (.getString cfg "pekko-clj.serialization.transit.format")))
    (is (= ser/default-identifier
           (.getInt cfg (str "pekko.actor.serialization-identifiers.\"" ser/serializer-class "\""))))
    (is (false? (.getBoolean cfg "pekko.actor.allow-java-serialization")))
    (doseq [klass ser/default-bindings]
      (is (= "transit" (.getString cfg (str "pekko.actor.serialization-bindings.\"" klass "\"")))
          (str klass " is bound to the transit serializer")))))

(deftest transit-config-options-test
  (let [^Config cfg (ser/transit-config {:alias "clj-transit"
                                         :identifier 5150
                                         :format :msgpack
                                         :extra-bindings ["java.util.UUID"]
                                         :allow-java-serialization true})]
    (is (= ser/serializer-class (.getString cfg "pekko.actor.serializers.clj-transit")))
    (is (= "msgpack" (.getString cfg "pekko-clj.serialization.transit.format")))
    (is (= 5150 (.getInt cfg (str "pekko.actor.serialization-identifiers.\"" ser/serializer-class "\""))))
    (is (= "clj-transit" (.getString cfg "pekko.actor.serialization-bindings.\"java.util.UUID\"")))
    (is (= "clj-transit" (.getString cfg "pekko.actor.serialization-bindings.\"clojure.lang.Keyword\"")))
    (is (true? (.getBoolean cfg "pekko.actor.allow-java-serialization"))))
  ;; :bindings replaces the defaults outright
  (let [^Config cfg (ser/transit-config {:bindings ["clojure.lang.Keyword"]})]
    (is (= "transit" (.getString cfg "pekko.actor.serialization-bindings.\"clojure.lang.Keyword\"")))
    (is (not (.hasPath cfg "pekko.actor.serialization-bindings.\"clojure.lang.IPersistentCollection\"")))))

;; ---------------------------------------------------------------------------
;; Wired into an ActorSystem (the real Pekko serialization path)
;; ---------------------------------------------------------------------------

(defn- transit-system
  "A plain ActorSystem with the Transit serializer bound."
  ([name] (transit-system name {}))
  ([name opts]
   (core/actor-system name (.withFallback (ser/transit-config opts) (ConfigFactory/load)))))

(defn- pekko-round-trip
  "Serialize and deserialize `msg` the way Pekko does (identifier + manifest)."
  [^ActorSystem sys msg]
  (let [ext (SerializationExtension/get sys)
        serializer (.findSerializerFor ext msg)
        manifest (Serializers/manifestFor serializer msg)]
    (.get (.deserialize ext (.toBinary serializer msg) (.identifier serializer) manifest))))

(deftest clojure-data-uses-the-transit-serializer-test
  (let [sys (transit-system "transit-bound")]
    (try
      (let [ext (SerializationExtension/get sys)]
        (doseq [msg [{:a 1} [:v 1] #{:s} '(1 2) :kw 'sym 3/4]]
          (is (instance? CljTransitSerializer (.findSerializerFor ext msg))
              (str (class msg) " is bound to the Transit serializer")))
        (is (= ser/default-identifier (.identifier (.findSerializerFor ext {:a 1}))))
        (is (= CljTransitSerializer/MANIFEST
               (Serializers/manifestFor (.findSerializerFor ext {:a 1}) {:a 1})))
        ;; Strings/longs keep Pekko's own primitive serializers.
        (is (not (instance? CljTransitSerializer (.findSerializerFor ext "a string")))))
      (finally (core/shutdown-system sys)))))

(deftest pekko-serialization-round-trip-test
  (let [sys (transit-system "transit-round-trip")]
    (try
      (is (= sample-data (pekko-round-trip sys sample-data)))
      (is (= [:cmd {:n 1}] (pekko-round-trip sys [:cmd {:n 1}])))
      (finally (core/shutdown-system sys)))))

(deftest pekko-serialization-round-trip-msgpack-test
  (let [sys (transit-system "transit-msgpack" {:format :msgpack})]
    (try
      (is (= sample-data (pekko-round-trip sys sample-data)))
      (finally (core/shutdown-system sys)))))

(deftest serialized-actor-ref-still-works-test
  ;; A ref that made the round trip through the serializer still delivers messages.
  (let [sys (transit-system "transit-ref-usable")]
    (try
      (let [target (core/spawn sys echo-actor nil)
            back (:ref (pekko-round-trip sys {:ref target}))]
        (is (= target back))
        (core/! back [:put :delivered])
        (is (= :delivered (core/<! back [:get] 5000))))
      (finally (core/shutdown-system sys)))))

;; ---------------------------------------------------------------------------
;; create-system integration
;; ---------------------------------------------------------------------------

(deftest create-system-applies-transit-test
  (let [sys (cluster/create-system "transit-create" {:port 0 :transit-serialization true})]
    (try
      (let [cfg (.config (.settings sys))]
        (is (= ser/serializer-class (.getString cfg "pekko.actor.serializers.transit")))
        ;; The transit config wins over create-system's allow-java-serialization = on default.
        (is (false? (.getBoolean cfg "pekko.actor.allow-java-serialization")))
        (is (instance? CljTransitSerializer
                       (.findSerializerFor (SerializationExtension/get sys) {:a 1}))))
      (finally (ts/terminate-system sys)))))

(deftest create-system-transit-options-and-precedence-test
  (let [sys (cluster/create-system "transit-create-opts"
                                   {:port 0
                                    :transit-serialization {:format :msgpack :allow-java-serialization true}
                                    :extra-config "pekko-clj.serialization.transit.format = json-verbose"})]
    (try
      (let [cfg (.config (.settings sys))]
        ;; :extra-config beats :transit-serialization
        (is (= "json-verbose" (.getString cfg "pekko-clj.serialization.transit.format")))
        (is (true? (.getBoolean cfg "pekko.actor.allow-java-serialization"))))
      (finally (ts/terminate-system sys)))))

(deftest messages-serialize-end-to-end-test
  ;; `serialize-messages = on` makes Pekko serialize *and deserialize* every user
  ;; message even for local sends, so this drives the real actor-message path
  ;; through the Transit serializer.
  (let [sys (cluster/create-system "transit-verify"
                                   {:port 0
                                    :transit-serialization true
                                    :extra-config "pekko.actor.serialize-messages = on"})]
    (try
      (let [actor (core/spawn sys echo-actor nil)
            payload {:nested [{:k :v} #{1 2}] :ratio 1/3}
            _ (core/! actor [:put payload])
            delivered (core/<! actor [:get] 10000)]
        (is (= payload delivered))
        ;; What arrived is a reconstruction, not the object we sent — proof the
        ;; message really went through toBinary/fromBinary rather than being
        ;; handed over by reference.
        (is (not (identical? payload delivered))))
      (finally (ts/terminate-system sys)))))

(deftest registered-records-survive-the-live-message-path-test
  ;; The whole point of N17: a record configured on the system keeps its type
  ;; across a real serialize/deserialize round trip, without the caller passing
  ;; :records at every call site.
  (let [sys (cluster/create-system "transit-records"
                                   {:port 0
                                    :transit-serialization {:records point-classes}
                                    :extra-config "pekko.actor.serialize-messages = on"})]
    (try
      (let [actor (core/spawn sys echo-actor nil)
            payload (->Segment (->Point 0 0) (->Point 3 4) :diagonal)]
        (is (instance? CljTransitSerializer
                       (.findSerializerFor (SerializationExtension/get sys) payload))
            "records route to the Transit serializer")
        (is (= payload (pekko-round-trip sys payload)))
        (core/! actor [:put payload])
        (let [delivered (core/<! actor [:get] 10000)]
          (is (instance? Segment delivered))
          (is (instance? Point (:from delivered)))
          (is (= payload delivered))
          (is (not (identical? payload delivered)))))
      (finally (ts/terminate-system sys)))))

(deftest unregistered-record-still-decays-on-a-live-system-test
  ;; Same system minus the :records option — pins that the config option, not some
  ;; ambient record support, is what preserves the type.
  (let [sys (transit-system "transit-no-records")]
    (try
      (is (= {:x 1 :y 2} (pekko-round-trip sys (->Point 1 2))))
      (finally (core/shutdown-system sys)))))
