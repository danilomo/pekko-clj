(ns pekko-clj.persistence.delivery-test
  (:require [clojure.test :refer [deftest is testing]]
            [pekko-clj.core :as core]
            [pekko-clj.persistence.delivery :as d]
            [pekko-clj.test-support :as ts])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [java.util UUID]))

(defn- create-test-system [name]
  (ActorSystem/create name (ConfigFactory/load "persistence-test.conf")))

(defn- terminate-system [sys]
  (.terminate sys)
  (Await/result (.whenTerminated sys) (Duration/create 10 "seconds")))

(defn- unique-id [] (str (UUID/randomUUID)))

;; A plain actor that just records every delivered message and never confirms, so
;; the test controls when (and whether) confirmation happens.
(defn- recording-destination [sys received]
  (core/new-actor sys
                  {:function (fn [_this msg]
                               (when (and (vector? msg) (= :deliver (first msg)))
                                 (swap! received conj msg))
                               nil)
                   :state nil}))

;; A reliable notifier: it persists that a message was queued, delivers it in the
;; event handler (so replay rebuilds the outstanding set), and on an ack persists +
;; confirms. Redelivery is fast so the test does not wait long.
(d/defactor-delivery notifier
  :persistence-id (fn [args] (str "notifier-" (:id args)))

  (init [args] {:target (:target args)})

  (command [:notify payload]
    (d/persist [:queued payload]))

  (command [:ack delivery-id]
    (d/persist [:confirmed delivery-id]))

  (command :unconfirmed
    (d/reply (d/num-unconfirmed))
    nil)

  (event [:queued payload]
    (d/deliver (:target state) (fn [delivery-id] [:deliver delivery-id payload]))
    state)

  (event [:confirmed delivery-id]
    (d/confirm-delivery! delivery-id)
    state)

  (redeliver-interval (java.time.Duration/ofMillis 300)))

(deftest redelivers-until-confirmed-then-stops-test
  (testing "a message is redelivered until confirmed, and confirmation stops it"
    (let [sys (create-test-system "delivery-test")]
      (try
        (let [received (atom [])
              dest (recording-destination sys received)
              n (d/spawn sys notifier {:id (unique-id) :target dest})]
          (core/! n [:notify "hello"])
          ;; redelivery: the same message arrives more than once
          (is (ts/poll-until #(>= (count @received) 2) 6000)
              "the unconfirmed message is redelivered")
          (is (= 1 (core/<! n :unconfirmed 3000)) "one message outstanding")
          ;; confirm the delivery id carried in the message
          (let [delivery-id (second (first @received))]
            (core/! n [:ack delivery-id])
            (is (ts/poll-until #(= 0 (core/<! n :unconfirmed 3000)) 6000)
                "confirmation cleared the outstanding set")
            ;; and no further redelivery arrives once nothing is outstanding
            (Thread/sleep 700)
            (let [c1 (count @received)]
              (Thread/sleep 1000)
              (is (= c1 (count @received)) "redelivery stopped after confirmation"))))
        (finally
          (terminate-system sys))))))

(deftest delivery-state-survives-restart-test
  (testing "an unconfirmed delivery is re-issued after the actor restarts (rebuilt by replay)"
    (let [sys (create-test-system "delivery-test")]
      (try
        (let [received (atom [])
              dest (recording-destination sys received)
              id (unique-id)
              n1 (d/spawn sys notifier {:id id :target dest})]
          (core/! n1 [:notify "durable"])
          (is (ts/poll-until #(>= (count @received) 1) 6000) "delivered at least once")
          ;; stop the sender; its redelivery timer stops with it
          (core/poison-pill n1)
          (is (ts/stopped-within? sys n1 5000) "the sender stopped")
          (let [count-at-restart (count @received)
                ;; a fresh instance with the same persistence id recovers the journal
                n2 (d/spawn sys notifier {:id id :target dest})]
            (is (ts/poll-until #(> (count @received) count-at-restart) 6000)
                "recovery replayed the queued event and resumed redelivery")
            (is (= 1 (core/<! n2 :unconfirmed 3000))
                "the outstanding set was rebuilt from the journal")
            ;; confirming via the recovered actor still stops it
            (let [delivery-id (second (last @received))]
              (core/! n2 [:ack delivery-id])
              (is (ts/poll-until #(= 0 (core/<! n2 :unconfirmed 3000)) 6000)
                  "the recovered actor confirms and stops redelivery"))))
        (finally
          (terminate-system sys))))))

;; ---------------------------------------------------------------------------
;; Macro validation (mirrors the H8 defactor-persistent guard tests)
;; ---------------------------------------------------------------------------

(deftest defactor-delivery-rejects-unknown-clause-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown clause"
        (try
          (macroexpand-1 '(pekko-clj.persistence.delivery/defactor-delivery bad
                            :persistence-id (fn [_] "x")
                            (init [_] {})
                            (command :x (d/persist [:e]))
                            (event [:e] state)
                            (tagger [event] #{"x"})))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

(deftest defactor-delivery-requires-persistence-id-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":persistence-id clause is required"
        (try
          (macroexpand-1 '(pekko-clj.persistence.delivery/defactor-delivery no-id
                            (init [_] {})
                            (command :x (d/persist [:e]))
                            (event [:e] state)))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

(deftest defactor-delivery-rejects-duplicate-singleton-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only one `redeliver-interval`"
        (try
          (macroexpand-1 '(pekko-clj.persistence.delivery/defactor-delivery dup
                            :persistence-id (fn [_] "x")
                            (command :x (d/persist [:e]))
                            (event [:e] state)
                            (redeliver-interval (java.time.Duration/ofSeconds 1))
                            (redeliver-interval (java.time.Duration/ofSeconds 2))))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

;; ---------------------------------------------------------------------------
;; H13: core/! resolves the sender inside a delivery command handler
;; ---------------------------------------------------------------------------

(d/defactor-delivery h13-delivery-sender
  :persistence-id (fn [args] (str "h13-del-sender-" (:id args)))

  (init [args] {:probe (:probe args)})

  (command [:ping-probe]
    (core/! (:probe state) :hi)
    nil))

;; H14: redeliver-interval as a plain ms number (not a Duration).
(d/defactor-delivery ms-notifier
  :persistence-id (fn [args] (str "ms-notifier-" (:id args)))
  (init [args] {:target (:target args)})
  (command [:notify payload]
    (d/persist [:queued payload]))
  (event [:queued payload]
    (d/deliver (:target state) (fn [delivery-id] [:deliver delivery-id payload]))
    state)
  (redeliver-interval 300))

(deftest redeliver-interval-accepts-millis
  ;; A ms number must be coerced to a Duration for the Java side; before the fix
  ;; the actor threw ClassCastException at construction (the Java prop is cast to
  ;; java.time.Duration), so spawn itself failed.
  (let [sys (create-test-system "h14-delivery-ms")]
    (try
      (let [received (atom [])
            dest (recording-destination sys received)
            n (d/spawn sys ms-notifier {:id (unique-id) :target dest})]
        (core/! n [:notify "hi"])
        (is (ts/poll-until #(>= (count @received) 2) 6000)
            "the unconfirmed message is redelivered on the ms interval"))
      (finally (terminate-system sys)))))

(deftest delivery-command-tell-uses-entity-as-sender
  ;; H13: like the persistent case, core/! inside a delivery command body must
  ;; send with the entity as sender, not noSender. *current-actor* is unbound in
  ;; a delivery body; the fix resolves the sender via *current-self*.
  (let [sys (create-test-system "h13-delivery-sender")]
    (try
      (let [got-sender (promise)
            probe (core/new-actor sys
                                  {:function (fn [this _msg]
                                               (binding [core/*current-actor* this]
                                                 (deliver got-sender (core/sender)))
                                               nil)
                                   :state nil})
            entity (d/spawn sys h13-delivery-sender {:id (unique-id) :probe probe})]
        (core/! entity [:ping-probe])
        (is (= entity (deref got-sender 5000 :timeout))
            "the probe saw the delivery entity as sender (noSender before the fix)"))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; H15: friendly errors
;; ---------------------------------------------------------------------------

(deftest delivery-out-of-context-calls-name-the-fn
  ;; self/sender/context called outside a delivery handler throw a friendly
  ;; IllegalStateException naming the fn, not a bare NPE.
  (is (thrown-with-msg? IllegalStateException #"pekko-clj\.persistence\.delivery/self" (d/self)))
  (is (thrown-with-msg? IllegalStateException #"pekko-clj\.persistence\.delivery/context" (d/context))))
