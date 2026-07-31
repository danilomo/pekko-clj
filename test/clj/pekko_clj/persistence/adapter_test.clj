(ns pekko-clj.persistence.adapter-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.match :refer [match]]
            [pekko-clj.persistence :as p]
            [pekko-clj.persistence.adapter :as adapter]
            [pekko-clj.core :as core]
            [pekko-clj.test-support :refer [eventually]])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config Config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [java.util UUID]))

(def ^:private base-conf (delay (ConfigFactory/load "persistence-test.conf")))

(defn- system
  "A persistence-test ActorSystem, optionally with `extra` config merged on top."
  ([name] (system name nil))
  ([name ^Config extra]
   (ActorSystem/create name (if extra (.withFallback extra @base-conf) @base-conf))))

(defn- terminate [sys]
  (.terminate sys)
  (Await/result (.whenTerminated sys) (Duration/create 10 "seconds")))

(defn- unique-pid [prefix] (str prefix "-" (UUID/randomUUID)))

(def ^:private leveldb "pekko.persistence.journal.leveldb")

;; ---------------------------------------------------------------------------
;; Schema-evolution fns (referenced by name from adapter config, so top-level)
;; ---------------------------------------------------------------------------

(defn upcast-v1->v2
  "Read-side upcast: an old [:v1 x] event becomes [:v2 x :default]; anything else
   passes through as a single event unchanged."
  [event _manifest]
  (match event
    [:v1 x] [:v2 x :default]
    :else   event))

(defn split-pair
  "Read-side one-to-many split: [:pair a b] recovers as two [:one _] events."
  [event _manifest]
  (match event
    [:pair a b] (adapter/many [[:one a] [:one b]])
    :else       event))

(defn stamp-version
  "Write-side manifest: stamp a fixed schema version on every event."
  [_event]
  "v3")

(defn attach-manifest
  "Read-side: fold the stored manifest into the recovered event."
  [event manifest]
  (conj event manifest))

;; ---------------------------------------------------------------------------
;; Actor definitions. Writer and reader share a persistence id passed as :pid.
;; ---------------------------------------------------------------------------

;; Writes old-schema [:v1 x] events (no adapter in its system).
(p/defactor-persistent v1-writer
  :persistence-id (fn [args] (:pid args))
  (init [_] {:seen []})
  (command [:put x] (p/persist [:v1 x]))
  (command :get (p/reply (:seen state)) nil)
  (event [:v1 x] (update state :seen conj x)))

;; Reads with the upcasting adapter bound: recovery replays [:v2 x d].
(p/defactor-persistent v2-reader
  :persistence-id (fn [args] (:pid args))
  (init [_] {:seen []})
  (command :get (p/reply (:seen state)) nil)
  (event [:v2 x d] (update state :seen conj [x d])))

;; Writes a single compound [:pair a b] event.
(p/defactor-persistent pair-writer
  :persistence-id (fn [args] (:pid args))
  (init [_] {:ones []})
  (command [:put a b] (p/persist [:pair a b]))
  (command :get (p/reply (:ones state)) nil)
  (event [:pair a b] (update state :ones conj [:pair a b])))

;; Reads with the splitting adapter bound: one stored event -> two [:one _].
(p/defactor-persistent ones-reader
  :persistence-id (fn [args] (:pid args))
  (init [_] {:ones []})
  (command :get (p/reply (:ones state)) nil)
  (event [:one x] (update state :ones conj x)))

;; Writes with the manifest-stamping adapter bound (manifest only).
(p/defactor-persistent manifest-writer
  :persistence-id (fn [args] (:pid args))
  (init [_] {})
  (command [:put x] (p/persist [:evt x]))
  (command :get (p/reply :ok) nil)
  (event [:evt _x] state))

;; Reads with the manifest-attaching adapter bound: recovers [:evt x manifest].
(p/defactor-persistent manifest-reader
  :persistence-id (fn [args] (:pid args))
  (init [_] {:last nil})
  (command :get (p/reply (:last state)) nil)
  (event [:evt x m] (assoc state :last [x m])))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest config-shape
  (let [^Config c (adapter/config {:journal-plugin leveldb
                                   :to-journal `stamp-version
                                   :from-journal `upcast-v1->v2
                                   :bindings ["a.B" java.lang.Long]})]
    (is (= "pekko-clj.persistence.adapter-test/stamp-version"
           (.getString c "pekko-clj.persistence.adapter.to-journal")))
    (is (= "pekko-clj.persistence.adapter-test/upcast-v1->v2"
           (.getString c "pekko-clj.persistence.adapter.from-journal")))
    (is (false? (.hasPath c "pekko-clj.persistence.adapter.manifest"))
        "an omitted hook produces no config entry")
    (is (= "pekko_clj.actor.CljEventAdapter"
           (.getString c (str leveldb ".event-adapters.clj-event-adapter"))))
    (is (= "clj-event-adapter"
           (.getString c (str leveldb ".event-adapter-bindings.\"a.B\""))))
    (is (= "clj-event-adapter"
           (.getString c (str leveldb ".event-adapter-bindings.\"java.lang.Long\"")))))
  (is (thrown-with-msg? IllegalArgumentException #":journal-plugin is required"
        (adapter/config {:from-journal `upcast-v1->v2}))))

(deftest from-journal-upcasts-old-events-on-recovery
  ;; Old [:v1 x] events, written without an adapter, recover as new-schema
  ;; [:v2 x :default] once the upcasting adapter is bound — proving LevelDB honors
  ;; event-adapters and fromJournal runs on the recovery path.
  (let [pid (unique-pid "adapter-upcast")]
    (let [sys (system "adapter-w")]
      (try
        (let [a (p/spawn sys v1-writer {:pid pid})]
          (core/! a [:put 1])
          (core/! a [:put 2])
          (is (eventually (= [1 2] (core/<! a :get 3000)))))
        (finally (terminate sys))))
    (let [cfg (adapter/config {:journal-plugin leveldb :from-journal `upcast-v1->v2})
          sys (system "adapter-r" cfg)]
      (try
        (let [a (p/spawn sys v2-reader {:pid pid})]
          (is (eventually (= [[1 :default] [2 :default]] (core/<! a :get 3000)))
              "recovered events were upcast [:v1 x] -> [:v2 x :default]"))
        (finally (terminate sys))))))

(deftest from-journal-splits-one-event-into-many
  ;; A single stored [:pair a b] recovers as two [:one _] events via (many …).
  (let [pid (unique-pid "adapter-split")]
    (let [sys (system "split-w")]
      (try
        (let [a (p/spawn sys pair-writer {:pid pid})]
          (core/! a [:put 10 20])
          (is (eventually (= [[:pair 10 20]] (core/<! a :get 3000)))))
        (finally (terminate sys))))
    (let [cfg (adapter/config {:journal-plugin leveldb :from-journal `split-pair})
          sys (system "split-r" cfg)]
      (try
        (let [a (p/spawn sys ones-reader {:pid pid})]
          (is (eventually (= [10 20] (core/<! a :get 3000)))
              "one stored event split into two recovered events, in order"))
        (finally (terminate sys))))))

(deftest manifest-round-trips-through-the-journal
  ;; The manifest stamped by toJournal/manifest on write is handed back to
  ;; fromJournal on read.
  (let [pid (unique-pid "adapter-manifest")]
    (let [cfg (adapter/config {:journal-plugin leveldb :manifest `stamp-version})
          sys (system "manifest-w" cfg)]
      (try
        (let [a (p/spawn sys manifest-writer {:pid pid})]
          (core/! a [:put 5])
          (is (= :ok (core/<! a :get 3000))))
        (finally (terminate sys))))
    (let [cfg (adapter/config {:journal-plugin leveldb :from-journal `attach-manifest})
          sys (system "manifest-r" cfg)]
      (try
        (let [a (p/spawn sys manifest-reader {:pid pid})]
          (is (eventually (= [5 "v3"] (core/<! a :get 3000)))
              "the recovered event carries the manifest written earlier"))
        (finally (terminate sys))))))
