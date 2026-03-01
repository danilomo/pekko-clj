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
