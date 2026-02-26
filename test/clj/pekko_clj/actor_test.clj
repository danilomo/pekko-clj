(ns pekko-clj.actor-test
  (:require [clojure.test :refer :all])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.pattern Patterns]
           [pekko_clj.actor BecomeResult CljActor]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(def ^:dynamic *system* nil)

(def timeout-duration (Duration/create 3 "seconds"))

(defn actor-system-fixture [f]
  (let [sys (ActorSystem/create "test-system")]
    (try
      (binding [*system* sys]
        (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each actor-system-fixture)

(defn new-actor
  "Create an actor without requiring pekko-clj.core (avoids global system side effect)."
  ([props] (.actorOf *system* (CljActor/create props)))
  ([func initial] (.actorOf *system* (CljActor/create initial func))))

(defn ask
  "Send a message and block for the reply."
  [actor msg]
  (Await/result (Patterns/ask actor msg 3000) timeout-duration))

;; ---------------------------------------------------------------------------
;; Handler functions for tests
;; ---------------------------------------------------------------------------

(declare handler-b)

(defn handler-a
  "Initial handler: replies with 'a:<state>', or becomes handler-b on :become."
  [this msg]
  (case msg
    :ask    (do (.reply this (str "a:" @this)) nil)
    :become (do (.reply this :became) (BecomeResult/of handler-b "state-b"))
    nil))

(defn handler-b
  "Second handler: replies with 'b:<state>'."
  [this msg]
  (case msg
    :ask (do (.reply this (str "b:" @this)) nil)
    nil))

(defn echo-handler
  "Replies with whatever message it receives."
  [this msg]
  (.reply this msg)
  nil)

(defn self-ref-handler
  "Replies with its own selfRef."
  [this msg]
  (case msg
    :get-self (do (.reply this (.selfRef this)) nil)
    nil))

(defn sender-ref-handler
  "Replies with the senderRef of the current message."
  [this msg]
  (case msg
    :get-sender (do (.reply this (.senderRef this)) nil)
    nil))

(defn parent-ref-handler
  "Replies with its parentRef."
  [this msg]
  (case msg
    :get-parent (do (.reply this (.parentRef this)) nil)
    nil))

(declare child-handler)

(defn spawn-map-handler
  "On :spawn, spawns a child actor using spawn(ILookup) and stores the ref.
   On :ask-child, forwards the message to the child."
  [this msg]
  (case msg
    :spawn (let [child (.spawn this {:function child-handler :state "child-state"})]
             (.reply this :spawned)
             {:child child})
    :ask-child (do (.forward this (:child @this) :ask-child) nil)
    nil))

(defn child-handler
  "Child actor: replies with its state."
  [this msg]
  (case msg
    :ask-child (do (.reply this (str "child:" @this)) nil)
    nil))

(defn state-update-handler
  "Returns new state directly (not BecomeResult, not vector)."
  [this msg]
  (case msg
    :ask   (do (.reply this @this) nil)
    :inc   (+ @this 1)
    :reset 0
    nil))

;; ---------------------------------------------------------------------------
;; Tests: BecomeResult changes actor behavior
;; ---------------------------------------------------------------------------

(deftest become-result-switches-behavior-and-state
  (let [actor (new-actor handler-a "state-a")]
    (testing "initial handler responds"
      (is (= "a:state-a" (ask actor :ask))))
    (testing "become switches to handler-b"
      (is (= :became (ask actor :become))))
    (testing "new handler responds with new state"
      (is (= "b:state-b" (ask actor :ask))))))

(deftest become-result-is-preferred-over-plain-state-update
  (testing "BecomeResult updates both function and state atomically"
    (let [actor (new-actor handler-a "initial")]
      (ask actor :become)
      ;; handler-b only handles :ask, so if become didn't work
      ;; this would fail or return the wrong format
      (is (= "b:state-b" (ask actor :ask))))))

;; ---------------------------------------------------------------------------
;; Tests: plain state updates still work (backward compat)
;; ---------------------------------------------------------------------------

(deftest plain-state-update-works
  (let [actor (new-actor state-update-handler 0)]
    (is (= 0 (ask actor :ask)))
    (.tell actor :inc (ActorRef/noSender))
    (.tell actor :inc (ActorRef/noSender))
    ;; ask is sequenced after the tells within the actor's mailbox
    (is (= 2 (ask actor :ask)))
    (.tell actor :reset (ActorRef/noSender))
    (is (= 0 (ask actor :ask)))))

;; ---------------------------------------------------------------------------
;; Tests: convenience methods
;; ---------------------------------------------------------------------------

(deftest self-ref-returns-own-actor-ref
  (let [actor (new-actor self-ref-handler nil)
        self  (ask actor :get-self)]
    (is (instance? ActorRef self))
    (is (= actor self))))

(deftest sender-ref-returns-message-sender
  (let [actor  (new-actor sender-ref-handler nil)
        sender (ask actor :get-sender)]
    (is (instance? ActorRef sender))
    ;; Patterns/ask creates a temporary actor as sender, so it should not
    ;; be the actor itself and should not be noSender
    (is (not= actor sender))
    (is (not= (ActorRef/noSender) sender))))

(deftest parent-ref-returns-parent-actor
  (let [actor  (new-actor parent-ref-handler nil)
        parent (ask actor :get-parent)]
    (is (instance? ActorRef parent))
    ;; top-level actors are children of the user guardian
    (is (not= actor parent))))

;; ---------------------------------------------------------------------------
;; Tests: spawn(ILookup) overload
;; ---------------------------------------------------------------------------

(deftest spawn-with-map-creates-child-actor
  (let [parent (new-actor spawn-map-handler {})]
    (testing "parent spawns child from map props"
      (is (= :spawned (ask parent :spawn))))
    (testing "child actor is functional and has correct state"
      (is (= "child:child-state" (ask parent :ask-child))))))

;; ---------------------------------------------------------------------------
;; Tests: create(ILookup) factory with pre-start
;; ---------------------------------------------------------------------------

(deftest create-with-map-props-and-pre-start
  (let [started (promise)
        actor (new-actor {:function echo-handler
                          :state "map-state"
                          :pre-start (fn [this]
                                       (deliver started @this)
                                       nil)})]
    (testing "pre-start runs and sees initial state"
      (is (= "map-state" (deref started 3000 :timeout))))
    (testing "actor is functional after pre-start"
      (is (= :hello (ask actor :hello))))))

(deftest create-with-map-props-pre-start-can-set-state
  (let [actor (new-actor {:function (fn [this msg]
                                      (case msg
                                        :ask (do (.reply this @this) nil)
                                        nil))
                          :pre-start (fn [_this]
                                       {:initialized true})})]
    (testing "pre-start return value becomes actor state"
      (is (= {:initialized true} (ask actor :ask))))))
