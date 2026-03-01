(ns pekko-clj.supervision-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.supervision :as sup])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.actor SupervisorStrategy
            OneForOneStrategy AllForOneStrategy]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def timeout-duration (Duration/create 5 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "supervision-test")]
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
;; Tests: Supervision Strategy Creation
;; ---------------------------------------------------------------------------

(deftest one-for-one-creates-strategy
  (let [strategy (sup/one-for-one (fn [_] :restart))]
    (is (instance? OneForOneStrategy strategy))))

(deftest one-for-one-with-options
  (let [strategy (sup/one-for-one {:max-retries 10 :within-ms 60000}
                                  (fn [_] :restart))]
    (is (instance? OneForOneStrategy strategy))))

(deftest all-for-one-creates-strategy
  (let [strategy (sup/all-for-one (fn [_] :restart))]
    (is (instance? AllForOneStrategy strategy))))

(deftest all-for-one-with-options
  (let [strategy (sup/all-for-one {:max-retries 5 :within-ms 30000}
                                  (fn [_] :restart))]
    (is (instance? AllForOneStrategy strategy))))

(deftest default-strategy-returns-strategy
  (let [strategy (sup/default-strategy)]
    (is (instance? SupervisorStrategy strategy))))

;; ---------------------------------------------------------------------------
;; Tests: Supervision with Actors
;; ---------------------------------------------------------------------------

(deftest child-actor-restarts-on-exception
  (let [restart-count (atom 0)
        child-started (promise)
        ;; Child actor that throws on :fail and tracks restarts
        child-def {:make-props (fn [_]
                                 {:function (fn [this msg]
                                              (case msg
                                                :fail (throw (RuntimeException. "intentional"))
                                                :ping (do (.reply this :pong) nil)
                                                nil))
                                  :pre-start (fn [_]
                                               (swap! restart-count inc)
                                               (when (> @restart-count 1)
                                                 (deliver child-started true))
                                               nil)
                                  :state nil})}
        ;; Parent with restart supervision
        parent-def {:make-props (fn [_]
                                  {:function (fn [this msg]
                                               (binding [core/*current-actor* this]
                                                 (case msg
                                                   :spawn-child
                                                   (let [child (core/spawn child-def nil)]
                                                     (.reply this child)
                                                     nil)
                                                   nil)))
                                   :supervisor-strategy (sup/one-for-one
                                                          {:max-retries 3 :within-ms 60000}
                                                          (fn [_] :restart))
                                   :state nil})}
        parent (core/spawn *system* parent-def nil)
        child (await-ask parent :spawn-child)]
    ;; Verify child is working
    (is (= :pong (await-ask child :ping)))
    ;; Initial start count should be 1
    (is (= 1 @restart-count))
    ;; Trigger failure
    (core/! child :fail)
    ;; Wait for restart
    (is (true? (deref child-started 3000 false)))
    ;; Restart count should be 2
    (is (= 2 @restart-count))
    ;; Child should still work after restart
    (Thread/sleep 100)
    (is (= :pong (await-ask child :ping)))))

(deftest child-actor-stops-on-exception-with-stop-directive
  (let [stopped (promise)
        ;; Child that throws
        child-def {:make-props (fn [_]
                                 {:function (fn [this msg]
                                              (case msg
                                                :fail (throw (RuntimeException. "stop me"))
                                                :ping (do (.reply this :pong) nil)
                                                nil))
                                  :state nil})}
        ;; Parent with stop supervision
        parent-def {:make-props (fn [_]
                                  {:function (fn [this msg]
                                               (binding [core/*current-actor* this]
                                                 (cond
                                                   (= msg :spawn-child)
                                                   (let [child (core/spawn child-def nil)]
                                                     (core/watch child)
                                                     (.reply this child)
                                                     nil)

                                                   (and (vector? msg) (= :terminated (first msg)))
                                                   (do
                                                     (deliver stopped (second msg))
                                                     nil)

                                                   :else nil)))
                                   :supervisor-strategy (sup/one-for-one (fn [_] :stop))
                                   :state nil})}
        parent (core/spawn *system* parent-def nil)
        child (await-ask parent :spawn-child)]
    ;; Child works initially
    (is (= :pong (await-ask child :ping)))
    ;; Trigger failure
    (core/! child :fail)
    ;; Wait for termination
    (let [stopped-ref (deref stopped 3000 :timeout)]
      (is (not= :timeout stopped-ref))
      (is (= child stopped-ref)))))

(deftest supervision-with-defactor
  (let [restart-count (atom 0)
        restarted (promise)]
    ;; Define child using defactor
    (core/defactor failing-child
      (init [_]
        (swap! restart-count inc)
        (when (> @restart-count 1)
          (deliver restarted true))
        nil)
      (handle :fail
        (throw (RuntimeException. "boom")))
      (handle :ping
        (core/reply :pong)))

    ;; Define parent with supervision using defactor
    (core/defactor supervising-parent
      (supervision (sup/one-for-one {:max-retries 5} (fn [_] :restart)))
      (init [_] {:child nil})
      (handle :spawn
        (let [child (core/spawn failing-child nil)]
          (core/reply child)
          {:child child}))
      (handle :ping
        (core/reply :parent-pong)))

    (let [parent (core/spawn *system* supervising-parent nil)
          child (await-ask parent :spawn)]
      ;; Initial start
      (is (= 1 @restart-count))
      ;; Verify child works
      (is (= :pong (await-ask child :ping)))
      ;; Trigger failure
      (core/! child :fail)
      ;; Wait for restart
      (is (true? (deref restarted 3000 false)))
      (is (= 2 @restart-count))
      ;; Child still works
      (Thread/sleep 100)
      (is (= :pong (await-ask child :ping))))))

(deftest decider-receives-exception
  (let [received-exception (promise)
        child-def {:make-props (fn [_]
                                 {:function (fn [_ msg]
                                              (case msg
                                                :fail-arith (throw (ArithmeticException. "div by zero"))
                                                :fail-null (throw (NullPointerException. "null"))
                                                nil))
                                  :state nil})}
        parent-def {:make-props (fn [_]
                                  {:function (fn [this msg]
                                               (binding [core/*current-actor* this]
                                                 (case msg
                                                   :spawn (do
                                                            (.reply this (core/spawn child-def nil))
                                                            nil)
                                                   nil)))
                                   :supervisor-strategy
                                   (sup/one-for-one
                                     (fn [ex]
                                       (deliver received-exception ex)
                                       :restart))
                                   :state nil})}
        parent (core/spawn *system* parent-def nil)
        child (await-ask parent :spawn)]
    ;; Trigger arithmetic exception
    (core/! child :fail-arith)
    ;; Check the decider received the right exception
    (let [ex (deref received-exception 2000 :timeout)]
      (is (not= :timeout ex))
      (is (instance? ArithmeticException ex)))))

;; ---------------------------------------------------------------------------
;; Tests: Simple Deciders
;; ---------------------------------------------------------------------------

(deftest restart-decider-returns-restart
  (is (= :restart (sup/restart-decider (Exception. "test")))))

(deftest resume-decider-returns-resume
  (is (= :resume (sup/resume-decider (Exception. "test")))))

(deftest stop-decider-returns-stop
  (is (= :stop (sup/stop-decider (Exception. "test")))))

(deftest escalate-decider-returns-escalate
  (is (= :escalate (sup/escalate-decider (Exception. "test")))))
