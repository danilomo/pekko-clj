(ns pekko-clj.cluster.sharding-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.cluster.sharding :as sharding]
            [pekko-clj.serialization :as serialization]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.cluster.sharding ClusterShardingSettings
            ClusterShardingSettings$PassivationStrategySettings]))

;; core/<?> now returns a CompletableFuture; block on it (deref returns nil on
;; timeout, rethrows the actor's failure otherwise).
(defn await-result [future]
  (deref future 10000 nil))

;; ---------------------------------------------------------------------------
;; Test Actor Definitions
;; ---------------------------------------------------------------------------

;; Entities match the raw (unwrapped) payload they are sent and read their own id
;; via (sharding/entity-id).
(core/defactor counter-entity
  "Simple counter entity for testing"
  (init [_] {:count 0})
  (handle [:inc]
    (update state :count inc))
  (handle [:get]
    (core/reply (:count state)))
  (handle [:get-id]
    (core/reply (sharding/entity-id))))

(def entity-log (atom []))

(core/defactor logging-entity
  "Entity that logs operations for testing"
  (init [_] {})
  (handle [:ping]
    (swap! entity-log conj {:id (sharding/entity-id) :msg :ping})
    (core/reply :pong)
    state)
  (handle [:get-id]
    (core/reply (sharding/entity-id))
    state))

;; ---------------------------------------------------------------------------
;; Tests: Basic Sharding
;; ---------------------------------------------------------------------------

(deftest sharding-start-test
  (let [sys (ts/create-cluster-system "sharding-start-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity
                                   {:type-name "Counter"
                                    :num-shards 10})]
        (is (some? region))
        (is (instance? org.apache.pekko.actor.ActorRef region)))
      (finally
        (ts/terminate-system sys)))))

(deftest sharding-tell-ask-test
  (let [sys (ts/create-cluster-system "sharding-tell-ask-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity
                                   {:type-name "Counter"
                                    :num-shards 10})]
        ;; Tell some increments
        (sharding/tell region "counter-1" [:inc])
        (sharding/tell region "counter-1" [:inc])
        (sharding/tell region "counter-1" [:inc])
        ;; Poll until the three increments have been applied.
        (is (eventually (= 3 (await-result (sharding/ask region "counter-1" [:get]))))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: EntityRef
;; ---------------------------------------------------------------------------

(deftest entity-ref-creation-test
  (let [sys (ts/create-cluster-system "entity-ref-creation-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [_ (sharding/start sys counter-entity
                              {:type-name "Counter"
                               :num-shards 10})
            ref (sharding/entity-ref sys "Counter" "test-entity-1")]
        (is (some? ref))
        (is (= "test-entity-1" (:entity-id ref)))
        (is (= "Counter" (:type-name ref)))
        (is (some? (:shard-region ref))))
      (finally
        (ts/terminate-system sys)))))

(deftest entity-ref-tell-ask-test
  (let [sys (ts/create-cluster-system "entity-ref-tell-ask-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [_ (sharding/start sys counter-entity
                              {:type-name "Counter"
                               :num-shards 10})
            ref (sharding/entity-ref sys "Counter" "entity-123")]
        ;; Use tell-entity
        (sharding/tell-entity ref [:inc])
        (sharding/tell-entity ref [:inc])
        ;; Use ask-entity (poll until both increments applied)
        (is (eventually (= 2 (await-result (sharding/ask-entity ref [:get]))))))
      (finally
        (ts/terminate-system sys)))))

(deftest entity-ref-multiple-entities-test
  (let [sys (ts/create-cluster-system "entity-ref-multi-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [_ (sharding/start sys counter-entity
                              {:type-name "Counter"
                               :num-shards 10})
            ref-a (sharding/entity-ref sys "Counter" "entity-a")
            ref-b (sharding/entity-ref sys "Counter" "entity-b")]
        ;; Increment different entities
        (sharding/tell-entity ref-a [:inc])
        (sharding/tell-entity ref-a [:inc])
        (sharding/tell-entity ref-b [:inc])
        ;; Verify they have independent state
        (is (eventually (= 2 (await-result (sharding/ask-entity ref-a [:get])))))
        (is (eventually (= 1 (await-result (sharding/ask-entity ref-b [:get]))))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Shard Region Info
;; ---------------------------------------------------------------------------

(deftest get-shard-region-test
  (let [sys (ts/create-cluster-system "get-region-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [original (sharding/start sys counter-entity
                                     {:type-name "TestEntity"
                                      :num-shards 10})
            retrieved (sharding/get-shard-region sys "TestEntity")]
        (is (some? retrieved))
        (is (= original retrieved)))
      (finally
        (ts/terminate-system sys)))))

(deftest shard-region-registered-test
  (let [sys (ts/create-cluster-system "registered-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      ;; Before starting, region should not be registered
      (is (not (sharding/shard-region-registered? sys "NotStarted")))
      ;; Start a region
      (sharding/start sys counter-entity
                      {:type-name "Started"
                       :num-shards 10})
      ;; Now it should be registered
      (is (sharding/shard-region-registered? sys "Started"))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Passivation settings (N5)
;; ---------------------------------------------------------------------------

(defn- opt-get
  "Value of a scala.Option, or nil when empty."
  [^scala.Option o]
  (when (.isDefined o) (.get o)))

(deftest passivation-settings-idle-test
  (let [ps (sharding/passivation-settings {:idle-timeout 60000})
        idle (opt-get (.idleEntitySettings ps))]
    (is (instance? ClusterShardingSettings$PassivationStrategySettings ps))
    (is (some? idle) ":idle-timeout alone implies the idle strategy")
    (is (= 60000 (.toMillis (.timeout idle))))
    (is (nil? (opt-get (.interval idle))) "no interval unless asked for"))
  ;; Explicit interval + a java.time.Duration timeout
  (let [ps (sharding/passivation-settings {:strategy :idle
                                           :idle-timeout (java.time.Duration/ofSeconds 30)
                                           :idle-interval 5000})
        idle (opt-get (.idleEntitySettings ps))]
    (is (= 30000 (.toMillis (.timeout idle))))
    (is (= 5000 (.toMillis ^scala.concurrent.duration.FiniteDuration (opt-get (.interval idle)))))))

(deftest passivation-settings-replacement-policies-test
  (let [lru (sharding/passivation-settings {:strategy :least-recently-used
                                            :active-entity-limit 5000})
        policy (opt-get (.replacementPolicySettings lru))]
    (is (= 5000 (opt-get (.activeEntityLimit lru))))
    (is (= "LeastRecentlyUsedSettings" (.getSimpleName (class policy))))
    (is (nil? (opt-get (.segmentedSettings policy))) "not segmented by default"))
  ;; Segmented (SLRU) — by level count and by proportions
  (let [by-levels (opt-get (.replacementPolicySettings
                            (sharding/passivation-settings {:strategy :least-recently-used
                                                            :active-entity-limit 100
                                                            :segmented 2})))
        by-props (opt-get (.replacementPolicySettings
                           (sharding/passivation-settings {:strategy :least-recently-used
                                                           :active-entity-limit 100
                                                           :segmented [0.2 0.8]})))]
    (is (= 2 (.levels (opt-get (.segmentedSettings by-levels)))))
    (let [segmented (opt-get (.segmentedSettings by-props))]
      (is (= [0.2 0.8] (mapv double (scala.jdk.javaapi.CollectionConverters/asJava
                                     (.proportions segmented))))
          "proportions are passed through to the segmented levels")))
  (let [mru (sharding/passivation-settings {:strategy :most-recently-used
                                            :active-entity-limit 10})]
    (is (= "MostRecentlyUsedSettings"
           (.getSimpleName (class (opt-get (.replacementPolicySettings mru)))))))
  (let [lfu (sharding/passivation-settings {:strategy :least-frequently-used
                                            :active-entity-limit 10
                                            :dynamic-aging true})
        policy (opt-get (.replacementPolicySettings lfu))]
    (is (= "LeastFrequentlyUsedSettings" (.getSimpleName (class policy))))
    (is (true? (.dynamicAging policy)))))

(deftest passivation-settings-disabled-and-unknown-test
  (let [ps (sharding/passivation-settings {:strategy :none})]
    (is (nil? (opt-get (.idleEntitySettings ps))))
    (is (nil? (opt-get (.activeEntityLimit ps))))
    (is (nil? (opt-get (.replacementPolicySettings ps)))))
  ;; An empty map disables passivation rather than guessing a strategy
  (is (nil? (opt-get (.idleEntitySettings (sharding/passivation-settings {})))))
  (is (thrown? IllegalArgumentException
        (sharding/passivation-settings {:strategy :bogus :active-entity-limit 1})))
  (is (thrown? IllegalArgumentException
        (sharding/passivation-settings {:strategy :least-recently-used
                                        :active-entity-limit 1
                                        :segmented :nonsense}))))

(deftest sharding-settings-from-opts-test
  (let [sys (ts/create-cluster-system "sharding-settings-test")]
    (try
      ;; :passivate-after is the idle shorthand. (Regression: this used to call
      ;; ClusterShardingSettings.withPassivateIdleEntityAfter, which does not exist
      ;; in Pekko 1.6 — every `start` with :passivate-after threw.)
      (let [^ClusterShardingSettings s (sharding/sharding-settings sys {:passivate-after 120000})
            idle (opt-get (.idleEntitySettings (.passivationStrategySettings s)))]
        (is (= 120000 (.toMillis (.timeout idle)))))
      ;; Full passivation map, role, remember-entities + store mode, plugins
      (let [^ClusterShardingSettings s
            (sharding/sharding-settings sys {:role "workers"
                                             :remember-entities true
                                             :remember-entities-store :eventsourced
                                             :journal-plugin-id "pekko.persistence.journal.inmem"
                                             :snapshot-plugin-id "pekko.persistence.snapshot-store.local"
                                             :passivation {:strategy :least-recently-used
                                                           :active-entity-limit 42}})]
        (is (= "workers" (opt-get (.role s))))
        (is (true? (.rememberEntities s)))
        (is (= "eventsourced" (.rememberEntitiesStore s)))
        (is (= "pekko.persistence.journal.inmem" (.journalPluginId s)))
        (is (= 42 (opt-get (.activeEntityLimit (.passivationStrategySettings s))))))
      ;; Defaults: the store mode stays whatever config says (ddata in the test conf)
      (is (= "ddata" (.rememberEntitiesStore (sharding/sharding-settings sys {}))))
      (is (thrown? IllegalArgumentException
            (sharding/sharding-settings sys {:remember-entities-store :sqlite})))
      ;; A ready-made PassivationStrategySettings is accepted as-is
      (let [^ClusterShardingSettings s
            (sharding/sharding-settings
             sys {:passivation (sharding/passivation-settings {:idle-timeout 7000})})]
        (is (= 7000 (.toMillis (.timeout (opt-get (.idleEntitySettings
                                                   (.passivationStrategySettings s))))))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Passivation end-to-end (N5)
;; ---------------------------------------------------------------------------

(def stopped-entities (atom #{}))

(core/defactor passivating-entity
  "Counter entity that records its id when stopped, and passivates on request."
  (init [_] {:count 0})
  (handle [:inc] (update state :count inc))
  (handle [:get] (core/reply (:count state)))
  (handle [:passivate]
    (sharding/passivate :stop-now)
    state)
  (handle :stop-now
    (core/stop (core/self))
    state)
  (on-stop (swap! stopped-entities conj (sharding/entity-id))))

(deftest idle-passivation-stops-entity-test
  (let [sys (ts/create-cluster-system "idle-passivation-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (reset! stopped-entities #{})
      (let [region (sharding/start sys passivating-entity
                                   {:type-name "IdlePassivating"
                                    :num-shards 10
                                    :passivation {:strategy :idle
                                                  :idle-timeout 1000
                                                  :idle-interval 200}})]
        (sharding/tell region "idle-1" [:inc])
        (is (eventually (= 1 (await-result (sharding/ask region "idle-1" [:get])))))
        ;; Left alone, the shard passivates it.
        (is (eventually 15000 (contains? @stopped-entities "idle-1")))
        ;; A new message recreates it with fresh state.
        (is (eventually (= 0 (await-result (sharding/ask region "idle-1" [:get]))))))
      (finally
        (ts/terminate-system sys)))))

(deftest manual-passivate-recreates-entity-test
  (let [sys (ts/create-cluster-system "manual-passivate-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (reset! stopped-entities #{})
      (let [region (sharding/start sys passivating-entity
                                   {:type-name "ManualPassivating"
                                    :num-shards 10})]
        (sharding/tell region "manual-1" [:inc])
        (sharding/tell region "manual-1" [:inc])
        (is (eventually (= 2 (await-result (sharding/ask region "manual-1" [:get])))))
        ;; Ask the shard to passivate: the entity gets :stop-now and stops.
        (sharding/tell region "manual-1" [:passivate])
        (is (eventually (contains? @stopped-entities "manual-1")))
        ;; The next message brings it back with fresh state.
        (is (eventually (= 0 (await-result (sharding/ask region "manual-1" [:get]))))))
      (finally
        (ts/terminate-system sys)))))

(deftest start-with-stop-message-and-remember-entities-test
  ;; The hand-off stop message goes through a different ClusterSharding.start
  ;; overload (it also needs an allocation strategy); remember-entities disables
  ;; automatic passivation but must still route messages.
  (let [sys (ts/create-cluster-system "stop-message-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys passivating-entity
                                   {:type-name "HandOff"
                                    :num-shards 10
                                    :remember-entities true
                                    :stop-message :stop-now})]
        (sharding/tell region "handoff-1" [:inc])
        (is (eventually (= 1 (await-result (sharding/ask region "handoff-1" [:get]))))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Sharding Stats
;; ---------------------------------------------------------------------------

(deftest cluster-sharding-stats-test
  (let [sys (ts/create-cluster-system "stats-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity
                                   {:type-name "StatsEntity"
                                    :num-shards 10})]
        ;; Create some entities
        (sharding/tell region "entity-1" [:inc])
        (sharding/tell region "entity-2" [:inc])
        (sharding/tell region "entity-3" [:inc])
        ;; Get stats once the region responds
        (let [stats (ts/poll-until
                     #(await-result (sharding/cluster-sharding-stats sys "StatsEntity" 5000)))]
          (is (some? stats))
          (is (contains? (sharding/stats->map stats) :regions))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Envelope serialization (B14)
;; ---------------------------------------------------------------------------

;; Replies with a map: `serialize-messages = on` plus Transit (which turns Java
;; serialization off) means every message needs a real serializer, and the
;; Transit bindings cover Clojure data — a bare Long reply would not be covered.
(core/defactor transit-entity
  "Entity used to drive the envelope through a real serializer."
  (init [_] {:count 0})
  (handle [:inc n]
    (update state :count + n))
  (handle [:get]
    (core/reply {:id (sharding/entity-id) :count (:count state)})))

(deftest entity-message-envelope-is-plain-data-test
  ;; B14: the envelope used to be a defrecord. Records are bound to the Transit
  ;; serializer (they are IPersistentCollections) but Transit has no record
  ;; handlers, so the library's own envelope could not cross the wire under the
  ;; library's own serializer. It is plain data now, and round trips.
  (let [envelope (sharding/entity-message "order-7" [:add-item {:sku "ABC"}])]
    (is (sharding/entity-message? envelope))
    (is (= envelope (serialization/read-bytes (serialization/write-bytes envelope)))
        "the envelope survives a Transit round trip")
    (is (not (sharding/entity-message? {:entity-id "order-7" :message [:x]}))
        "a user map with similar unqualified keys is not an envelope")))

(deftest sharding-under-transit-serialization-test
  ;; End-to-end: `serialize-messages = on` makes Pekko serialize and deserialize
  ;; every user message even for local sends, so each tell/ask below really goes
  ;; through the Transit serializer — the path that failed for every cross-node
  ;; message while the envelope was a record.
  (let [sys (cluster/create-system "sharding-transit-test"
                                   {:port 0
                                    :transit-serialization true
                                    :extra-config "pekko.actor.serialize-messages = on"})]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys transit-entity
                                   {:type-name "TransitCounter"
                                    :num-shards 10})]
        (sharding/tell region "e-1" [:inc 2])
        (sharding/tell region "e-1" [:inc 3])
        (sharding/tell region "e-2" [:inc 7])
        (is (eventually (= {:id "e-1" :count 5}
                           (await-result (sharding/ask region "e-1" [:get] 10000)))))
        (is (= {:id "e-2" :count 7}
               (await-result (sharding/ask region "e-2" [:get] 10000)))
            "entities stay isolated across the serialized envelope"))
      (finally
        (ts/terminate-system sys)))))
