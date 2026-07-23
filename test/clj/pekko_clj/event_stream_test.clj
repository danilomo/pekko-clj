(ns pekko-clj.event-stream-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.event-stream :as es]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.actor ActorRef]))

(core/defactor picky
  "Handles only :known — anything else routes to unhandled()."
  (init [_] {})
  (handle :known (core/reply :ok) state))

(core/defactor noop
  (init [_] {})
  (handle _msg state))

(deftest subscribe-and-publish-test
  (let [sys (core/actor-system "es-pub")]
    (try
      (let [received (atom [])
            sub (es/subscribe sys String (fn [m] (swap! received conj m)))]
        (is (instance? ActorRef sub))
        ;; subscribe spawns + registers asynchronously; publish until it lands.
        (is (eventually (do (es/publish sys "hello")
                            (some #{"hello"} @received)))))
      (finally (core/shutdown-system sys)))))

(deftest subscribe-existing-ref-and-unsubscribe-test
  (let [sys (core/actor-system "es-ref")]
    (try
      (let [received (atom [])
            worker (es/subscribe sys clojure.lang.Keyword
                                 (fn [m] (swap! received conj m)))]
        (is (eventually (do (es/publish sys :ping)
                            (some #{:ping} @received))))
        (is (nil? (es/unsubscribe sys worker)))
        (Thread/sleep 300)
        (reset! received [])
        (dotimes [_ 5] (es/publish sys :after))
        (Thread/sleep 300)
        (is (not-any? #{:after} @received)))
      (finally (core/shutdown-system sys)))))

(deftest unsubscribe-stops-internally-spawned-subscriber-test
  ;; H12: subscribe passed a fn spawns an internal actor; unsubscribe used to
  ;; only deregister it from the EventStream, leaking the actor forever.
  (let [sys (core/actor-system "es-leak")]
    (try
      (let [sub (es/subscribe sys clojure.lang.Keyword (fn [_]))]
        (is (nil? (es/unsubscribe sys sub)))
        (is (ts/stopped-within? sys sub)))
      (finally (core/shutdown-system sys)))))

(deftest unsubscribe-does-not-stop-a-caller-supplied-ref-test
  ;; A directly-passed ActorRef is the caller's to manage; unsubscribe must not
  ;; stop it.
  (let [sys (core/actor-system "es-no-leak")]
    (try
      (let [worker (core/spawn sys noop)]
        (is (nil? (es/unsubscribe sys (es/subscribe sys clojure.lang.Keyword worker))))
        (is (not (ts/stopped-within? sys worker 500))))
      (finally (core/shutdown-system sys)))))

(deftest dead-letters-test
  (let [sys (core/actor-system "es-dead")]
    (try
      (let [received (atom [])
            a (core/spawn sys noop)]
        (es/subscribe-dead-letters sys (fn [m] (swap! received conj m)))
        (core/poison-pill a)  ; stop the actor so later sends become dead letters
        ;; Keep sending until the stopped actor's message shows up as a dead letter.
        (is (eventually (do (core/! a :hello)
                            (some (fn [m] (= :hello (:message m))) @received)))))
      (finally (core/shutdown-system sys)))))

(deftest unhandled-message-test
  (let [sys (core/actor-system "es-unhandled")]
    (try
      (let [received (atom [])
            a (core/spawn sys picky)]
        (es/subscribe-unhandled sys (fn [m] (swap! received conj m)))
        ;; :mystery matches no handle clause -> defactor routes it to unhandled().
        (is (eventually (do (core/! a :mystery)
                            (some (fn [m] (= :mystery (:message m))) @received))))
        (is (some (fn [m] (= a (:recipient m))) @received)))
      (finally (core/shutdown-system sys)))))
