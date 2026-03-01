(ns pekko-clj.cluster.sharding-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.cluster.sharding :as sharding])
  (:import [org.apache.pekko.actor ActorSystem]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def timeout-duration (Duration/create 10 "seconds"))

(defn await-result [future]
  (Await/result future timeout-duration))

;; ---------------------------------------------------------------------------
;; Helper: Create cluster-enabled system for sharding tests
;; ---------------------------------------------------------------------------

(defn create-sharding-system [name]
  (cluster/create-system name
    {:hostname "127.0.0.1"
     :port 0}))

(defn wait-for-cluster-up [sys]
  (let [c (cluster/cluster sys)]
    (.join c (.selfAddress c))
    (loop [attempts 50]
      (if (zero? attempts)
        false
        (let [member (cluster/self-member sys)
              status (str (.status member))]
          (if (= "Up" status)
            true
            (do
              (Thread/sleep 100)
              (recur (dec attempts)))))))))

(defn terminate-system [sys]
  (.terminate sys)
  (Await/result (.whenTerminated sys) (Duration/create 10 "seconds")))

;; ---------------------------------------------------------------------------
;; Test Actor Definitions
;; ---------------------------------------------------------------------------

(core/defactor counter-entity
  "Simple counter entity for testing"
  (init [args]
    {:entity-id (:entity-id args)
     :count 0})
  (handle [:entity-message id msg]
    ;; Unwrap entity message and update entity-id if needed
    (let [new-state (if (nil? (:entity-id state))
                      (assoc state :entity-id id)
                      state)]
      (case (first msg)
        :inc (update new-state :count inc)
        :get (do (core/reply (:count new-state)) new-state)
        :get-id (do (core/reply (:entity-id new-state)) new-state)
        new-state)))
  (handle :inc
    (update state :count inc))
  (handle :get
    (core/reply (:count state)))
  (handle :get-id
    (core/reply (:entity-id state))))

(def entity-log (atom []))

(core/defactor logging-entity
  "Entity that logs operations for testing"
  (init [args]
    {:entity-id (:entity-id args)})
  (handle [:entity-message id msg]
    (swap! entity-log conj {:id id :msg msg})
    (let [new-state (if (nil? (:entity-id state))
                      (assoc state :entity-id id)
                      state)]
      (case (first msg)
        :ping (do (core/reply :pong) new-state)
        :get-id (do (core/reply id) new-state)
        new-state))))

;; ---------------------------------------------------------------------------
;; Tests: Basic Sharding
;; ---------------------------------------------------------------------------

(deftest sharding-start-test
  (let [sys (create-sharding-system "sharding-start-test")]
    (try
      (is (wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity
                     {:type-name "Counter"
                      :num-shards 10})]
        (is (some? region))
        (is (instance? org.apache.pekko.actor.ActorRef region)))
      (finally
        (terminate-system sys)))))

(deftest sharding-tell-ask-test
  (let [sys (create-sharding-system "sharding-tell-ask-test")]
    (try
      (is (wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity
                     {:type-name "Counter"
                      :num-shards 10})]
        ;; Tell some increments
        (sharding/tell region "counter-1" [:inc])
        (sharding/tell region "counter-1" [:inc])
        (sharding/tell region "counter-1" [:inc])
        (Thread/sleep 500)
        ;; Ask for the count
        (let [count (await-result (sharding/ask region "counter-1" [:get]))]
          (is (= 3 count))))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: EntityRef
;; ---------------------------------------------------------------------------

(deftest entity-ref-creation-test
  (let [sys (create-sharding-system "entity-ref-creation-test")]
    (try
      (is (wait-for-cluster-up sys))
      (let [_ (sharding/start sys counter-entity
                {:type-name "Counter"
                 :num-shards 10})
            ref (sharding/entity-ref sys "Counter" "test-entity-1")]
        (is (some? ref))
        (is (= "test-entity-1" (:entity-id ref)))
        (is (= "Counter" (:type-name ref)))
        (is (some? (:shard-region ref))))
      (finally
        (terminate-system sys)))))

(deftest entity-ref-tell-ask-test
  (let [sys (create-sharding-system "entity-ref-tell-ask-test")]
    (try
      (is (wait-for-cluster-up sys))
      (let [_ (sharding/start sys counter-entity
                {:type-name "Counter"
                 :num-shards 10})
            ref (sharding/entity-ref sys "Counter" "entity-123")]
        ;; Use tell-entity
        (sharding/tell-entity ref [:inc])
        (sharding/tell-entity ref [:inc])
        (Thread/sleep 500)
        ;; Use ask-entity
        (let [count (await-result (sharding/ask-entity ref [:get]))]
          (is (= 2 count))))
      (finally
        (terminate-system sys)))))

(deftest entity-ref-multiple-entities-test
  (let [sys (create-sharding-system "entity-ref-multi-test")]
    (try
      (is (wait-for-cluster-up sys))
      (let [_ (sharding/start sys counter-entity
                {:type-name "Counter"
                 :num-shards 10})
            ref-a (sharding/entity-ref sys "Counter" "entity-a")
            ref-b (sharding/entity-ref sys "Counter" "entity-b")]
        ;; Increment different entities
        (sharding/tell-entity ref-a [:inc])
        (sharding/tell-entity ref-a [:inc])
        (sharding/tell-entity ref-b [:inc])
        (Thread/sleep 500)
        ;; Verify they have independent state
        (let [count-a (await-result (sharding/ask-entity ref-a [:get]))
              count-b (await-result (sharding/ask-entity ref-b [:get]))]
          (is (= 2 count-a))
          (is (= 1 count-b))))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Shard Region Info
;; ---------------------------------------------------------------------------

(deftest get-shard-region-test
  (let [sys (create-sharding-system "get-region-test")]
    (try
      (is (wait-for-cluster-up sys))
      (let [original (sharding/start sys counter-entity
                       {:type-name "TestEntity"
                        :num-shards 10})
            retrieved (sharding/get-shard-region sys "TestEntity")]
        (is (some? retrieved))
        (is (= original retrieved)))
      (finally
        (terminate-system sys)))))

(deftest shard-region-registered-test
  (let [sys (create-sharding-system "registered-test")]
    (try
      (is (wait-for-cluster-up sys))
      ;; Before starting, region should not be registered
      (is (not (sharding/shard-region-registered? sys "NotStarted")))
      ;; Start a region
      (sharding/start sys counter-entity
        {:type-name "Started"
         :num-shards 10})
      ;; Now it should be registered
      (is (sharding/shard-region-registered? sys "Started"))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Sharding Stats
;; ---------------------------------------------------------------------------

(deftest cluster-sharding-stats-test
  (let [sys (create-sharding-system "stats-test")]
    (try
      (is (wait-for-cluster-up sys))
      (let [region (sharding/start sys counter-entity
                     {:type-name "StatsEntity"
                      :num-shards 10})]
        ;; Create some entities
        (sharding/tell region "entity-1" [:inc])
        (sharding/tell region "entity-2" [:inc])
        (sharding/tell region "entity-3" [:inc])
        (Thread/sleep 1000)
        ;; Get stats
        (let [stats (await-result (sharding/cluster-sharding-stats sys "StatsEntity" 5000))]
          (is (some? stats))
          ;; Convert to map
          (let [stats-map (sharding/stats->map stats)]
            (is (contains? stats-map :regions)))))
      (finally
        (terminate-system sys)))))
