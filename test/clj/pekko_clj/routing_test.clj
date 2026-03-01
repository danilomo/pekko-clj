(ns pekko-clj.routing-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.routing :as routing])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.routing Routees]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def timeout-duration (Duration/create 5 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "routing-test")]
    (try
      (binding [*system* sys]
        (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each actor-system-fixture)

(defn await-ask
  "Send a message and block for the reply via core/<?>"
  [actor msg]
  (Await/result (core/<?> actor msg 3000) timeout-duration))

;; ---------------------------------------------------------------------------
;; Test actor definitions
;; ---------------------------------------------------------------------------

(core/defactor echo-worker
  "Simple worker that echoes messages back"
  (init [_] nil)
  (handle :ping
    (core/reply :pong))
  (handle [:echo msg]
    (core/reply msg)))

(core/defactor counting-worker
  "Worker that counts messages received"
  (init [_] {:count 0})
  (handle :inc
    (update state :count inc))
  (handle :get
    (core/reply (:count state))))

(def process-log (atom []))

(core/defactor logging-worker
  "Worker that logs which instance processed a message"
  (init [args]
    (let [id (or (:id args) (rand-int 10000))]
      {:id id}))
  (handle [:process data]
    (swap! process-log conj {:id (:id state) :data data})
    state)
  (handle :get-id
    (core/reply (:id state))))

;; ---------------------------------------------------------------------------
;; Tests: Pool Routers
;; ---------------------------------------------------------------------------

(deftest spawn-pool-creates-router
  (let [pool (routing/spawn-pool *system* echo-worker 3)]
    (is (instance? ActorRef pool))
    ;; Router responds to messages
    (is (= :pong (await-ask pool :ping)))))

(deftest pool-round-robin-distributes-messages
  (reset! process-log [])
  (let [pool (routing/spawn-pool *system* logging-worker 3 {:strategy :round-robin})]
    ;; Send 6 messages - should hit each of 3 workers twice
    (dotimes [i 6]
      (core/! pool [:process i]))
    (Thread/sleep 300)
    ;; All messages processed
    (is (= 6 (count @process-log)))
    ;; Messages distributed to multiple workers
    (let [worker-ids (set (map :id @process-log))]
      (is (= 3 (count worker-ids))))))

(deftest pool-random-strategy
  (reset! process-log [])
  (let [pool (routing/spawn-pool *system* logging-worker 3 {:strategy :random})]
    ;; Send several messages
    (dotimes [i 10]
      (core/! pool [:process i]))
    (Thread/sleep 300)
    ;; All messages processed
    (is (= 10 (count @process-log)))))

(deftest pool-broadcast-sends-to-all
  (reset! process-log [])
  (let [pool (routing/spawn-pool *system* logging-worker 3 {:strategy :broadcast})]
    ;; Send one message - should go to all 3 workers
    (core/! pool [:process :hello])
    (Thread/sleep 200)
    ;; Message received by all workers
    (is (= 3 (count @process-log)))
    (is (every? #(= :hello (:data %)) @process-log))))

(deftest pool-smallest-mailbox-strategy
  (let [pool (routing/spawn-pool *system* echo-worker 3 {:strategy :smallest-mailbox})]
    ;; Should work without error
    (is (= :pong (await-ask pool :ping)))))

(deftest pool-with-args
  (reset! process-log [])
  (let [pool (routing/spawn-pool *system* logging-worker 2
                                  {:strategy :round-robin :args {:id 999}})]
    ;; All workers should have the same ID from args
    (core/! pool [:process :test])
    (core/! pool [:process :test])
    (Thread/sleep 200)
    ;; Both workers have ID 999
    (is (every? #(= 999 (:id %)) @process-log))))

;; ---------------------------------------------------------------------------
;; Tests: Group Routers
;; ---------------------------------------------------------------------------

(deftest spawn-group-routes-to-existing-actors
  ;; Create individual actors first
  (let [w1 (core/spawn *system* echo-worker nil)
        w2 (core/spawn *system* echo-worker nil)
        ;; Get their paths
        path1 (.path w1)
        path2 (.path w2)
        ;; Create group router
        group (routing/spawn-group *system*
                                   [(.toString path1) (.toString path2)])]
    ;; Router should work
    (is (= :pong (await-ask group :ping)))))

(deftest group-broadcast-strategy
  (reset! process-log [])
  ;; Create individual logging workers
  (let [w1 (core/spawn *system* logging-worker {:id 1})
        w2 (core/spawn *system* logging-worker {:id 2})
        w3 (core/spawn *system* logging-worker {:id 3})
        paths [(str (.path w1)) (str (.path w2)) (str (.path w3))]
        group (routing/spawn-group *system* paths {:strategy :broadcast})]
    ;; Send one message
    (core/! group [:process :broadcast-test])
    (Thread/sleep 200)
    ;; All three workers received it
    (is (= 3 (count @process-log)))
    (is (= #{1 2 3} (set (map :id @process-log))))))

;; ---------------------------------------------------------------------------
;; Tests: Broadcast helper
;; ---------------------------------------------------------------------------

(deftest broadcast-to-round-robin-pool
  (reset! process-log [])
  (let [pool (routing/spawn-pool *system* logging-worker 3 {:strategy :round-robin})]
    ;; Use broadcast helper to send to all, even though pool is round-robin
    (routing/broadcast pool [:process :to-all])
    (Thread/sleep 200)
    ;; All 3 workers received it
    (is (= 3 (count @process-log)))))

;; ---------------------------------------------------------------------------
;; Tests: Get Routees
;; ---------------------------------------------------------------------------

(deftest get-routees-returns-routee-info
  (let [pool (routing/spawn-pool *system* echo-worker 3)
        future (routing/get-routees pool)
        routees (Await/result future timeout-duration)]
    (is (instance? Routees routees))
    ;; Should have 3 routees
    (is (= 3 (.size (.getRoutees routees))))))

;; ---------------------------------------------------------------------------
;; Tests: Balancing Pool
;; ---------------------------------------------------------------------------

(deftest balancing-pool-processes-messages
  (reset! process-log [])
  (let [pool (routing/spawn-pool *system* logging-worker 3 {:strategy :balancing})]
    ;; Send several messages
    (dotimes [i 6]
      (core/! pool [:process i]))
    (Thread/sleep 500)
    ;; All messages processed
    (is (= 6 (count @process-log)))))

;; ---------------------------------------------------------------------------
;; Tests: Consistent Hashing
;; ---------------------------------------------------------------------------

(def hash-log (atom {}))

(core/defactor hash-tracking-worker
  "Worker that tracks which messages it received"
  (init [args]
    {:id (or (:id args) (rand-int 100000))})
  (handle [:hash-msg key data]
    (swap! hash-log update key (fnil conj []) {:id (:id state) :data data})
    state)
  (handle :get-id
    (core/reply (:id state))))

(deftest consistent-hashing-pool-routes-same-key-to-same-routee
  (reset! hash-log {})
  (let [pool (routing/spawn-consistent-hash-pool *system* hash-tracking-worker 5
               {:hash-fn (fn [[_ key _]] (str key))})]  ; Convert to string for serialization
    ;; Send messages with same key - should go to same routee
    (dotimes [i 5]
      (core/! pool [:hash-msg "user-123" i]))
    ;; Send messages with different key - may go to different routee
    (dotimes [i 5]
      (core/! pool [:hash-msg "user-456" i]))
    (Thread/sleep 500)
    ;; All messages for same key went to same worker
    (let [user123-workers (set (map :id (get @hash-log "user-123")))
          user456-workers (set (map :id (get @hash-log "user-456")))]
      (is (= 1 (count user123-workers)) "Same key should route to same worker")
      (is (= 1 (count user456-workers)) "Same key should route to same worker"))))

(deftest consistent-hashing-group-routes-same-key-to-same-routee
  (reset! hash-log {})
  ;; Create individual workers
  (let [w1 (core/spawn *system* hash-tracking-worker {:id 1})
        w2 (core/spawn *system* hash-tracking-worker {:id 2})
        w3 (core/spawn *system* hash-tracking-worker {:id 3})
        paths [(str (.path w1)) (str (.path w2)) (str (.path w3))]
        group (routing/spawn-consistent-hash-group *system* paths
                {:hash-fn (fn [[_ key _]] (str key))})]  ; Convert to string for serialization
    ;; Send messages with same key
    (dotimes [i 5]
      (core/! group [:hash-msg "session-abc" i]))
    (Thread/sleep 500)
    ;; All messages went to same worker
    (let [workers (set (map :id (get @hash-log "session-abc")))]
      (is (= 1 (count workers)) "Same key should route to same worker"))))

;; ---------------------------------------------------------------------------
;; Tests: Scatter-Gather Pool
;; ---------------------------------------------------------------------------

(core/defactor delayed-echo-worker
  "Worker that echoes with a delay"
  (init [args]
    {:delay-ms (or (:delay-ms args) 0)})
  (handle [:delayed-echo msg]
    (Thread/sleep (:delay-ms state))
    (core/reply msg)))

(deftest scatter-gather-pool-returns-first-response
  (let [pool (routing/spawn-scatter-gather-pool *system* delayed-echo-worker 3
               {:timeout-ms 5000
                :args {:delay-ms 0}})]
    ;; Should get response from first responder
    (let [result (await-ask pool [:delayed-echo :test-msg])]
      (is (= :test-msg result)))))

;; ---------------------------------------------------------------------------
;; Tests: Tail-Chopping Pool
;; ---------------------------------------------------------------------------

(deftest tail-chopping-pool-returns-response
  (let [pool (routing/spawn-tail-chopping-pool *system* echo-worker 3
               {:timeout-ms 5000
                :interval-ms 100})]
    ;; Should get response
    (is (= :pong (await-ask pool :ping)))))

;; ---------------------------------------------------------------------------
;; Tests: Pool with Resizer
;; ---------------------------------------------------------------------------

(deftest pool-with-resizer-starts
  (let [pool (routing/spawn-pool-with-resizer *system* echo-worker
               {:min-size 2
                :max-size 5
                :strategy :round-robin})]
    ;; Pool should respond
    (is (= :pong (await-ask pool :ping)))
    ;; Should have at least min-size routees
    (let [routees (Await/result (routing/get-routees pool) timeout-duration)]
      (is (>= (.size (.getRoutees routees)) 2)))))

;; ---------------------------------------------------------------------------
;; Tests: Dynamic Routee Management
;; ---------------------------------------------------------------------------

(deftest adjust-pool-size-changes-routees
  (let [pool (routing/spawn-pool *system* echo-worker 3)]
    ;; Initial check
    (let [routees (Await/result (routing/get-routees pool) timeout-duration)]
      (is (= 3 (.size (.getRoutees routees)))))
    ;; Add 2 routees
    (routing/adjust-pool-size pool 2)
    (Thread/sleep 500)
    (let [routees (Await/result (routing/get-routees pool) timeout-duration)]
      (is (= 5 (.size (.getRoutees routees)))))
    ;; Remove 1 routee
    (routing/adjust-pool-size pool -1)
    (Thread/sleep 500)
    (let [routees (Await/result (routing/get-routees pool) timeout-duration)]
      (is (= 4 (.size (.getRoutees routees)))))))
