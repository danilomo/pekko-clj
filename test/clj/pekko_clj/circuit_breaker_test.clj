(ns pekko-clj.circuit-breaker-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.circuit-breaker :as cb]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.pattern CircuitBreaker CircuitBreakerOpenException]
           [java.util.concurrent CompletableFuture]))

(defn- boom [] (throw (RuntimeException. "boom")))

(deftest circuit-breaker-create-and-success-test
  (let [sys (core/actor-system "cb-ok")]
    (try
      (let [b (cb/circuit-breaker sys {:max-failures 3})]
        (is (instance? CircuitBreaker b))
        (is (cb/closed? b))
        (is (false? (cb/open? b)))
        (is (= 42 (cb/call b (fn [] 42))))
        (is (cb/closed? b)))
      (finally (core/shutdown-system sys)))))

(deftest circuit-breaker-opens-after-failures-test
  (let [sys (core/actor-system "cb-open")]
    (try
      (let [b (cb/circuit-breaker sys {:max-failures 2
                                       :call-timeout 1000
                                       :reset-timeout 60000})]
        (dotimes [_ 2]
          (is (thrown? RuntimeException (cb/call b boom))))
        ;; Having hit max-failures, the breaker trips open and fails fast.
        (is (eventually (cb/open? b)))
        (is (thrown? CircuitBreakerOpenException
              (cb/call b (fn [] :never-runs)))))
      (finally (core/shutdown-system sys)))))

(deftest circuit-breaker-async-test
  (let [sys (core/actor-system "cb-async")]
    (try
      (let [b (cb/circuit-breaker sys {:max-failures 3})
            result (cb/call-async b (fn [] (CompletableFuture/completedFuture 7)))]
        (is (= 7 (deref result 5000 nil))))
      (finally (core/shutdown-system sys)))))

(deftest circuit-breaker-on-open-listener-test
  (let [sys (core/actor-system "cb-listener")]
    (try
      (let [b (cb/circuit-breaker sys {:max-failures 1 :reset-timeout 60000})
            opened (atom false)]
        (is (identical? b (cb/on-open b (fn [] (reset! opened true)))))
        (is (thrown? RuntimeException (cb/call b boom)))
        (is (eventually @opened)))
      (finally (core/shutdown-system sys)))))

(deftest circuit-breaker-manual-succeed-fail-test
  (let [sys (core/actor-system "cb-manual")]
    (try
      (let [b (cb/circuit-breaker sys {:max-failures 2 :reset-timeout 60000})]
        (is (nil? (cb/fail b)))
        (is (nil? (cb/fail b)))
        (is (eventually (cb/open? b))))
      (finally (core/shutdown-system sys)))))
