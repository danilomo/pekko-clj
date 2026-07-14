(ns pekko-clj.testkit-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.test :as t]
            [pekko-clj.stream :as s])
  (:import [org.apache.pekko.stream.javadsl Keep]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def ^:dynamic *system* nil)

(defn system-fixture [f]
  (let [sys (core/actor-system "testkit-test")]
    (try
      (binding [*system* sys] (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each system-fixture)

;; ---------------------------------------------------------------------------
;; Test actors
;; ---------------------------------------------------------------------------

(core/defactor echo-actor
  (handle msg
    (core/reply msg)))

(core/defactor counter-actor
  (init [_] {:count 0})
  (handle :inc (update state :count inc))
  (handle :get (core/reply (:count state))))

;; ---------------------------------------------------------------------------
;; Tests: TestProbe wrappers
;; ---------------------------------------------------------------------------

(deftest probe-expect-msg
  (let [p (t/probe *system*)
        echo (core/spawn *system* echo-actor)]
    (t/send-to p echo :hello)
    (is (= :hello (t/expect-msg p :hello)))))

(deftest probe-expect-msg-type
  (let [p (t/probe *system*)
        echo (core/spawn *system* echo-actor)]
    (t/send-to p echo "a-string")
    (is (= "a-string" (t/expect-msg-type p String)))))

(deftest probe-expect-no-message
  (let [p (t/probe *system*)]
    (is (nil? (t/expect-no-message p (t/millis 200))))))

(deftest probe-receive-n
  (let [p (t/probe *system*)
        echo (core/spawn *system* echo-actor)]
    (t/send-to p echo 1)
    (t/send-to p echo 2)
    (t/send-to p echo 3)
    (is (= [1 2 3] (t/receive-n p 3)))))

(deftest probe-await-assert
  (let [state (atom 0)
        p (t/probe *system*)]
    (future (Thread/sleep 300) (reset! state 42))
    (t/await-assert p (t/seconds 3)
                    #(when (not= 42 @state)
                       (throw (AssertionError. "not 42 yet"))))
    (is (= 42 @state))))

(deftest probe-within
  (let [p (t/probe *system*)
        echo (core/spawn *system* echo-actor)]
    (t/send-to p echo :quick)
    (is (= :quick (t/within p (t/seconds 3) #(t/expect-msg p :quick))))))

(deftest probe-fish-for-message
  (let [p (t/probe *system*)
        echo (core/spawn *system* echo-actor)]
    (t/send-to p echo :a)
    (t/send-to p echo :b)
    (t/send-to p echo :target)
    (is (= :target (t/fish-for-message p (t/seconds 3) #(= % :target))))))

(deftest probe-watch-expect-terminated
  (let [p (t/probe *system*)
        target (core/spawn *system* echo-actor)]
    (t/watch p target)
    (.tell target (org.apache.pekko.actor.PoisonPill/getInstance)
           (org.apache.pekko.actor.ActorRef/noSender))
    (is (some? (t/expect-terminated p (t/seconds 3) target)))))

;; ---------------------------------------------------------------------------
;; Tests: synchronous TestActorRef
;; ---------------------------------------------------------------------------

(deftest test-actor-ref-processes-synchronously
  (let [ref (t/test-actor-ref *system* counter-actor)]
    ;; CallingThreadDispatcher: each tell is fully processed before it returns.
    (core/! ref :inc)
    (core/! ref :inc)
    (core/! ref :inc)
    (is (= 3 (:count @(t/underlying ref))))))

;; ---------------------------------------------------------------------------
;; Tests: stream probes
;; ---------------------------------------------------------------------------

(deftest test-sink-asserts-elements
  (let [mat (s/materializer *system*)
        probe (s/run-with (s/source [1 2 3]) (t/test-sink *system*) mat)]
    (.request probe 3)
    (.expectNext probe 1)
    (.expectNext probe 2)
    (.expectNext probe 3)
    (.expectComplete probe)
    (is true)))

(deftest test-source-and-sink-probes
  (let [mat (s/materializer *system*)
        pair (-> (t/test-source *system*)
                 (.toMat (t/test-sink *system*) (Keep/both))
                 (.run mat))
        src-probe (.first pair)
        sink-probe (.second pair)]
    (.request sink-probe 2)
    (.sendNext src-probe 10)
    (.expectNext sink-probe 10)
    (.sendNext src-probe 20)
    (.expectNext sink-probe 20)
    (.sendComplete src-probe)
    (.expectComplete sink-probe)
    (is true)))
