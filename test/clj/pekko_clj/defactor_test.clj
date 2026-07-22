(ns pekko-clj.defactor-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pekko-clj.core :as core :refer [defactor]])
  (:import [org.apache.pekko.actor ActorRef UnhandledMessage]
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

;; Actors WITHOUT a catch-all — exercise the auto-generated unhandled default.
(defactor test-picky
  (handle :ping
    (core/reply :pong)))

(defactor test-picky-counter
  "Stateful actor with no catch-all: an unmatched message must NOT restart it
   (which would reset the count via init)."
  (init [_] {:count 0})

  (handle :inc
    (update state :count inc))

  (handle :get
    (core/reply (:count state))))

;; State is a vector — must be treated as state, not as a [fn state] become-pair.
(defactor vector-state-actor
  (init [_] [])

  (handle [:push x]
    (conj state x))

  (handle :get
    (core/reply state)))

;; Captures UnhandledMessage events off the event stream and delivers the one
;; matching the expected payload to a promise.
(defactor unhandled-listener
  (init [args]
    {:promise (:promise args)
     :expect  (:expect args)})

  (handle msg
    (when (and (instance? UnhandledMessage msg)
               (= (:expect state) (.getMessage ^UnhandledMessage msg)))
      (deliver (:promise state) msg))
    state))

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
  (let [a (core/spawn *system* test-context-info)
        p (core/<! *system* a :get-parent)]
    (is (instance? ActorRef p))
    (is (not= a p))))

(deftest defactor-sender-returns-sender-ref
  (let [a (core/spawn *system* test-context-info)
        s (core/<! *system* a :get-sender)]
    (is (instance? ActorRef s))
    ;; Patterns/ask creates a temp actor as sender
    (is (not= a s))
    (is (not= (ActorRef/noSender) s))))

;; ---------------------------------------------------------------------------
;; Tests: docstring support
;; ---------------------------------------------------------------------------

(deftest defactor-docstring-preserved
  (is (= "A counter for testing." (:doc (meta #'test-counter)))))

(deftest defactor-no-docstring-when-none
  ;; H6: no docstring provided -> the var carries no :doc (not an empty string).
  (is (nil? (:doc (meta #'test-echo)))))

(deftest defactor-rejects-state-shadow-in-init
  ;; H6: `state` is a reserved anaphor; shadowing it throws at macro-expansion
  ;; (macroexpand-1 wraps the guard's ExceptionInfo in a CompilerException).
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reserved"
        (try
          (macroexpand-1 '(pekko-clj.core/defactor bad-shadow-actor
                            (init [state] {:v state})
                            (handle :ping nil)))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

(deftest defactor-rejects-state-shadow-in-on-restart
  ;; `state` is auto-bound in the on-restart body too, so a binding named
  ;; `state` must be rejected at macro-expansion.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reserved"
        (try
          (macroexpand-1 '(pekko-clj.core/defactor bad-restart-actor
                            (init [_] nil)
                            (on-restart [state] nil)
                            (handle :ping nil)))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

;; ---------------------------------------------------------------------------
;; Tests: unhandled-message parity (B3) — no catch-all must not crash the actor
;; ---------------------------------------------------------------------------

(deftest defactor-unmatched-message-does-not-crash
  ;; Without the catch-all, an unmatched message throws MatchError → the actor
  ;; is restarted; with it, the actor keeps running and stays responsive.
  (let [a (core/spawn *system* test-picky)]
    (core/! a :not-a-known-message)
    (is (= :pong (core/<! *system* a :ping)))))

(deftest defactor-unmatched-message-preserves-state
  ;; A MatchError-driven restart would re-run init and reset :count to 0, so a
  ;; preserved count proves the unmatched message did NOT crash/restart.
  (let [a (core/spawn *system* test-picky-counter)]
    (core/! a :inc)
    (core/! a :inc)
    (core/! a :some-unknown-message)
    (is (= 2 (core/<! *system* a :get)))))

(deftest defactor-unmatched-message-published-as-unhandled
  ;; The unmatched message is routed to Pekko's unhandled() → published on the
  ;; event stream as an UnhandledMessage rather than silently vanishing.
  (let [received (promise)
        listener (core/spawn *system* unhandled-listener
                             {:promise received :expect :ghost-message})
        a        (core/spawn *system* test-picky)]
    (.subscribe (.getEventStream *system*) listener UnhandledMessage)
    (core/! a :ghost-message)
    (let [um (deref received 5000 :timeout)]
      (is (instance? UnhandledMessage um) "an UnhandledMessage was published")
      (is (= :ghost-message (.getMessage ^UnhandledMessage um)))
      (is (= a (.getRecipient ^UnhandledMessage um))))))

(deftest defactor-catch-all-still-handles-unknown
  ;; Regression: a bare-symbol catch-all (test-echo) keeps matching everything
  ;; and must NOT be shadowed by an appended default.
  (let [a (core/spawn *system* test-echo)]
    (is (= :anything (core/<! *system* a :anything)))
    (is (= "str" (core/<! *system* a "str")))))

;; ---------------------------------------------------------------------------
;; Tests: vector state (B1) — a returned vector is state, not a become-pair
;; ---------------------------------------------------------------------------

(deftest vector-state-actor-initial-empty-vector
  ;; init returning [] previously NPE'd in handleSeq (empty seq).
  (let [a (core/spawn *system* vector-state-actor)]
    (is (= [] (core/<! *system* a :get)))))

(deftest vector-state-actor-mutates-across-messages
  ;; A handler returning a vector (whose first element is an Integer) previously
  ;; hit the PersistentVector branch → (IFn) 1 ClassCastException / behavior swap.
  (let [a (core/spawn *system* vector-state-actor)]
    (core/! a [:push 1])
    (core/! a [:push 2])
    (core/! a [:push 3])
    (is (= [1 2 3] (core/<! *system* a :get)))))
