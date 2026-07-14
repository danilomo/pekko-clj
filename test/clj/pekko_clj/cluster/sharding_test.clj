(ns pekko-clj.cluster.sharding-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.cluster.sharding :as sharding]
            [pekko-clj.test-support :as ts :refer [eventually]]))

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
