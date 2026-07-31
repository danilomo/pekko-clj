(ns pekko-clj.cluster.sharding-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.cluster.sharding :as sharding]
            [pekko-clj.persistence :as persistence]
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

;; H13: a sharded entity that echoes :pong back to whoever pinged it, and a
;; PERSISTENT actor that pings it via sharding/tell. sharding/tell routes through
;; core/!, so from a persistent command body it used to send as noSender and the
;; echo's reply went to dead letters — the caller never saw :pong.
(core/defactor h13-echo-entity
  "Sharded entity that replies :pong to its sender."
  (init [_] {})
  (handle [:ping]
    (core/reply :pong)
    state))

(persistence/defactor-persistent h13-sharding-caller
  :persistence-id (fn [args] (str "h13-scaller-" (:id args)))
  (init [args] {:region (:region args) :probe (:probe args)})
  (command [:call entity-id]
    (sharding/tell (:region state) entity-id [:ping])
    nil)
  (command :pong
    (core/! (:probe state) :pong-received)
    nil))

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
        ;; Poll until the region stats actually report the active entities — the
        ;; ClusterShardingStats gather is async, so an early reply has empty regions.
        (let [m (ts/poll-until
                 (fn []
                   (let [mm (sharding/stats->map
                             (await-result (sharding/cluster-sharding-stats sys "StatsEntity" 5000)))]
                     (when (pos? (reduce + 0 (mapcat (comp vals :stats) (vals (:regions mm)))))
                       mm)))
                 15000)]
          (is (some? m) "cluster sharding stats eventually report the entities")
          (is (contains? m :regions))
          ;; N19: each region value is {:stats {shard-id count} :failed #{}}
          (let [region-vals (vals (:regions m))]
            (is (every? #(and (map? (:stats %)) (set? (:failed %))) region-vals))
            (is (<= 1 (reduce + 0 (mapcat (comp vals :stats) region-vals)))
                "the entities show up in the per-shard counts"))))
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

;; ---------------------------------------------------------------------------
;; Tests: Persistent sharded entities (N10)
;; ---------------------------------------------------------------------------

;; The canonical Pekko sharding pattern: one event-sourced aggregate per entity
;; id, recovered from the journal whenever the entity is recreated. Both the
;; :persistence-id function and `init` receive the *entity id* — the shared Props
;; carries no per-entity args.
;; Counts how many times each persistence id has finished recovery, so a test can
;; tell "the entity was recreated and replayed its journal" apart from "the entity
;; was never stopped and still had the state in memory".
(def recoveries (atom {}))

(persistence/defactor-persistent account-entity
  "Event-sourced account, sharded by account id."
  :persistence-id (fn [id] (str "account-" id))

  (init [id] {:id id :balance 0 :history []})

  (on-recovery-complete [this]
    (swap! recoveries update
           (.persistenceId ^pekko_clj.actor.CljPersistentActor this) (fnil inc 0)))

  (command [:deposit n]
    (persistence/persist [:deposited n]))
  (command [:withdraw n]
    (persistence/persist [:withdrawn n]))
  (command [:deposit-twice n]
    (persistence/persist-all [[:deposited n] [:deposited n]]))
  (command :get
    (persistence/reply {:id (:id state) :balance (:balance state)})
    nil)
  (command :get-persistence-id
    (persistence/reply (.persistenceId ^pekko_clj.actor.CljPersistentActor this))
    nil)
  ;; Note the persistence/* context helpers: core/self and core/context read
  ;; core/*current-actor*, which is a CljActor and is not bound here.
  (command :passivate
    (sharding/passivate (persistence/context) :stop-now)
    (persistence/reply :passivating)
    nil)
  (command :stop-now
    (persistence/stop (persistence/self))
    nil)

  (event [:deposited n] (-> state
                            (update :balance + n)
                            (update :history conj [:deposited n])))
  (event [:withdrawn n] (-> state
                            (update :balance - n)
                            (update :history conj [:withdrawn n]))))

(deftest persistent-entity-recovers-after-passivation-test
  (let [sys (ts/create-cluster-system "sharded-persistent-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (reset! recoveries {})
      (let [region (sharding/start sys account-entity {:type-name "Account"
                                                       :num-shards 10})]
        ;; init got the entity id, not nil args
        (sharding/tell region "acct-1" [:deposit 100])
        (sharding/tell region "acct-1" [:withdraw 30])
        (is (eventually (= {:id "acct-1" :balance 70}
                           (await-result (sharding/ask region "acct-1" :get)))))
        ;; the persistence id is derived from the entity id
        (is (= "account-acct-1" (await-result (sharding/ask region "acct-1" :get-persistence-id))))
        (is (= 1 (get @recoveries "account-acct-1")) "recovered once, on creation")
        ;; passivate: the entity actor stops, its journal does not. Ask rather
        ;; than tell — a reply proves the command ran to completion, so a second
        ;; recovery below means "passivated and recreated", not "crashed and
        ;; restarted" (which would replay the journal just as convincingly).
        (is (= :passivating (await-result (sharding/ask region "acct-1" :passivate))))
        ;; the next message recreates it and it replays its events
        (is (eventually (= {:id "acct-1" :balance 70}
                           (await-result (sharding/ask region "acct-1" :get))))
            "state came back from the journal, not from a fresh init")
        (is (eventually (= 2 (get @recoveries "account-acct-1")))
            "a second recovery ran — the entity really was stopped and replayed")
        ;; and it keeps accumulating on top of the recovered state
        (sharding/tell region "acct-1" [:deposit 5])
        (is (eventually (= {:id "acct-1" :balance 75}
                           (await-result (sharding/ask region "acct-1" :get))))))
      (finally
        (ts/terminate-system sys)))))

(deftest persistent-entities-isolate-state-by-id-test
  (let [sys (ts/create-cluster-system "sharded-persistent-isolation-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys account-entity {:type-name "IsolatedAccount"
                                                       :num-shards 10})]
        (sharding/tell region "a" [:deposit 10])
        (sharding/tell region "b" [:deposit 7])
        (sharding/tell region "b" [:deposit-twice 1])
        (is (eventually (= {:id "a" :balance 10}
                           (await-result (sharding/ask region "a" :get)))))
        (is (eventually (= {:id "b" :balance 9}
                           (await-result (sharding/ask region "b" :get))))
            "persist-all inside a sharded entity applies both events")
        (is (= "account-b" (await-result (sharding/ask region "b" :get-persistence-id)))))
      (finally
        (ts/terminate-system sys)))))

(deftest persistent-entity-requires-a-persistence-id-test
  ;; Without :persistence-id there is nothing to derive an entity's journal from;
  ;; fail at start rather than when the first entity is created on some node.
  (let [bad {:type :persistent-actor :entity-props {:command-handler identity}}]
    (is (thrown-with-msg? IllegalArgumentException #":persistence-id"
          (sharding/start nil bad {:type-name "Bad"}))))
  ;; :args belongs to classic entities — a persistent one gets the entity id.
  (is (thrown-with-msg? IllegalArgumentException #":args does not apply"
        (sharding/start nil account-entity {:type-name "Bad" :args {:x 1}}))))

;; ---------------------------------------------------------------------------
;; Tests: :args for classic entities, region state, graceful shutdown (N10)
;; ---------------------------------------------------------------------------

(core/defactor configured-entity
  "Classic entity whose init reads shared args passed at region start."
  (init [args] {:greeting (:greeting args) :n 0})
  (handle [:get] (core/reply {:id (sharding/entity-id) :greeting (:greeting state)})))

(deftest start-args-reach-a-classic-entity-init-test
  (let [sys (ts/create-cluster-system "sharding-args-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys configured-entity
                                   {:type-name "Configured"
                                    :num-shards 10
                                    :args {:greeting "hello"}})]
        (is (eventually (= {:id "c-1" :greeting "hello"}
                           (await-result (sharding/ask region "c-1" [:get])))))
        (is (= {:id "c-2" :greeting "hello"}
               (await-result (sharding/ask region "c-2" [:get])))
            "every entity of the type shares the args"))
      (finally
        (ts/terminate-system sys)))))

(deftest shard-region-state->map-test
  (let [sys (ts/create-cluster-system "region-state-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity {:type-name "StateEntity"
                                                       :num-shards 10})]
        (sharding/tell region "s-1" [:inc])
        (sharding/tell region "s-2" [:inc])
        (is (eventually (= 1 (await-result (sharding/ask region "s-1" [:get])))))
        (let [m (ts/poll-until
                 #(let [m (sharding/state->map (await-result (sharding/shard-region-state region)))]
                    (when (= #{"s-1" "s-2"} (reduce into #{} (vals (:shards m)))) m)))]
          (is (some? m) "both entities show up in the region state")
          (is (set? (:failed m)))
          (is (every? string? (keys (:shards m))) "shard ids key the map")))
      (finally
        (ts/terminate-system sys)))))

(deftest graceful-shutdown-stops-the-region-test
  (let [sys (ts/create-cluster-system "graceful-shutdown-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity {:type-name "Draining"
                                                       :num-shards 10})]
        (sharding/tell region "g-1" [:inc])
        (is (eventually (= 1 (await-result (sharding/ask region "g-1" [:get])))))
        ;; Single node: there is nowhere to hand off to, so the shards stop and
        ;; the region terminates.
        (sharding/graceful-shutdown! region)
        (is (ts/stopped-within? sys region 15000) "the region actor terminates"))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: H13 — sharding/tell from a persistent entity round-trips
;; ---------------------------------------------------------------------------

(deftest sharding-tell-from-persistent-entity-round-trips
  ;; sharding/tell routes through core/!. From a persistent command body the
  ;; sender used to be noSender, so the echo entity's reply went to dead letters
  ;; and the persistent caller never received :pong. With the H13 fix the reply
  ;; comes back and the caller notifies the probe. Fails before the fix.
  (let [sys (ts/create-cluster-system "h13-sharding-persistent")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys h13-echo-entity {:type-name "H13Echo"
                                                        :num-shards 4})
            got (promise)
            probe (core/new-actor sys {:function (fn [_ _] (deliver got true) nil)
                                       :state nil})
            caller (persistence/spawn sys h13-sharding-caller
                                      {:id "caller-1" :region region :probe probe})]
        (core/! caller [:call "echo-1"])
        (is (true? (deref got 8000 false))
            "the echo entity's reply returned to the persistent caller"))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: B23 — entity ids survive Pekko's URL encoding of the actor name
;; ---------------------------------------------------------------------------

;; Pekko's Shard creates each entity child under
;; `URLEncoder.encode(entityId, "utf-8")` (javap-confirmed on
;; pekko-cluster-sharding_3 1.6.0) and hands the *raw* id to the entity Props.
;; It never decodes: nothing in the sharding jar references URLDecoder, because
;; Pekko keeps the raw id in its own state maps. Anything deriving the id back
;; out of the path name — `entity-id` and CljPersistentActor's entity branch —
;; has to decode it itself, or every id containing a character encoding touches
;; (/ space @ : + non-ASCII) silently reads back mangled.

(deftest entity-id-decodes-the-url-encoded-actor-name-test
  (let [sys (ts/create-cluster-system "entity-id-decoding-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity {:type-name "IdEcho"
                                                       :num-shards 10})]
        ;; The headline case: before the fix this came back as "a%2Fb+c%40d".
        (is (eventually (= "a/b c@d" (await-result (sharding/ask region "a/b c@d" [:get-id]))))
            "an entity reads back the id it was addressed with")
        ;; Round trips only if decode is a true inverse of Pekko's encode —
        ;; note `+` (encode's spelling of space) and `%` (its escape char).
        (doseq [id ["100%" "a+b" "x&y=z" "1/2/3" "a:b" "user@example.com" "münchen" "~x"]]
          (is (= id (await-result (sharding/ask region id [:get-id])))
              (str "round trip for " (pr-str id))))
        ;; Regression guard: ids URL-encoding leaves alone (only alphanumerics and
        ;; . - * _ are unreserved to URLEncoder — `~` is not) are untouched.
        (doseq [id ["plain" "counter-1" "order_9.2" "a*b"]]
          (is (= id (await-result (sharding/ask region id [:get-id])))
              (str "plain id unchanged: " (pr-str id))))
        ;; Decoding must not collapse an id onto its own encoded spelling:
        ;; "a/b" is named "a%2Fb", and "a%2Fb" is named "a%252Fb".
        (sharding/tell region "a/b" [:inc])
        (sharding/tell region "a%2Fb" [:inc])
        (sharding/tell region "a%2Fb" [:inc])
        (is (eventually (= 1 (await-result (sharding/ask region "a/b" [:get])))))
        (is (= 2 (await-result (sharding/ask region "a%2Fb" [:get])))
            "a raw id and its encoded spelling stay distinct entities"))
      (finally
        (ts/terminate-system sys)))))

(deftest persistent-entity-id-with-special-chars-keeps-one-journal-key-test
  ;; The serious half of B23: the persistence id is derived from the path name,
  ;; so a special-char entity id filed its journal under the *encoded* form.
  ;; Nothing errors — events just land under a key no one queries by.
  (let [sys (ts/create-cluster-system "sharded-persistent-encoded-id-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (reset! recoveries {})
      (let [region (sharding/start sys account-entity {:type-name "EncodedAccount"
                                                       :num-shards 10})
            id "acct/2026 a@b"
            pid (str "account-" id)]
        (sharding/tell region id [:deposit 100])
        ;; init-fn is called with the entity id too — same bug, same fix.
        (is (eventually (= {:id id :balance 100}
                           (await-result (sharding/ask region id :get))))
            "`init` received the raw entity id")
        (is (= pid (await-result (sharding/ask region id :get-persistence-id)))
            "the journal key comes from the raw id, not the encoded actor name")
        (is (= 1 (get @recoveries pid)) "recovered once, on creation")
        ;; Passivate and revive: the replay has to find the same journal key.
        (is (= :passivating (await-result (sharding/ask region id :passivate))))
        (is (eventually (= {:id id :balance 100}
                           (await-result (sharding/ask region id :get))))
            "state came back from the journal under the same key")
        (is (eventually (= 2 (get @recoveries pid)))
            "a second recovery ran — the entity really was stopped and replayed"))
      (finally
        (ts/terminate-system sys)))))

(deftest entity-message-coerces-the-id-to-a-string-test
  ;; ShardRegion$MessageExtractor.entityId is declared to return String, so a
  ;; non-string id blows up inside the extractor proxy. `entity-ref` already
  ;; coerced; `entity-message` (and thus tell/ask) did not.
  (is (= "42" (::sharding/entity-id (sharding/entity-message 42 [:x]))))
  (is (= "order-7" (::sharding/entity-id (sharding/entity-message "order-7" [:x]))))
  (is (= ":kw" (::sharding/entity-id (sharding/entity-message :kw [:x]))))
  (is (nil? (::sharding/entity-id (sharding/entity-message nil [:x])))
      "nil stays nil — the extractor reports 'no id' and Pekko drops the message")
  (is (= [:x] (::sharding/message (sharding/entity-message 42 [:x])))
      "the payload is untouched"))

(deftest numeric-entity-id-round-trips-test
  (let [sys (ts/create-cluster-system "numeric-entity-id-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity {:type-name "NumericCounter"
                                                       :num-shards 10})]
        (sharding/tell region 42 [:inc])
        (sharding/tell region 42 [:inc])
        (is (eventually (= 2 (await-result (sharding/ask region 42 [:get]))))
            "a numeric id addresses one entity instead of throwing in the extractor")
        (is (= "42" (await-result (sharding/ask region 42 [:get-id])))
            "the entity sees the stringified id")
        (is (= 2 (await-result (sharding/ask region "42" [:get])))
            "42 and \"42\" are the same entity"))
      (finally
        (ts/terminate-system sys)))))
