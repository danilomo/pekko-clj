(ns pekko-clj.cluster-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.cluster.singleton :as singleton]
            [pekko-clj.cluster.sharding :as sharding])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def timeout-duration (Duration/create 10 "seconds"))

(defn- create-cluster-system [name]
  (let [config (ConfigFactory/load "cluster-test.conf")]
    (ActorSystem/create name config)))

(defn- terminate-system [sys]
  (.terminate sys)
  (Await/result (.whenTerminated sys) (Duration/create 15 "seconds")))

(defn- wait-for-member-up [sys]
  ;; Wait for this node to become Up in the cluster
  (let [up? (promise)]
    (cluster/register-on-member-up sys #(deliver up? true))
    ;; Join self to form single-node cluster
    (.join (cluster/cluster sys) (.selfAddress (cluster/cluster sys)))
    (deref up? 10000 false)))

;; ---------------------------------------------------------------------------
;; Test Actor Definitions
;; ---------------------------------------------------------------------------

(core/defactor counter-actor
  "Simple counter for testing"
  (init [args] {:count (or (:initial args) 0)})
  (handle :increment
    (update state :count inc))
  (handle :get
    (core/reply (:count state)))
  (handle [:set n]
    (assoc state :count n))
  (handle :stop
    ;; Termination message for singleton
    state))

(core/defactor entity-actor
  "Sharded entity actor"
  (init [args]
    {:entity-id nil
     :data nil})
  ;; Handle wrapped messages from sharding
  (handle [:entity-message entity-id [:set-data data]]
    (assoc state :entity-id entity-id :data data))
  (handle [:entity-message entity-id :get-data]
    (core/reply {:entity-id (or (:entity-id state) entity-id) :data (:data state)}))
  (handle [:entity-message entity-id :get-id]
    (core/reply (or (:entity-id state) entity-id)))
  ;; Also handle direct messages for testing
  (handle [:set-data data]
    (assoc state :data data))
  (handle :get-data
    (core/reply {:entity-id (:entity-id state) :data (:data state)}))
  (handle :get-id
    (core/reply (:entity-id state))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Membership
;; ---------------------------------------------------------------------------

(deftest cluster-self-member
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))
      (let [self (cluster/self-member sys)]
        (is (some? self))
        (is (= :up (keyword (clojure.string/lower-case (str (.status self)))))))
      (finally
        (terminate-system sys)))))

(deftest cluster-members-list
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))
      (let [members (cluster/members sys)]
        (is (= 1 (count members)))
        (is (= :up (:status (first members)))))
      (finally
        (terminate-system sys)))))

(deftest cluster-leader
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))
      ;; In a single-node cluster, this node is the leader
      (Thread/sleep 500) ; Give time for leader election
      (is (cluster/is-leader? sys))
      (finally
        (terminate-system sys)))))

(deftest cluster-subscribe-events
  (let [sys (create-cluster-system "cluster-test")
        events (atom [])]
    (try
      (let [subscriber (cluster/subscribe sys
                         (fn [event]
                           (swap! events conj (:type event))))]
        (is (some? subscriber))
        ;; Join to trigger events
        (.join (cluster/cluster sys) (.selfAddress (cluster/cluster sys)))
        (Thread/sleep 1000)
        ;; Should have received some membership events
        (is (seq @events)))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Singleton
;; ---------------------------------------------------------------------------

(deftest singleton-manager-starts
  ;; Note: Full singleton proxy communication requires a stable cluster.
  ;; This test verifies the singleton manager can be started.
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))

      ;; Start singleton manager
      (let [manager (singleton/start sys counter-actor
                      {:name "test-singleton"
                       :args {:initial 10}})]
        (is (some? manager))
        (is (= "test-singleton" (.name (.path manager)))))
      (finally
        (terminate-system sys)))))

(deftest singleton-proxy-creates
  ;; Note: Full singleton communication in single-node tests is complex.
  ;; This test verifies the proxy can be created.
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))

      ;; Start singleton manager
      (let [manager (singleton/start sys counter-actor
                      {:name "proxy-test-singleton"
                       :args {:initial 5}})]
        (is (some? manager))

        ;; Create proxy
        (let [proxy-ref (singleton/proxy sys
                          {:singleton-manager-path "/user/proxy-test-singleton"})]
          (is (some? proxy-ref))))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Sharding
;; ---------------------------------------------------------------------------

(deftest sharding-start-and-send
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))

      ;; Start sharding
      (let [region (sharding/start sys entity-actor
                     {:type-name "TestEntity"
                      :num-shards 10})]
        (is (some? region))

        ;; Give time for sharding to initialize
        (Thread/sleep 1000)

        ;; Send message to entity "entity-1"
        (sharding/tell region "entity-1" [:set-data "hello"])
        (Thread/sleep 300)

        ;; Query the entity
        (let [result (Await/result (sharding/ask region "entity-1" :get-data 5000)
                                   timeout-duration)]
          (is (= "entity-1" (:entity-id result)))
          (is (= "hello" (:data result)))))
      (finally
        (terminate-system sys)))))

(deftest sharding-multiple-entities
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))

      (let [region (sharding/start sys entity-actor
                     {:type-name "MultiEntity"
                      :num-shards 10})]
        (Thread/sleep 1000)

        ;; Send to multiple entities
        (sharding/tell region "order-1" [:set-data {:item "book" :qty 2}])
        (sharding/tell region "order-2" [:set-data {:item "pen" :qty 5}])
        (sharding/tell region "order-3" [:set-data {:item "notebook" :qty 1}])
        (Thread/sleep 500)

        ;; Query each
        (let [r1 (Await/result (sharding/ask region "order-1" :get-data) timeout-duration)
              r2 (Await/result (sharding/ask region "order-2" :get-data) timeout-duration)
              r3 (Await/result (sharding/ask region "order-3" :get-data) timeout-duration)]
          (is (= "order-1" (:entity-id r1)))
          (is (= "order-2" (:entity-id r2)))
          (is (= "order-3" (:entity-id r3)))
          (is (= {:item "book" :qty 2} (:data r1)))
          (is (= {:item "pen" :qty 5} (:data r2)))
          (is (= {:item "notebook" :qty 1} (:data r3)))))
      (finally
        (terminate-system sys)))))

(deftest sharding-entity-id-available
  (let [sys (create-cluster-system "cluster-test")]
    (try
      (is (wait-for-member-up sys))

      (let [region (sharding/start sys entity-actor
                     {:type-name "IdEntity"
                      :num-shards 10})]
        (Thread/sleep 1000)

        ;; Entity should know its own ID
        (let [result (Await/result (sharding/ask region "my-entity-id" :get-id)
                                   timeout-duration)]
          (is (= "my-entity-id" result))))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Utilities
;; ---------------------------------------------------------------------------

(deftest cluster-create-system-with-config
  (let [sys (cluster/create-system "config-test"
              {:hostname "127.0.0.1"
               :port 0
               :roles ["test-role"]})]
    (try
      (is (some? sys))
      (is (= "config-test" (.name sys)))
      ;; Should have cluster provider
      (is (some? (cluster/cluster sys)))
      (finally
        (terminate-system sys)))))

(deftest cluster-has-role
  (let [sys (cluster/create-system "role-test"
              {:hostname "127.0.0.1"
               :port 0
               :roles ["backend" "api"]})]
    (try
      (is (wait-for-member-up sys))
      (is (cluster/has-role? sys "backend"))
      (is (cluster/has-role? sys "api"))
      (is (not (cluster/has-role? sys "frontend")))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: New Cluster Parity Features
;; ---------------------------------------------------------------------------

(deftest join-seed-nodes-test
  (testing "Join cluster with seed nodes"
    (let [sys (cluster/create-system "seed-test"
                {:hostname "127.0.0.1"
                 :port 0})]
      (try
        ;; Test that join-seed-nodes can be called with a list of addresses
        ;; In a single-node test, we use our own address
        (let [self-addr (str (.selfAddress (cluster/cluster sys)))]
          (cluster/join-seed-nodes sys [self-addr])
          (Thread/sleep 1000)
          ;; After joining, we should have at least one member
          (is (<= 1 (count (cluster/members sys)))))
        (finally
          (terminate-system sys))))))

(deftest is-terminated-test
  (testing "Check cluster termination status"
    (let [sys (cluster/create-system "term-test"
                {:hostname "127.0.0.1"
                 :port 0})]
      (try
        (is (wait-for-member-up sys))
        ;; Cluster should not be terminated while running
        (is (not (cluster/is-terminated? sys)))
        (finally
          (terminate-system sys))))))

(deftest members-by-age-test
  (testing "Members sorted by age"
    (let [sys (cluster/create-system "age-test"
                {:hostname "127.0.0.1"
                 :port 0})]
      (try
        (is (wait-for-member-up sys))
        (let [members (cluster/members-by-age sys)]
          ;; Should have one member in single-node cluster
          (is (= 1 (count members)))
          ;; Each member should have expected keys
          (let [member (first members)]
            (is (contains? member :address))
            (is (contains? member :status))
            (is (contains? member :upNumber))))
        (finally
          (terminate-system sys))))))

(deftest state-snapshot-test
  (testing "Get cluster state snapshot"
    (let [sys (cluster/create-system "snapshot-test"
                {:hostname "127.0.0.1"
                 :port 0})]
      (try
        (is (wait-for-member-up sys))
        (Thread/sleep 500) ; Allow state to stabilize
        (let [snapshot (cluster/state-snapshot sys)]
          ;; Should have required keys
          (is (contains? snapshot :members))
          (is (contains? snapshot :unreachable))
          (is (contains? snapshot :leader))
          (is (contains? snapshot :seen-by))
          ;; Should have one member
          (is (= 1 (count (:members snapshot))))
          ;; No unreachable members in healthy single-node cluster
          (is (empty? (:unreachable snapshot)))
          ;; Should have a leader (self in single-node)
          (is (some? (:leader snapshot)))
          ;; Seen-by should contain self
          (is (seq (:seen-by snapshot))))
        (finally
          (terminate-system sys))))))

(deftest prepare-for-shutdown-test
  (testing "Coordinated cluster shutdown preparation"
    (let [sys (cluster/create-system "shutdown-test"
                {:hostname "127.0.0.1"
                 :port 0})
          shutdown-event (promise)]
      (try
        (is (wait-for-member-up sys))
        ;; Subscribe to cluster events to observe shutdown event
        (cluster/subscribe sys
          (fn [event]
            (when (= :member-preparing-for-shutdown (:type event))
              (deliver shutdown-event event))))
        ;; Call prepare-for-shutdown
        (cluster/prepare-for-shutdown sys)
        ;; Should receive shutdown event within timeout
        (let [event (deref shutdown-event 5000 nil)]
          (is (some? event) "Should receive member-preparing-for-shutdown event")
          (when event
            (is (= :member-preparing-for-shutdown (:type event)))))
        (finally
          (terminate-system sys))))))
