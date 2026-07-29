(ns pekko-clj.timer-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pekko-clj.core :as core]
            [pekko-clj.test-support :refer [eventually]])
  (:import [scala.concurrent Await]
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
  (core/<! actor msg 3000))

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
    (is (eventually (>= @counter 3)))
    (core/! actor :stop)))

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
    (is (eventually (>= @counter 1))) ; let at least one tick fire
    (core/! actor :cancel)
    (let [count-at-cancel @counter]
      (Thread/sleep 100) ; stability window: confirm no further ticks
      ;; After cancelling, count should not have increased much (maybe 1 more due to timing)
      (is (<= @counter (+ count-at-cancel 1))))))

(deftest timer-active-returns-correct-status
  (let [actor (core/new-actor
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
    ;; :check-active is handled FIFO after :setup, so the timer is already active.
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
    (is (eventually (and (>= @counter1 1) (>= @counter2 1)))) ; let both fire
    (core/! actor :cancel-all)
    (let [c1 @counter1
          c2 @counter2]
      (Thread/sleep 100) ; stability window: confirm no further ticks
      ;; After cancelling all, counts should not increase
      (is (<= @counter1 (+ c1 1)))
      (is (<= @counter2 (+ c2 1))))))

;; ---------------------------------------------------------------------------
;; H14: timers and schedule-once accept a plain ms number, not only a Duration
;; ---------------------------------------------------------------------------

(deftest core-timers-accept-millis
  ;; start-timer / start-single-timer used to require a java.time.Duration and
  ;; threw ClassCastException on a number. They now take ms too.
  (let [single (promise)
        ticks (atom 0)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do
                                         (core/start-single-timer :single 50 :single-fired)
                                         (core/start-timer :periodic 20 :tick)
                                         nil)
                                :single-fired (do (deliver single true) nil)
                                :tick (do (swap! ticks inc) nil)
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (is (true? (deref single 2000 false)) "start-single-timer fired with ms")
    (is (eventually (>= @ticks 2)) "start-timer (ms) fired periodically")))

(deftest core-timer-initial-delay-accepts-millis
  ;; The 4-arg start-timer (initial-delay + interval), both as ms numbers.
  (let [ticks (atom 0)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do (core/start-timer :periodic 10 20 :tick) nil)
                                :tick (do (swap! ticks inc) nil)
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (is (eventually (>= @ticks 2)))))

(deftest schedule-once-accepts-millis
  ;; schedule-once runs the fn after a delay; ms number instead of a Duration.
  (let [fired (promise)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (when (= msg :setup)
                                (core/schedule-once 50 (fn [] (deliver fired true))))
                              nil))
                :state nil})]
    (core/! actor :setup)
    (is (true? (deref fired 2000 false)))))

;; ---------------------------------------------------------------------------
;; N21: fixed-delay timers
;; ---------------------------------------------------------------------------

(deftest fixed-delay-timer-fires-and-cancels
  ;; start-timer-fixed-delay fires periodically (both the 3-arg and 4-arg/
  ;; initial-delay arities) and cancel-timer stops it.
  (let [ticks (atom 0)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :setup (do (core/start-timer-fixed-delay :fd 20 :tick) nil)
                                :setup-initial (do (core/start-timer-fixed-delay :fd 10 20 :tick) nil)
                                :tick (do (swap! ticks inc) nil)
                                :cancel (do (core/cancel-timer :fd) nil)
                                nil)))
                :state nil})]
    (core/! actor :setup)
    (is (eventually (>= @ticks 3)) "fixed-delay timer fires periodically")
    (core/! actor :cancel)
    (let [c @ticks]
      (Thread/sleep 100)
      (is (<= @ticks (+ c 1)) "cancel stopped the fixed-delay timer"))
    ;; the initial-delay (4-arg) arity also fires
    (reset! ticks 0)
    (core/! actor :setup-initial)
    (is (eventually (>= @ticks 2)) "the initial-delay arity fires too")
    (core/! actor :cancel)))

(deftest fixed-delay-timer-replaces-on-same-key
  ;; Starting a fixed-delay timer with an existing key replaces it.
  (let [ticks (atom 0)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :fast (do (core/start-timer-fixed-delay :fd 20 :tick) nil)
                                :slow (do (core/start-timer-fixed-delay :fd 10000 :tick) nil)
                                :tick (do (swap! ticks inc) nil)
                                nil)))
                :state nil})]
    (core/! actor :fast)
    (is (eventually (>= @ticks 2)))
    (core/! actor :slow) ; replace the fast timer under :fd with a slow one
    (let [c @ticks]
      (Thread/sleep 200)
      (is (<= @ticks (+ c 1)) "the same-key restart replaced the fast timer"))))
