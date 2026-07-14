(ns pekko-clj.cluster-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.cluster.singleton :as singleton]
            [pekko-clj.cluster.sharding :as sharding]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

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

;; Sharded entity: matches the raw (unwrapped) message and reads its own id via
;; (sharding/entity-id).
(core/defactor entity-actor
  "Sharded entity actor"
  (init [_] {:data nil})
  (handle [:set-data data]
    (assoc state :data data))
  (handle :get-data
    (core/reply {:entity-id (sharding/entity-id) :data (:data state)}))
  (handle :get-id
    (core/reply (sharding/entity-id))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Membership
;; ---------------------------------------------------------------------------

(deftest cluster-self-member
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [self (cluster/self-member sys)]
        (is (some? self))
        (is (= :up (keyword (clojure.string/lower-case (str (.status self)))))))
      (finally
        (ts/terminate-system sys)))))

(deftest cluster-members-list
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [members (cluster/members sys)]
        (is (= 1 (count members)))
        (is (= :up (:status (first members)))))
      (finally
        (ts/terminate-system sys)))))

(deftest cluster-leader
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      ;; In a single-node cluster, this node becomes the leader.
      (is (eventually (cluster/is-leader? sys)))
      (finally
        (ts/terminate-system sys)))))

(deftest cluster-subscribe-events
  (let [sys (ts/create-cluster-system "cluster-test")
        events (atom [])]
    (try
      (let [subscriber (cluster/subscribe sys
                         (fn [event]
                           (swap! events conj (:type event))))]
        (is (some? subscriber))
        ;; Join to trigger events
        (.join (cluster/cluster sys) (.selfAddress (cluster/cluster sys)))
        ;; Should receive some membership events.
        (is (eventually (seq @events))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: create-system config + join (B5)
;; ---------------------------------------------------------------------------

(deftest create-system-accepts-config-object
  ;; A Config object is used verbatim (falling back to reference.conf).
  (let [cfg (.withFallback
              (ConfigFactory/parseString "my.custom.key = 42\npekko.actor.provider = local")
              (ConfigFactory/load))
        sys (cluster/create-system "b5-config-passthrough" cfg)]
    (try
      (let [config (.config (.settings sys))]
        (is (= 42 (.getInt config "my.custom.key")))
        (is (= "local" (.getString config "pekko.actor.provider"))))
      (finally
        (ts/terminate-system sys)))))

(deftest create-system-applies-roles-and-hostname
  (let [sys (cluster/create-system "b5-roles"
              {:hostname "127.0.0.1" :port 0 :roles ["backend" "data-node"]})]
    (try
      (let [config (.config (.settings sys))]
        (is (= "127.0.0.1" (.getString config "pekko.remote.artery.canonical.hostname")))
        (is (= ["backend" "data-node"]
               (vec (.getStringList config "pekko.cluster.roles")))))
      (finally
        (ts/terminate-system sys)))))

(deftest create-system-merges-extra-config
  ;; :extra-config overrides a generated default (provider) and adds a new key.
  (let [sys (cluster/create-system "b5-extra"
              {:hostname "127.0.0.1" :port 0
               :extra-config (str "pekko.actor.provider = local\n"
                                  "pekko.cluster.min-nr-of-members = 3")})]
    (try
      (let [config (.config (.settings sys))]
        ;; overrides the generated provider = cluster
        (is (= "local" (.getString config "pekko.actor.provider")))
        ;; adds a key not present in the generated config
        (is (= 3 (.getInt config "pekko.cluster.min-nr-of-members"))))
      (finally
        (ts/terminate-system sys)))))

(deftest join-no-arg-with-no-seeds-is-noop
  ;; Regression: the 1-arity previously called a non-existent no-arg .join().
  (let [sys (cluster/create-system "b5-join" {:hostname "127.0.0.1" :port 0})]
    (try
      (is (nil? (cluster/join sys)))
      (finally
        (ts/terminate-system sys)))))

(deftest join-no-arg-routes-to-configured-seed-nodes
  ;; With seed-nodes configured, (join sys) parses them and calls joinSeedNodes
  ;; (async — it must not throw synchronously).
  (let [sys (cluster/create-system "b5-join-seeds"
              {:hostname "127.0.0.1" :port 0
               :seed-nodes ["pekko://b5-join-seeds@127.0.0.1:25599"]})]
    (try
      (is (nil? (cluster/join sys)))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Singleton
;; ---------------------------------------------------------------------------

(deftest singleton-manager-starts
  ;; Note: Full singleton proxy communication requires a stable cluster.
  ;; This test verifies the singleton manager can be started.
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))

      ;; Start singleton manager
      (let [manager (singleton/start sys counter-actor
                      {:name "test-singleton"
                       :args {:initial 10}})]
        (is (some? manager))
        (is (= "test-singleton" (.name (.path manager)))))
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-proxy-creates
  ;; Note: Full singleton communication in single-node tests is complex.
  ;; This test verifies the proxy can be created.
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))

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
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Cluster Sharding
;; ---------------------------------------------------------------------------

(deftest sharding-start-and-send
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))

      ;; Start sharding
      (let [region (sharding/start sys entity-actor
                     {:type-name "TestEntity"
                      :num-shards 10})]
        (is (some? region))
        ;; Send a message to entity "entity-1" (created on demand).
        (sharding/tell region "entity-1" [:set-data "hello"])
        ;; Poll until the entity has applied the data.
        (let [result (ts/poll-until
                      #(let [r (deref (sharding/ask region "entity-1" :get-data 5000) 5000 nil)]
                         (when (= "hello" (:data r)) r)))]
          (is (= "entity-1" (:entity-id result)))
          (is (= "hello" (:data result)))))
      (finally
        (ts/terminate-system sys)))))

(deftest sharding-multiple-entities
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))

      (let [region (sharding/start sys entity-actor
                     {:type-name "MultiEntity"
                      :num-shards 10})]
        ;; Send to multiple entities
        (sharding/tell region "order-1" [:set-data {:item "book" :qty 2}])
        (sharding/tell region "order-2" [:set-data {:item "pen" :qty 5}])
        (sharding/tell region "order-3" [:set-data {:item "notebook" :qty 1}])
        ;; Poll until all three entities have applied their data.
        (let [ask #(deref (sharding/ask region % :get-data) 5000 nil)
              [r1 r2 r3] (ts/poll-until
                          #(let [r1 (ask "order-1") r2 (ask "order-2") r3 (ask "order-3")]
                             (when (and (= {:item "book" :qty 2} (:data r1))
                                        (= {:item "pen" :qty 5} (:data r2))
                                        (= {:item "notebook" :qty 1} (:data r3)))
                               [r1 r2 r3])))]
          (is (= "order-1" (:entity-id r1)))
          (is (= "order-2" (:entity-id r2)))
          (is (= "order-3" (:entity-id r3)))
          (is (= {:item "book" :qty 2} (:data r1)))
          (is (= {:item "pen" :qty 5} (:data r2)))
          (is (= {:item "notebook" :qty 1} (:data r3)))))
      (finally
        (ts/terminate-system sys)))))

(deftest sharding-entity-id-available
  (let [sys (ts/create-cluster-system "cluster-test")]
    (try
      (is (ts/wait-for-cluster-up sys))

      (let [region (sharding/start sys entity-actor
                     {:type-name "IdEntity"
                      :num-shards 10})]
        ;; Entity should know its own ID (poll until it responds).
        (let [result (ts/poll-until
                      #(deref (sharding/ask region "my-entity-id" :get-id) 5000 nil))]
          (is (= "my-entity-id" result))))
      (finally
        (ts/terminate-system sys)))))

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
        (ts/terminate-system sys)))))

(deftest cluster-has-role
  (let [sys (cluster/create-system "role-test"
              {:hostname "127.0.0.1"
               :port 0
               :roles ["backend" "api"]})]
    (try
      (is (ts/wait-for-cluster-up sys))
      (is (cluster/has-role? sys "backend"))
      (is (cluster/has-role? sys "api"))
      (is (not (cluster/has-role? sys "frontend")))
      (finally
        (ts/terminate-system sys)))))

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
          ;; After joining, we should have at least one member.
          (is (eventually (<= 1 (count (cluster/members sys))))))
        (finally
          (ts/terminate-system sys))))))

(deftest is-terminated-test
  (testing "Check cluster termination status"
    (let [sys (cluster/create-system "term-test"
                {:hostname "127.0.0.1"
                 :port 0})]
      (try
        (is (ts/wait-for-cluster-up sys))
        ;; Cluster should not be terminated while running
        (is (not (cluster/is-terminated? sys)))
        (finally
          (ts/terminate-system sys))))))

(deftest members-by-age-test
  (testing "Members sorted by age"
    (let [sys (cluster/create-system "age-test"
                {:hostname "127.0.0.1"
                 :port 0})]
      (try
        (is (ts/wait-for-cluster-up sys))
        (let [members (cluster/members-by-age sys)]
          ;; Should have one member in single-node cluster
          (is (= 1 (count members)))
          ;; Each member should have expected keys
          (let [member (first members)]
            (is (contains? member :address))
            (is (contains? member :status))
            (is (contains? member :upNumber))))
        (finally
          (ts/terminate-system sys))))))

(deftest state-snapshot-test
  (testing "Get cluster state snapshot"
    (let [sys (cluster/create-system "snapshot-test"
                {:hostname "127.0.0.1"
                 :port 0})]
      (try
        (is (ts/wait-for-cluster-up sys))
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
          (ts/terminate-system sys))))))

(deftest prepare-for-shutdown-test
  (testing "Coordinated cluster shutdown preparation"
    (let [sys (cluster/create-system "shutdown-test"
                {:hostname "127.0.0.1"
                 :port 0})
          shutdown-event (promise)]
      (try
        (is (ts/wait-for-cluster-up sys))
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
          (ts/terminate-system sys))))))
