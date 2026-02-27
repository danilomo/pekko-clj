(ns pekko-clj.defactor-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core :refer [defactor]])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [pekko_clj.actor BecomeResult CljActor]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(def timeout-duration (Duration/create 5 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "defactor-test")]
    (try
      (binding [*system* sys]
        (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each actor-system-fixture)

;; ---------------------------------------------------------------------------
;; Test actors defined with defactor
;; ---------------------------------------------------------------------------

(defactor test-counter
  "A counter for testing."

  (init [args]
    {:count (or (:start args) 0)})

  (handle :inc
    (update state :count inc))

  (handle :dec
    (update state :count dec))

  (handle [:add n]
    (update state :count + n))

  (handle :get
    (core/reply (:count state)))

  (handle :reset
    {:count 0}))

(defactor test-echo
  (handle msg
    (core/reply msg)))

(defactor test-stateless
  "Actor without init clause."
  (handle :ping
    (core/reply :pong)))

(declare test-sad)

(defactor test-happy
  (handle :mood
    (core/reply "happy")
    state)

  (handle :toggle
    (core/become test-sad state)))

(defactor test-sad
  (handle :mood
    (core/reply "sad")
    state)

  (handle :toggle
    (core/become test-happy state)))

(defactor test-child
  (handle :get-value
    (core/reply (:value state))))

(defactor test-parent-spawner
  (init [_]
    {:child (core/spawn test-child {:value 42})})

  (handle :ask-child
    (core/forward (:child state) :get-value)
    state)

  (handle :get-child
    (core/reply (:child state))))

(defactor test-manager
  (handle msg
    (core/reply (.toUpperCase msg))))

(defactor test-guardian
  (init [_]
    {:manager (core/spawn test-manager)})

  (handle msg
    (core/forward (:manager state) msg)
    state))

(defactor test-context-info
  (handle :get-self
    (core/reply (core/self)))

  (handle :get-parent
    (core/reply (core/parent)))

  (handle :get-sender
    (core/reply (core/sender))))

;; ---------------------------------------------------------------------------
;; Tests: defactor counter
;; ---------------------------------------------------------------------------

(deftest counter-init-with-args
  (let [c (core/spawn *system* test-counter {:start 10})]
    (is (= 10 (core/<! *system* c :get)))))

(deftest counter-init-default
  (let [c (core/spawn *system* test-counter {})]
    (is (= 0 (core/<! *system* c :get)))))

(deftest counter-increment
  (let [c (core/spawn *system* test-counter {:start 0})]
    (core/! c :inc)
    (core/! c :inc)
    (core/! c :inc)
    (is (= 3 (core/<! *system* c :get)))))

(deftest counter-decrement
  (let [c (core/spawn *system* test-counter {:start 5})]
    (core/! c :dec)
    (core/! c :dec)
    (is (= 3 (core/<! *system* c :get)))))

(deftest counter-add
  (let [c (core/spawn *system* test-counter {:start 0})]
    (core/! c [:add 10])
    (core/! c [:add 5])
    (is (= 15 (core/<! *system* c :get)))))

(deftest counter-reset
  (let [c (core/spawn *system* test-counter {:start 42})]
    (core/! c :reset)
    (is (= 0 (core/<! *system* c :get)))))

(deftest counter-mixed-operations
  (let [c (core/spawn *system* test-counter {:start 0})]
    (core/! c :inc)
    (core/! c :inc)
    (core/! c [:add 10])
    (core/! c :dec)
    (is (= 11 (core/<! *system* c :get)))
    (core/! c :reset)
    (is (= 0 (core/<! *system* c :get)))))

;; ---------------------------------------------------------------------------
;; Tests: echo (catch-all pattern)
;; ---------------------------------------------------------------------------

(deftest echo-keyword
  (let [a (core/spawn *system* test-echo)]
    (is (= :hello (core/<! *system* a :hello)))))

(deftest echo-string
  (let [a (core/spawn *system* test-echo)]
    (is (= "world" (core/<! *system* a "world")))))

(deftest echo-vector
  (let [a (core/spawn *system* test-echo)]
    (is (= [1 2 3] (core/<! *system* a [1 2 3])))))

;; ---------------------------------------------------------------------------
;; Tests: stateless actor (no init clause)
;; ---------------------------------------------------------------------------

(deftest stateless-actor-responds
  (let [a (core/spawn *system* test-stateless)]
    (is (= :pong (core/<! *system* a :ping)))))

;; ---------------------------------------------------------------------------
;; Tests: become (behavior switching)
;; ---------------------------------------------------------------------------

(deftest become-switches-behavior
  (let [a (core/spawn *system* test-happy nil)]
    (is (= "happy" (core/<! *system* a :mood)))
    (core/! a :toggle)
    (is (= "sad" (core/<! *system* a :mood)))
    (core/! a :toggle)
    (is (= "happy" (core/<! *system* a :mood)))))

;; ---------------------------------------------------------------------------
;; Tests: child spawning inside actors
;; ---------------------------------------------------------------------------

(deftest parent-spawns-child-in-init
  (let [p (core/spawn *system* test-parent-spawner)]
    (is (= 42 (core/<! *system* p :ask-child)))))

(deftest child-is-actor-ref
  (let [p (core/spawn *system* test-parent-spawner)]
    (is (instance? ActorRef (core/<! *system* p :get-child)))))

;; ---------------------------------------------------------------------------
;; Tests: guardian pattern (forward)
;; ---------------------------------------------------------------------------

(deftest guardian-forwards-and-replies
  (let [g (core/spawn *system* test-guardian)]
    (is (= "HELLO" (core/<! *system* g "hello")))
    (is (= "WORLD" (core/<! *system* g "world")))))

;; ---------------------------------------------------------------------------
;; Tests: context functions (self, parent, sender)
;; ---------------------------------------------------------------------------

(deftest defactor-self-returns-own-ref
  (let [a (core/spawn *system* test-context-info)]
    (is (= a (core/<! *system* a :get-self)))))

(deftest defactor-parent-returns-parent-ref
  (let [a (core/spawn *system* test-context-info)]
    (let [p (core/<! *system* a :get-parent)]
      (is (instance? ActorRef p))
      (is (not= a p)))))

(deftest defactor-sender-returns-sender-ref
  (let [a (core/spawn *system* test-context-info)]
    (let [s (core/<! *system* a :get-sender)]
      (is (instance? ActorRef s))
      ;; Patterns/ask creates a temp actor as sender
      (is (not= a s))
      (is (not= (ActorRef/noSender) s)))))

;; ---------------------------------------------------------------------------
;; Tests: docstring support
;; ---------------------------------------------------------------------------

(deftest defactor-docstring-preserved
  (is (= "A counter for testing." (:doc (meta #'test-counter)))))

(deftest defactor-empty-docstring-when-none
  (is (= "" (:doc (meta #'test-echo)))))
