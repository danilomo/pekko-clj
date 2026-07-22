(ns pekko-clj.error-handling-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.supervision :as sup])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def timeout-duration (Duration/create 5 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "error-handling-test")]
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
;; Tests: Error Handling
;; ---------------------------------------------------------------------------

(deftest error-handler-catches-exception
  (let [caught-exception (atom nil)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (case msg
                              :fail (throw (RuntimeException. "test error"))
                              :ping (do (.reply this :pong) nil)
                              nil))
                :error-handler (fn [this ex msg]
                                 (reset! caught-exception {:ex ex :msg msg})
                                 nil)
                :state nil})]
    ;; Normal operation works
    (is (= :pong (await-ask actor :ping)))
    ;; Trigger error
    (core/! actor :fail)
    (Thread/sleep 100)
    ;; Error handler was called
    (is (some? @caught-exception))
    (is (instance? RuntimeException (:ex @caught-exception)))
    (is (= "test error" (.getMessage (:ex @caught-exception))))
    (is (= :fail (:msg @caught-exception)))
    ;; Actor still works after error
    (is (= :pong (await-ask actor :ping)))))

(deftest error-handler-can-update-state
  (let [actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :fail (throw (RuntimeException. "error"))
                                :get-errors (do (core/reply (:errors @this)) nil)
                                nil)))
                :error-handler (fn [this ex msg]
                                 (update @this :errors conj (.getMessage ex)))
                :state {:errors []}})]
    ;; Trigger multiple errors
    (core/! actor :fail)
    (core/! actor :fail)
    (core/! actor :fail)
    (Thread/sleep 150)
    ;; Errors were accumulated
    (is (= ["error" "error" "error"] (await-ask actor :get-errors)))))

(deftest error-handler-preserves-state-on-nil-return
  (let [actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (case msg
                                :inc (update @this :count inc)
                                :fail (throw (RuntimeException. "boom"))
                                :get (do (core/reply (:count @this)) nil)
                                nil)))
                :error-handler (fn [this ex msg]
                                 ;; Return nil - state should not change
                                 nil)
                :state {:count 0}})]
    ;; Increment a few times
    (core/! actor :inc)
    (core/! actor :inc)
    (Thread/sleep 50)
    (is (= 2 (await-ask actor :get)))
    ;; Error with nil return - count should stay at 2
    (core/! actor :fail)
    (Thread/sleep 50)
    (is (= 2 (await-ask actor :get)))))

(deftest without-error-handler-exception-propagates
  (let [restarted (atom false)
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (case msg
                              :fail (throw (RuntimeException. "no handler"))
                              :ping (do (.reply this :pong) nil)
                              nil))
                :pre-start (fn [this]
                             (when @restarted
                               ;; Already restarted once
                               nil)
                             (reset! restarted true)
                             nil)
                ;; No error-handler - exception triggers supervision
                :state nil})]
    ;; Works initially
    (is (= :pong (await-ask actor :ping)))
    ;; Trigger error - will cause restart via default supervision
    (core/! actor :fail)
    (Thread/sleep 200)
    ;; Actor was restarted (pre-start called again)
    (is (true? @restarted))
    ;; Actor still works after restart
    (is (= :pong (await-ask actor :ping)))))

(deftest defactor-on-error-clause
  (let [errors (atom [])]
    (core/defactor safe-worker
      (init [_] {:value 0})
      (on-error [ex msg]
        (swap! errors conj {:ex-class (class ex) :msg msg})
        state)  ; preserve state
      (handle :inc
        (update state :value inc))
      (handle :fail
        (throw (ArithmeticException. "divide by zero")))
      (handle :get
        (core/reply (:value state))))

    (let [actor (core/spawn *system* safe-worker nil)]
      ;; Normal operation
      (core/! actor :inc)
      (core/! actor :inc)
      (Thread/sleep 50)
      (is (= 2 (await-ask actor :get)))
      ;; Error is caught
      (core/! actor :fail)
      (Thread/sleep 50)
      ;; State preserved
      (is (= 2 (await-ask actor :get)))
      ;; Error was logged
      (is (= 1 (count @errors)))
      (is (= ArithmeticException (:ex-class (first @errors))))
      (is (= :fail (:msg (first @errors)))))))

(deftest on-error-can-change-behavior
  (core/defactor mode-switcher
    (init [_] {:mode :normal :error-count 0})
    (on-error [ex msg]
      (let [new-count (inc (:error-count state))]
        (if (>= new-count 3)
          {:mode :safe :error-count new-count}
          (assoc state :error-count new-count))))
    (handle :get-mode
      (core/reply (:mode state)))
    (handle :risky
      (if (= :safe (:mode state))
        state  ; In safe mode, do nothing
        (throw (RuntimeException. "risky failed")))))

  (let [actor (core/spawn *system* mode-switcher nil)]
    ;; Start in normal mode
    (is (= :normal (await-ask actor :get-mode)))
    ;; Trigger errors
    (core/! actor :risky)
    (core/! actor :risky)
    (Thread/sleep 100)
    (is (= :normal (await-ask actor :get-mode)))
    ;; Third error switches to safe mode
    (core/! actor :risky)
    (Thread/sleep 100)
    (is (= :safe (await-ask actor :get-mode)))))

(deftest error-handler-has-access-to-context
  (let [self-ref (atom nil)
        sender-ref (atom nil)]
    (core/defactor context-aware
      (init [_] nil)
      (on-error [ex msg]
        (reset! self-ref (core/self))
        (reset! sender-ref (core/sender))
        state)
      (handle :fail
        (throw (RuntimeException. "error")))
      (handle :ping
        (core/reply :pong)))

    (let [actor (core/spawn *system* context-aware nil)]
      ;; Trigger error via ask (so sender is the ask actor)
      (try
        (await-ask actor :fail)
        (catch Exception _))
      (Thread/sleep 100)
      ;; Error handler had access to self
      (is (= actor @self-ref))
      ;; Sender was the ask temporary actor (not noSender)
      (is (some? @sender-ref)))))

;; ---------------------------------------------------------------------------
;; Tests: on-error vs supervision contract (B10)
;; ---------------------------------------------------------------------------

(deftest on-error-does-not-swallow-errors
  ;; An Error must bypass on-error and reach the parent's supervision decider,
  ;; not be swallowed by the on-error handler.
  (let [on-error-calls (atom [])
        decider-received (promise)]
    (core/defactor b10-error-child
      (init [_] nil)
      (on-error [ex msg]
        (swap! on-error-calls conj (class ex))
        state)
      (handle :throw-error
        (throw (AssertionError. "fatal-ish")))
      (handle :ping
        (core/reply :pong)))

    (core/defactor b10-error-parent
      (supervision (sup/one-for-one (fn [ex]
                                      (deliver decider-received (class ex))
                                      :stop)))
      (init [_] nil)
      (handle :spawn
        (core/reply (core/spawn b10-error-child nil))))

    (let [parent (core/spawn *system* b10-error-parent nil)
          child (await-ask parent :spawn)]
      (core/! child :throw-error)
      (is (= AssertionError (deref decider-received 3000 :timeout))
          "the Error reached the parent's supervision decider")
      (is (empty? @on-error-calls)
          "on-error was NOT called for the Error"))))

;; ---------------------------------------------------------------------------
;; Tests: on-restart lifecycle clause (fires on the fresh instance after a
;; supervised restart)
;; ---------------------------------------------------------------------------

(deftest defactor-on-restart-clause
  ;; on-restart runs on the fresh instance after a supervised restart: it sees
  ;; the causing Throwable, init has already re-run (state reset), and its
  ;; return value becomes the new state. on-stop fires on the old instance too.
  (let [restart-events (atom [])
        stop-events    (atom [])]
    (core/defactor restartable-child
      (init [_] {:value 0})
      (on-stop
        (swap! stop-events conj :stopped))
      (on-restart [reason]
        (swap! restart-events conj (class reason))
        (assoc state :restarted true))
      (handle :inc
        (update state :value inc))
      (handle :boom
        (throw (IllegalStateException. "boom")))
      (handle :get
        (core/reply state)))

    (core/defactor restart-parent
      (supervision (sup/one-for-one (fn [_] :restart)))
      (init [_] nil)
      (handle :spawn
        (core/reply (core/spawn restartable-child nil))))

    (let [parent (core/spawn *system* restart-parent nil)
          child  (await-ask parent :spawn)]
      ;; Build up some state, then crash the child → supervised restart.
      (core/! child :inc)
      (core/! child :inc)
      (is (= {:value 2} (await-ask child :get)))
      (core/! child :boom)
      (Thread/sleep 200)
      ;; on-restart fired once with the causing exception's class.
      (is (= [IllegalStateException] @restart-events))
      ;; on-stop fired on the old instance during the restart.
      (is (= [:stopped] @stop-events))
      ;; init re-ran (value back to 0) and on-restart's return set :restarted.
      (is (= {:value 0 :restarted true} (await-ask child :get))))))

(deftest on-error-intercepts-exceptions-before-supervision
  ;; A recoverable Exception is handled by on-error in place; the parent's
  ;; supervision decider is not invoked and the child keeps running.
  (let [on-error-calls (atom [])
        decider-received (atom nil)]
    (core/defactor b10-recoverable-child
      (init [_] nil)
      (on-error [ex msg]
        (swap! on-error-calls conj (class ex))
        state)
      (handle :boom
        (throw (RuntimeException. "recoverable")))
      (handle :ping
        (core/reply :pong)))

    (core/defactor b10-watching-parent
      (supervision (sup/one-for-one (fn [ex]
                                      (reset! decider-received (class ex))
                                      :restart)))
      (init [_] nil)
      (handle :spawn
        (core/reply (core/spawn b10-recoverable-child nil))))

    (let [parent (core/spawn *system* b10-watching-parent nil)
          child (await-ask parent :spawn)]
      (core/! child :boom)
      (Thread/sleep 200)
      (is (= [RuntimeException] @on-error-calls) "on-error handled the Exception")
      (is (nil? @decider-received) "supervision decider was NOT invoked")
      (is (= :pong (await-ask child :ping)) "child is still alive"))))
