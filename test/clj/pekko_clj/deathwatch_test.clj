(ns pekko-clj.deathwatch-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
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
  (Await/result (core/<?> actor msg 3000) timeout-duration))

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
                {:function (fn [this msg] nil)
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
                                    :state nil})}]
    (let [target (core/spawn *system* watchable-def nil)
          watcher (core/spawn *system* watcher-def target)]
      (is (= :started (await-ask watcher :start-watching)))
      (core/! target :stop)
      (let [terminated-ref (deref terminated-received 3000 :timeout)]
        (is (not= :timeout terminated-ref))
        (is (= target terminated-ref))))))

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
                {:function (fn [this msg] nil)
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
    ;; Wait for termination messages
    (Thread/sleep 500)
    ;; Should have received all three
    (is (= 3 (count @terminated-actors)))
    (is (contains? @terminated-actors target1))
    (is (contains? @terminated-actors target2))
    (is (contains? @terminated-actors target3))))
