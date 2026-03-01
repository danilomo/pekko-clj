(ns pekko-clj.timer-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def timeout-duration (Duration/create 5 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "timer-test")]
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
;; Tests: Timers
;; ---------------------------------------------------------------------------

(deftest start-single-timer-sends-message
  (let [received (promise)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do
                                         (core/start-single-timer :test-timer
                                                                  (java.time.Duration/ofMillis 50)
                                                                  :timer-fired)
                                         nil)
                                :timer-fired (do
                                               (deliver received true)
                                               nil)
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (is (true? (deref received 2000 false)))))

(deftest start-timer-periodic-sends-multiple
  (let [counter (atom 0)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do
                                         (core/start-timer :counter-timer
                                                           (java.time.Duration/ofMillis 30)
                                                           :tick)
                                         nil)
                                :tick (do
                                        (swap! counter inc)
                                        nil)
                                :stop (do
                                        (core/cancel-timer :counter-timer)
                                        nil)
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (Thread/sleep 200)
    (core/! actor :stop)
    (is (>= @counter 3))))

(deftest cancel-timer-stops-messages
  (let [counter (atom 0)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do
                                         (core/start-timer :cancel-test-timer
                                                           (java.time.Duration/ofMillis 20)
                                                           :tick)
                                         nil)
                                :tick (do
                                        (swap! counter inc)
                                        nil)
                                :cancel (do
                                          (core/cancel-timer :cancel-test-timer)
                                          nil)
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (Thread/sleep 100)
    (core/! actor :cancel)
    (let [count-at-cancel @counter]
      (Thread/sleep 100)
      ;; After cancelling, count should not have increased much (maybe 1 more due to timing)
      (is (<= @counter (+ count-at-cancel 1))))))

(deftest timer-active-returns-correct-status
  (let [result (promise)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do
                                         (core/start-timer :active-check-timer
                                                           (java.time.Duration/ofSeconds 10)
                                                           :tick)
                                         nil)
                                :check-active (do
                                                (.reply this (core/timer-active? :active-check-timer))
                                                nil)
                                :check-inactive (do
                                                  (.reply this (core/timer-active? :nonexistent))
                                                  nil)
                                :tick nil
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (Thread/sleep 50)
    (is (true? (await-ask actor :check-active)))
    (is (false? (await-ask actor :check-inactive)))))

(deftest cancel-all-timers-stops-everything
  (let [counter1 (atom 0)
        counter2 (atom 0)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do
                                         (core/start-timer :timer1
                                                           (java.time.Duration/ofMillis 20)
                                                           :tick1)
                                         (core/start-timer :timer2
                                                           (java.time.Duration/ofMillis 20)
                                                           :tick2)
                                         nil)
                                :tick1 (do (swap! counter1 inc) nil)
                                :tick2 (do (swap! counter2 inc) nil)
                                :cancel-all (do
                                              (core/cancel-all-timers)
                                              nil)
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (Thread/sleep 100)
    (core/! actor :cancel-all)
    (let [c1 @counter1
          c2 @counter2]
      (Thread/sleep 100)
      ;; After cancelling all, counts should not increase
      (is (<= @counter1 (+ c1 1)))
      (is (<= @counter2 (+ c2 1))))))
