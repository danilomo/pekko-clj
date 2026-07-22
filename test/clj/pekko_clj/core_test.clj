(ns pekko-clj.core-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [pekko_clj.actor BecomeResult]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(def timeout-duration (Duration/create 3 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "core-api-test")]
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
;; Tests: dynamic vars
;; ---------------------------------------------------------------------------

(deftest current-actor-default-is-nil
  (is (nil? core/*current-actor*)))

(deftest timeout-default-is-30000
  (is (= 30000 core/*timeout*)))

;; ---------------------------------------------------------------------------
;; Tests: actor-system
;; ---------------------------------------------------------------------------

(deftest actor-system-creates-with-name
  (let [sys (core/actor-system "named-test-system")]
    (try
      (is (instance? ActorSystem sys))
      (is (= "named-test-system" (.name sys)))
      (finally
        (.terminate sys)))))

;; ---------------------------------------------------------------------------
;; Tests: new-actor (backward compat)
;; ---------------------------------------------------------------------------

(deftest new-actor-func-and-state
  (let [actor (core/new-actor *system*
                              (fn [this msg]
                                (.reply this (str "echo:" msg))
                                nil)
                              "init")]
    (is (instance? ActorRef actor))
    (is (= "echo:world" (await-ask actor "world")))))

(deftest new-actor-with-map-props
  (let [actor (core/new-actor *system*
                              {:function (fn [this msg]
                                           (.reply this msg)
                                           nil)
                               :state :initial-state})]
    (is (instance? ActorRef actor))
    (is (= :ping (await-ask actor :ping)))))

;; ---------------------------------------------------------------------------
;; Tests: ! (tell)
;; ---------------------------------------------------------------------------

(deftest tell-outside-context-delivers-message
  ;; ! outside actor context should use noSender and not throw
  (let [received (promise)
        actor (core/new-actor *system*
                              (fn [_this msg]
                                (deliver received msg)
                                nil)
                              nil)]
    (core/! actor :hello)
    (is (= :hello (deref received 3000 :timeout)))))

(deftest tell-outside-context-returns-nil
  (let [actor (core/new-actor *system*
                              (fn [_this _msg] nil)
                              nil)]
    (is (nil? (core/! actor :any)))))

;; ---------------------------------------------------------------------------
;; Tests: <?> (ask)
;; ---------------------------------------------------------------------------

(deftest ask-returns-completion-stage
  (let [actor  (core/new-actor *system*
                               (fn [this _msg] (.reply this :pong) nil)
                               nil)
        future (core/<?> actor :ping 3000)]
    (is (instance? java.util.concurrent.CompletionStage future))
    ;; A CompletableFuture, so it is also derefable and composable.
    (is (instance? java.util.concurrent.CompletableFuture future))))

(deftest ask-future-derefs-to-reply
  (let [actor  (core/new-actor *system*
                               (fn [this _msg] (.reply this :pong) nil)
                               nil)]
    ;; @ works because <?> returns a CompletableFuture.
    (is (= :pong @(core/<?> actor :ping 3000)))))

(deftest ask-future-composes-with-then-apply
  (let [actor  (core/new-actor *system*
                               (fn [this _msg] (.reply this :pong) nil)
                               nil)
        stage  (.thenApply (core/<?> actor :ping 3000)
                           (reify java.util.function.Function
                             (apply [_ v] (name v))))]
    (is (= "pong" @stage))))

(deftest ask-uses-dynamic-timeout
  (binding [core/*timeout* 5000]
    (let [actor (core/new-actor *system*
                                (fn [this _msg] (.reply this :ok) nil)
                                nil)]
      (is (= :ok (await-ask actor :go))))))

;; ---------------------------------------------------------------------------
;; Tests: <! (blocking ask)
;; ---------------------------------------------------------------------------

(deftest blocking-ask-returns-reply
  (let [actor (core/new-actor *system*
                              (fn [this msg]
                                (.reply this (str "reply:" msg))
                                nil)
                              nil)]
    (is (= "reply:world" (core/<! *system* actor "world")))))

(deftest blocking-ask-with-explicit-timeout
  (let [actor (core/new-actor *system*
                              (fn [this _msg] (.reply this :done) nil)
                              nil)]
    (is (= :done (core/<! *system* actor :go 5000)))))

(deftest blocking-ask-two-arg-form
  ;; B2: new ergonomic form without a leading ActorSystem.
  (let [actor (core/new-actor *system*
                              (fn [this msg] (.reply this (str "hi:" msg)) nil)
                              nil)]
    (is (= "hi:x" (core/<! actor "x")))
    (is (= "hi:y" (core/<! actor "y" 3000)))))

(deftest blocking-ask-surfaces-failure-reply
  ;; B2: a genuine failure (here a Status/Failure reply) must be rethrown, not
  ;; silently turned into nil (which was indistinguishable from a timeout).
  (let [actor (core/new-actor *system*
                              (fn [this _]
                                (.reply this (org.apache.pekko.actor.Status$Failure.
                                              (ex-info "boom" {:k 1})))
                                nil)
                              nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
          (core/<! actor :go 3000)))))

(deftest blocking-ask-timeout-returns-nil
  ;; An actor that never replies: the ask times out (AskTimeoutException) and <!
  ;; returns nil — distinct from a surfaced failure.
  (let [actor (core/new-actor *system*
                              (fn [_ _] nil) ; never replies
                              nil)]
    (is (nil? (core/<! actor :go 300)))))

;; ---------------------------------------------------------------------------
;; Tests: become
;; ---------------------------------------------------------------------------

(deftest become-returns-become-result
  (let [handler-fn (fn [_this _msg] nil)
        actor-def  {:receive handler-fn}
        result     (core/become actor-def :new-state)]
    (is (instance? BecomeResult result))
    (is (= handler-fn (.function result)))
    (is (= :new-state (.state result)))))

(deftest become-with-nil-state
  (let [handler-fn (fn [_ _] nil)
        result     (core/become {:receive handler-fn} nil)]
    (is (nil? (.state result)))
    (is (= handler-fn (.function result)))))

(deftest become-with-map-state
  (let [handler-fn (fn [_ _] nil)
        state      {:x 1 :y 2}
        result     (core/become {:receive handler-fn} state)]
    (is (= state (.state result)))))

;; ---------------------------------------------------------------------------
;; Tests: context functions (self, sender, parent, reply, !)
;;   Handlers bind *current-actor* to mimic what defactor will do in spec3.
;; ---------------------------------------------------------------------------

(defn core-api-handler
  "Uses core API functions by binding *current-actor* to this."
  [this msg]
  (binding [core/*current-actor* this]
    (case msg
      :get-self   (do (core/reply (core/self)) nil)
      :get-sender (do (core/reply (core/sender)) nil)
      :get-parent (do (core/reply (core/parent)) nil)
      :reply-test (do (core/reply :replied) nil)
      nil)))

(deftest self-returns-own-actor-ref
  (let [actor (core/new-actor *system* core-api-handler nil)
        self  (await-ask actor :get-self)]
    (is (instance? ActorRef self))
    (is (= actor self))))

(deftest sender-returns-message-sender
  (let [actor  (core/new-actor *system* core-api-handler nil)
        sender (await-ask actor :get-sender)]
    (is (instance? ActorRef sender))
    ;; Patterns/ask creates a temp actor as sender — not the actor itself
    (is (not= actor sender))
    (is (not= (ActorRef/noSender) sender))))

(deftest parent-returns-parent-actor
  (let [actor  (core/new-actor *system* core-api-handler nil)
        parent (await-ask actor :get-parent)]
    (is (instance? ActorRef parent))
    ;; top-level actors are children of the user guardian
    (is (not= actor parent))))

(deftest reply-sends-to-sender
  (let [actor (core/new-actor *system* core-api-handler nil)]
    (is (= :replied (await-ask actor :reply-test)))))

(deftest tell-inside-context-uses-self-as-sender
  ;; When core/! is used inside an actor context, the actor's self should be
  ;; the sender of the forwarded message. We verify by having a receiving actor
  ;; report the sender it observed.
  (let [observed-sender (promise)
        receiver (core/new-actor *system*
                                 (fn [this _msg]
                                   (deliver observed-sender (.senderRef this))
                                   nil)
                                 nil)
        sending-actor (core/new-actor *system*
                                      (fn [this _msg]
                                        (binding [core/*current-actor* this]
                                          (core/! receiver :payload)
                                          (.reply this :done)
                                          nil))
                                      nil)]
    (is (= :done (await-ask sending-actor :go)))
    (let [the-sender (deref observed-sender 3000 :timeout)]
      (is (instance? ActorRef the-sender))
      (is (= sending-actor the-sender)))))

;; ---------------------------------------------------------------------------
;; Tests: spawn
;; ---------------------------------------------------------------------------

(def ^:private echo-actor-def
  {:make-props (fn [_args]
                 {:function (fn [this msg] (.reply this msg) nil)
                  :state nil})})

(def ^:private stateful-actor-def
  {:make-props (fn [initial-state]
                 {:function (fn [this msg]
                              (case msg
                                :ask   (do (.reply this @this) nil)
                                :reset (do (.reply this :ok) 0)
                                nil))
                  :state initial-state})})

(deftest spawn-with-system-creates-actor
  (let [actor (core/spawn *system* echo-actor-def)]
    (is (instance? ActorRef actor))
    (is (= :hello (await-ask actor :hello)))))

(deftest spawn-with-system-and-args-passes-initial-state
  (let [actor (core/spawn *system* stateful-actor-def {:value 42})]
    (is (= {:value 42} (await-ask actor :ask)))))

(deftest spawn-with-system-no-args-form
  ;; (spawn system actor-def) — two-arg form dispatches on ActorSystem
  (let [actor (core/spawn *system* echo-actor-def)]
    (is (instance? ActorRef actor))
    (is (= :ping (await-ask actor :ping)))))

(deftest spawn-inside-context-creates-child-actor
  (let [spawner-handler
        (fn [this msg]
          (binding [core/*current-actor* this]
            (case msg
              :spawn-child (let [child (core/spawn echo-actor-def)]
                             (.reply this child)
                             nil)
              nil)))
        parent-actor (core/new-actor *system* spawner-handler nil)
        child        (await-ask parent-actor :spawn-child)]
    (is (instance? ActorRef child))
    (is (= :echo (await-ask child :echo)))))

;; ---------------------------------------------------------------------------
;; Tests: DX polish (H5) — context, stop, poison-pill, graceful-stop,
;;        actor-selection/identify, actor-system Config arity, shutdown-system
;; ---------------------------------------------------------------------------

(core/defactor h5-context-reporter
  (handle :ctx? (core/reply (instance? org.apache.pekko.actor.ActorContext (core/context)))))

(core/defactor h5-echo
  (handle msg (core/reply msg)))

(deftest context-returns-actor-context
  (let [a (core/spawn *system* h5-context-reporter nil)]
    (is (true? (core/<! a :ctx? 3000)))))

(deftest poison-pill-stops-actor
  (let [stopped (promise)
        a (core/new-actor *system* {:function  (fn [_ _] nil)
                                    :post-stop (fn [_] (deliver stopped true))
                                    :state     nil})]
    (core/poison-pill a)
    (is (true? (deref stopped 3000 false)))))

(deftest graceful-stop-completes
  (let [a (core/new-actor *system* {:function (fn [_ _] nil) :state nil})]
    (is (true? @(core/graceful-stop a 3000)))))

(deftest stop-stops-a-child
  (let [child-stopped (promise)]
    (core/defactor h5-stop-child
      (init [_] nil)
      (on-stop (deliver child-stopped true))
      (handle :ping (core/reply :pong)))
    (core/defactor h5-stop-parent
      (init [_] {})
      (handle :make (core/reply (core/spawn h5-stop-child nil)))
      (handle [:kill c]
        (core/stop c)
        (core/reply :killed)))
    (let [p (core/spawn *system* h5-stop-parent nil)
          c (core/<! p :make 3000)]
      (is (= :pong (core/<! c :ping 3000)))
      (is (= :killed (core/<! p [:kill c] 3000)))
      (is (true? (deref child-stopped 3000 false))))))

(deftest actor-selection-resolves-actor
  (let [a   (core/spawn *system* h5-echo nil)
        sel (core/actor-selection *system* (str (.path a)))]
    (is (= a @(core/identify sel 3000)))))

(deftest actor-system-with-config-and-shutdown
  (let [cfg (.withFallback (com.typesafe.config.ConfigFactory/parseString "my.key = 7")
                           (com.typesafe.config.ConfigFactory/load))
        sys (core/actor-system "cfg-sys" cfg)]
    (is (= 7 (.getInt (.config (.settings sys)) "my.key")))
    ;; shutdown-system returns the Terminated event
    (is (some? (core/shutdown-system sys 10000)))))
