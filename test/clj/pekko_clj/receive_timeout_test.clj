(ns pekko-clj.receive-timeout-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.test-support :as ts :refer [eventually]]))

(def timeouts (atom 0))

(core/defactor timeout-actor
  "Sets a receive timeout on start and counts ReceiveTimeout messages."
  (init [args]
    (core/set-receive-timeout (:timeout args))
    {})
  (handle :ping (core/reply :pong) state)
  (handle msg
    (when (core/receive-timeout? msg) (swap! timeouts inc))
    state))

(def cancel-fired (atom 0))

(core/defactor cancel-timeout-actor
  "Sets a receive timeout, then cancels it when told to."
  (init [args]
    (core/set-receive-timeout (:timeout args))
    {})
  (handle :cancel (core/cancel-receive-timeout) state)
  (handle msg
    (when (core/receive-timeout? msg) (swap! cancel-fired inc))
    state))

(deftest receive-timeout-fires-when-idle-test
  (let [sys (core/actor-system "rt-fires")]
    (try
      (reset! timeouts 0)
      ;; No messages are sent, so the actor idles into a ReceiveTimeout.
      (core/spawn sys timeout-actor {:timeout 200})
      (is (eventually (pos? @timeouts)))
      (finally (core/shutdown-system sys)))))

(deftest receive-timeout-accepts-duration-test
  (let [sys (core/actor-system "rt-duration")]
    (try
      (reset! timeouts 0)
      (core/spawn sys timeout-actor {:timeout (java.time.Duration/ofMillis 200)})
      (is (eventually (pos? @timeouts)))
      (finally (core/shutdown-system sys)))))

(deftest cancel-receive-timeout-test
  (let [sys (core/actor-system "rt-cancel")]
    (try
      (reset! cancel-fired 0)
      (let [a (core/spawn sys cancel-timeout-actor {:timeout 250})]
        ;; Cancel before the timeout can fire; it must then stay silent.
        (core/! a :cancel)
        (Thread/sleep 700)
        (is (zero? @cancel-fired)))
      (finally (core/shutdown-system sys)))))
