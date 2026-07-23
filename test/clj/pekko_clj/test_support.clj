(ns pekko-clj.test-support
  "Shared helpers for pekko-clj's own tests:
   - one cluster-system creation + cluster-up wait + termination (deduped from the
     cluster/singleton/sharding suites),
   - `eventually`/`poll-until` to replace flaky fixed `Thread/sleep` synchronization
     with a bounded poll that returns as soon as the condition holds."
  (:require [pekko-clj.cluster :as cluster]
            [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

;; ---------------------------------------------------------------------------
;; Cluster system lifecycle
;; ---------------------------------------------------------------------------

(defn create-cluster-system
  "Create a single-node cluster ActorSystem from test/resources/cluster-test.conf
   (random port, WARNING log level, ddata sharding store)."
  [name]
  (ActorSystem/create name (ConfigFactory/load "cluster-test.conf")))

(defn wait-for-cluster-up
  "Self-join and poll until this node is Up in the cluster. Returns true, or false
   on timeout."
  ([sys] (wait-for-cluster-up sys 10000))
  ([sys timeout-ms]
   (let [c (cluster/cluster sys)]
     (.join c (.selfAddress c))
     (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
       (loop []
         (cond
           (= "Up" (str (.status (cluster/self-member sys)))) true
           (> (System/currentTimeMillis) deadline) false
           :else (do (Thread/sleep 100) (recur))))))))

(defn terminate-system
  "Terminate an ActorSystem and block until it is fully stopped."
  [sys]
  (.terminate sys)
  (Await/result (.whenTerminated sys) (Duration/create 15 "seconds")))

;; ---------------------------------------------------------------------------
;; Polling assertions (replace fixed Thread/sleep synchronization)
;; ---------------------------------------------------------------------------

(defn poll-until
  "Call thunk `f` every `interval-ms` until it returns a truthy value or
   `timeout-ms` elapses; a thrown exception counts as 'not yet'. Returns f's
   truthy value, or nil on timeout."
  ([f] (poll-until f 8000 50))
  ([f timeout-ms] (poll-until f timeout-ms 50))
  ([f timeout-ms interval-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (let [v (try (f) (catch Throwable _ nil))]
         (or v
             (when (< (System/currentTimeMillis) deadline)
               (Thread/sleep interval-ms)
               (recur))))))))

(defmacro eventually
  "Evaluate `body` repeatedly until it is truthy (exceptions count as 'not yet')
   or the timeout elapses; returns the last truthy value or nil. Use in place of a
   fixed `(Thread/sleep n)` before an assertion:

     (is (eventually (= 3 (get-count actor))))"
  ([body] `(poll-until (fn [] ~body) 8000))
  ([timeout-ms body] `(poll-until (fn [] ~body) ~timeout-ms)))

;; ---------------------------------------------------------------------------
;; Lifecycle observation
;; ---------------------------------------------------------------------------

(defn stopped-within?
  "True if `target` (an ActorRef) terminates within timeout-ms. Spawns a
   throwaway watcher actor to observe it via DeathWatch — for confirming an
   internally-spawned subscriber actor was actually stopped, not just
   unsubscribed."
  ([system target] (stopped-within? system target 3000))
  ([system target timeout-ms]
   (let [terminated (promise)
         watcher (core/new-actor
                  system
                  {:function (fn [this msg]
                               (binding [core/*current-actor* this]
                                 (when (and (vector? msg) (= :terminated (first msg)))
                                   (deliver terminated true)))
                               nil)
                   :pre-start (fn [this]
                                (binding [core/*current-actor* this]
                                  (core/watch target))
                                nil)
                   :state nil})]
     (try
       (boolean (deref terminated timeout-ms false))
       (finally
         (core/poison-pill watcher))))))
