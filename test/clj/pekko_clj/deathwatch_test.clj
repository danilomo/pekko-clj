(ns pekko-clj.deathwatch-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pekko-clj.core :as core]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.actor ActorRef]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def poison-pill (org.apache.pekko.actor.PoisonPill/getInstance))
(def no-sender (ActorRef/noSender))

(def timeout-duration (Duration/create 5 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "deathwatch-test")]
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
;; Tests: DeathWatch
;; ---------------------------------------------------------------------------

(deftest watch-receives-terminated-message
  (let [terminated-actor (promise)
        watcher (core/new-actor
                 *system*
                 {:function (fn [this msg]
                              (binding [core/*current-actor* this]
                                (case (first msg)
                                  :watch-target (do
                                                  (core/watch (second msg))
                                                  (.reply this :watching)
                                                  nil)
                                  :terminated (do
                                                (deliver terminated-actor (second msg))
                                                nil)
                                  nil)))
                  :state nil})
        target (core/new-actor
                *system*
                {:function (fn [_this _msg] nil)
                 :state nil})]
    ;; Have watcher watch target
    (is (= :watching (await-ask watcher [:watch-target target])))
    ;; Stop target
    (.tell target poison-pill no-sender)
    ;; Watcher should receive terminated message
    (let [terminated-ref (deref terminated-actor 3000 :timeout)]
      (is (not= :timeout terminated-ref))
      (is (= target terminated-ref)))))

(deftest watch-with-defactor-pattern-matching
  (let [terminated-received (promise)
        ;; Define a simple watchable actor
        watchable-def {:make-props (fn [_]
                                     {:function (fn [this msg]
                                                  (when (= msg :stop)
                                                    (.tell (.selfRef this) poison-pill no-sender))
                                                  nil)
                                      :state nil})}
        ;; Define a watcher actor that pattern matches on [:terminated ref]
        watcher-def {:make-props (fn [target-ref]
                                   {:function (fn [this msg]
                                                (binding [core/*current-actor* this]
                                                  (cond
                                                    (= msg :start-watching)
                                                    (do
                                                      (core/watch target-ref)
                                                      (.reply this :started)
                                                      nil)

                                                    (and (vector? msg)
                                                         (= :terminated (first msg)))
                                                    (do
                                                      (deliver terminated-received (second msg))
                                                      nil)

                                                    :else nil)))
                                    :state nil})}
        target (core/spawn *system* watchable-def nil)
        watcher (core/spawn *system* watcher-def target)]
    (is (= :started (await-ask watcher :start-watching)))
    (core/! target :stop)
    (let [terminated-ref (deref terminated-received 3000 :timeout)]
      (is (not= :timeout terminated-ref))
      (is (= target terminated-ref)))))

(deftest unwatch-prevents-terminated-message
  (let [terminated-received (atom false)
        watcher (core/new-actor
                 *system*
                 {:function (fn [this msg]
                              (binding [core/*current-actor* this]
                                (cond
                                  (and (vector? msg) (= :watch (first msg)))
                                  (do
                                    (core/watch (second msg))
                                    (.reply this :watching)
                                    nil)

                                  (and (vector? msg) (= :unwatch (first msg)))
                                  (do
                                    (core/unwatch (second msg))
                                    (.reply this :unwatched)
                                    nil)

                                  (and (vector? msg) (= :terminated (first msg)))
                                  (do
                                    (reset! terminated-received true)
                                    nil)

                                  :else nil)))
                  :state nil})
        target (core/new-actor
                *system*
                {:function (fn [_this _msg] nil)
                 :state nil})]
    ;; Watch then unwatch
    (is (= :watching (await-ask watcher [:watch target])))
    (is (= :unwatched (await-ask watcher [:unwatch target])))
    ;; Stop target
    (.tell target poison-pill no-sender)
    ;; Give some time for potential message delivery
    (Thread/sleep 200)
    ;; Should NOT have received terminated
    (is (false? @terminated-received))))

(deftest watch-with-custom-message-delivers-marker
  ;; N19: (watch ref msg) is Pekko's watchWith — the watcher receives `msg` as-is
  ;; (here a map marker) instead of a [:terminated ref] vector.
  (let [received (promise)
        watcher (core/new-actor
                 *system*
                 {:function (fn [this msg]
                              (binding [core/*current-actor* this]
                                (cond
                                  (and (vector? msg) (= :watch (first msg)))
                                  (do (core/watch (second msg) {:gone :my-marker})
                                      (.reply this :watching)
                                      nil)

                                  (and (map? msg) (contains? msg :gone))
                                  (do (deliver received msg) nil)

                                  :else nil)))
                  :state nil})
        target (core/new-actor *system* {:function (fn [_ _] nil) :state nil})]
    (is (= :watching (await-ask watcher [:watch target])))
    (.tell target poison-pill no-sender)
    (is (= {:gone :my-marker} (deref received 3000 :timeout)))))

;; ---------------------------------------------------------------------------
;; B21: death pact for an unhandled Terminated
;; ---------------------------------------------------------------------------

(deftest unhandled-terminated-death-pacts-watcher
  ;; B21: a defactor that watches an actor but has NO [:terminated _] clause must
  ;; NOT silently survive the watched actor's death. Pekko's contract is that an
  ;; unhandled Terminated throws DeathPactException, which default supervision
  ;; turns into a stop of the watcher. (Before the fix the message was matched as
  ;; the translated [:terminated ref] vector, so the death-pact branch of
  ;; super.unhandled was unreachable and the watcher lived on.)
  (core/defactor b21-bare-watcher
    (init [target] (core/watch target) nil)
    (handle :ping (core/reply :pong)))
  (let [target (core/new-actor *system* {:function (fn [_ _] nil) :state nil})
        watcher (core/spawn *system* b21-bare-watcher target)]
    (is (= :pong (core/<! watcher :ping 3000)) "watcher is alive and watching")
    (core/poison-pill target)
    (is (ts/stopped-within? *system* watcher 5000)
        "the unhandled Terminated fired the death pact and stopped the watcher")))

(deftest handled-terminated-does-not-death-pact
  ;; B21: a watcher WITH a [:terminated ref] clause consumes the message — no
  ;; death pact, and the actor keeps running afterwards.
  (let [terminated (promise)]
    (core/defactor b21-good-watcher
      (init [target] (core/watch target) nil)
      (handle :ping (core/reply :pong))
      (handle [:terminated ref] (deliver terminated ref) nil))
    (let [target (core/new-actor *system* {:function (fn [_ _] nil) :state nil})
          watcher (core/spawn *system* b21-good-watcher target)]
      (is (= :pong (core/<! watcher :ping 3000)))
      (core/poison-pill target)
      (is (= target (deref terminated 3000 :timeout)) "the terminated clause fired")
      (is (= :pong (core/<! watcher :ping 3000)) "watcher survived — no death pact"))))

(deftest unmatched-watchwith-marker-does-not-death-pact
  ;; B21: watchWith delivers a custom message, not a Terminated. An *unmatched*
  ;; marker is an ordinary UnhandledMessage — never a death pact (matches Pekko,
  ;; where the custom message is just a message). The watcher survives.
  (core/defactor b21-watchwith-watcher
    (init [target] (core/watch target {:gone true}) nil)
    (handle :ping (core/reply :pong)))
  (let [target (core/new-actor *system* {:function (fn [_ _] nil) :state nil})
        watcher (core/spawn *system* b21-watchwith-watcher target)]
    (is (= :pong (core/<! watcher :ping 3000)))
    (core/poison-pill target)
    ;; give the marker ample time to be (mis)delivered: if it death-pacted, the
    ;; watcher would stop within this window.
    (is (not (ts/stopped-within? *system* watcher 1500))
        "an unmatched watchWith marker must not stop the watcher")
    (is (= :pong (core/<! watcher :ping 3000)) "watcher is still responsive")))

(deftest watch-multiple-actors
  (let [terminated-actors (atom #{})
        watcher (core/new-actor
                 *system*
                 {:function (fn [this msg]
                              (binding [core/*current-actor* this]
                                (cond
                                  (and (vector? msg) (= :watch (first msg)))
                                  (do
                                    (core/watch (second msg))
                                    (.reply this :watching)
                                    nil)

                                  (and (vector? msg) (= :terminated (first msg)))
                                  (do
                                    (swap! terminated-actors conj (second msg))
                                    nil)

                                  :else nil)))
                  :state nil})
        target1 (core/new-actor *system* {:function (fn [_ _] nil) :state nil})
        target2 (core/new-actor *system* {:function (fn [_ _] nil) :state nil})
        target3 (core/new-actor *system* {:function (fn [_ _] nil) :state nil})]
    ;; Watch all three
    (is (= :watching (await-ask watcher [:watch target1])))
    (is (= :watching (await-ask watcher [:watch target2])))
    (is (= :watching (await-ask watcher [:watch target3])))
    ;; Stop all three
    (.tell target1 poison-pill no-sender)
    (.tell target2 poison-pill no-sender)
    (.tell target3 poison-pill no-sender)
    ;; Should receive all three termination messages.
    (is (eventually (= 3 (count @terminated-actors))))
    (is (contains? @terminated-actors target1))
    (is (contains? @terminated-actors target2))
    (is (contains? @terminated-actors target3))))
