(ns pekko-clj.cluster.pubsub-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.cluster.pubsub :as pubsub]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.actor ActorRef]))

;; ---------------------------------------------------------------------------
;; Test actor: collects every message it receives into an atom passed via args.
;; ---------------------------------------------------------------------------

(core/defactor collector
  "Appends each received message to the atom in its :sink arg."
  (init [args] {:sink (:sink args)})
  (handle msg
    (swap! (:sink state) conj msg)
    state))

(defn- ref-path
  "The registrable (address-less) path string of an actor, for send/send-to-all."
  [^ActorRef ref]
  (.toStringWithoutAddress (.path ref)))

;; ---------------------------------------------------------------------------
;; Mediator access
;; ---------------------------------------------------------------------------

(deftest mediator-test
  (let [sys (ts/create-cluster-system "pubsub-mediator")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [m1 (pubsub/mediator sys)
            m2 (pubsub/mediator sys)]
        (is (instance? ActorRef m1))
        ;; The extension is a singleton — same mediator each time.
        (is (= m1 m2)))
      (finally (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Topic pub-sub
;; ---------------------------------------------------------------------------

(deftest topic-publish-subscribe-test
  (let [sys (ts/create-cluster-system "pubsub-topic")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [received (atom [])
            sub      (pubsub/subscribe sys "news" (fn [m] (swap! received conj m)))]
        ;; subscribe with a handler fn returns the spawned subscriber ActorRef.
        (is (instance? ActorRef sub))
        ;; Subscription registration + delivery are asynchronous; publish until the
        ;; message lands (duplicates from retries are harmless for a `some` check).
        (is (eventually (do (pubsub/publish sys "news" {:headline "hello"})
                            (some #{{:headline "hello"}} @received)))))
      (finally (ts/terminate-system sys)))))

(deftest topic-fans-out-to-all-subscribers-test
  (let [sys (ts/create-cluster-system "pubsub-fanout")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [a (atom [])
            b (atom [])]
        (pubsub/subscribe sys "events" (fn [m] (swap! a conj m)))
        (pubsub/subscribe sys "events" (fn [m] (swap! b conj m)))
        ;; Every subscriber of the topic receives each published message.
        (is (eventually (do (pubsub/publish sys "events" :tick)
                            (and (some #{:tick} @a)
                                 (some #{:tick} @b))))))
      (finally (ts/terminate-system sys)))))

(deftest subscribe-existing-actor-ref-test
  (let [sys (ts/create-cluster-system "pubsub-ref")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [received (atom [])
            worker   (core/spawn sys collector {:sink received})
            sub      (pubsub/subscribe sys "jobs" worker)]
        ;; Passing an ActorRef subscribes it directly and returns that same ref.
        (is (= worker sub))
        (is (eventually (do (pubsub/publish sys "jobs" [:job 1])
                            (some #{[:job 1]} @received)))))
      (finally (ts/terminate-system sys)))))

(deftest unsubscribe-stops-delivery-test
  (let [sys (ts/create-cluster-system "pubsub-unsub")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [received (atom [])
            sub      (pubsub/subscribe sys "chatter" (fn [m] (swap! received conj m)))]
        ;; First confirm delivery is working.
        (is (eventually (do (pubsub/publish sys "chatter" :before)
                            (some #{:before} @received))))
        ;; Unsubscribe and let the removal settle, then confirm new messages
        ;; addressed to the topic are no longer delivered.
        (is (nil? (pubsub/unsubscribe sys "chatter" sub)))
        (Thread/sleep 800)
        (reset! received [])
        (dotimes [_ 5] (pubsub/publish sys "chatter" :after))
        (Thread/sleep 500)
        (is (not-any? #{:after} @received)))
      (finally (ts/terminate-system sys)))))

(deftest unsubscribe-stops-internally-spawned-subscriber-test
  ;; H12: subscribe passed a fn spawns an internal topic-subscriber actor;
  ;; unsubscribe used to only tell the mediator to remove the registration,
  ;; leaking the actor forever.
  (let [sys (ts/create-cluster-system "pubsub-leak")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [sub (pubsub/subscribe sys "leaky-topic" (fn [_]))]
        (is (nil? (pubsub/unsubscribe sys "leaky-topic" sub)))
        (is (ts/stopped-within? sys sub)))
      (finally (ts/terminate-system sys)))))

(deftest unsubscribe-does-not-stop-a-caller-supplied-ref-test
  (let [sys (ts/create-cluster-system "pubsub-no-leak")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [received (atom [])
            worker (core/spawn sys collector {:sink received})
            sub (pubsub/subscribe sys "own-ref-topic" worker)]
        (is (nil? (pubsub/unsubscribe sys "own-ref-topic" sub)))
        (is (not (ts/stopped-within? sys worker 500))))
      (finally (ts/terminate-system sys)))))

(deftest topic-groups-deliver-test
  (let [sys (ts/create-cluster-system "pubsub-groups")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [received (atom [])]
        ;; A grouped subscription still receives ordinary publishes, and receives a
        ;; one-per-group publish (consumer-group semantics).
        (pubsub/subscribe sys "tasks" "group-a" (fn [m] (swap! received conj m)))
        (is (eventually (do (pubsub/publish sys "tasks" :task true)
                            (some #{:task} @received)))))
      (finally (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Point-to-point / broadcast by actor path
;; ---------------------------------------------------------------------------

(deftest point-to-point-send-test
  (let [sys (ts/create-cluster-system "pubsub-send")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [received (atom [])
            worker   (core/spawn sys collector {:sink received})
            path     (ref-path worker)]
        (is (nil? (pubsub/put sys worker)))
        ;; Registration is async and Send is best-effort — retry until it routes.
        (is (eventually (do (pubsub/send sys path :ping)
                            (some #{:ping} @received)))))
      (finally (ts/terminate-system sys)))))

(deftest send-to-all-test
  (let [sys (ts/create-cluster-system "pubsub-send-all")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [received (atom [])
            worker   (core/spawn sys collector {:sink received})
            path     (ref-path worker)]
        (pubsub/put sys worker)
        (is (eventually (do (pubsub/send-to-all sys path :broadcast)
                            (some #{:broadcast} @received)))))
      (finally (ts/terminate-system sys)))))
