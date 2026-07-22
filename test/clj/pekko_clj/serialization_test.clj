(ns pekko-clj.serialization-test
  "Tests for N4: the Transit-backed Clojure-data serializer and its config helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.serialization :as ser]
            [pekko-clj.test-support :as ts])
  (:import [com.typesafe.config Config ConfigFactory]
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
