(ns pekko-clj.become-result-test
  (:require [clojure.test :refer :all])
  (:import [pekko_clj.actor BecomeResult]))

(deftest of-creates-instance-with-function-and-state
  (let [func (fn [_ _] nil)
        state {:count 42}
        result (BecomeResult/of func state)]
    (is (instance? BecomeResult result))
    (is (= func (.function result)))
    (is (= state (.state result)))))

(deftest of-preserves-nil-state
  (let [func (fn [_ _] nil)
        result (BecomeResult/of func nil)]
    (is (nil? (.state result)))
    (is (= func (.function result)))))

(deftest of-preserves-various-state-types
  (testing "string state"
    (let [result (BecomeResult/of (fn [_ _] nil) "hello")]
      (is (= "hello" (.state result)))))
  (testing "numeric state"
    (let [result (BecomeResult/of (fn [_ _] nil) 42)]
      (is (= 42 (.state result)))))
  (testing "vector state"
    (let [result (BecomeResult/of (fn [_ _] nil) [1 2 3])]
      (is (= [1 2 3] (.state result)))))
  (testing "map state"
    (let [result (BecomeResult/of (fn [_ _] nil) {:a 1 :b 2})]
      (is (= {:a 1 :b 2} (.state result))))))
