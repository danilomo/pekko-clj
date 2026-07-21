(ns pekko-clj.persistence-test
  (:require [clojure.test :refer :all]
            [pekko-clj.persistence :as p]
            [pekko-clj.core :as core]
            [pekko-clj.test-support :refer [eventually]])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [java.io File]
           [java.util UUID]))

(def timeout-duration (Duration/create 5 "seconds"))

(defn- delete-directory [^File dir]
  (when (.exists dir)
    (doseq [f (.listFiles dir)]
      (if (.isDirectory f)
        (delete-directory f)
        (.delete f)))
    (.delete dir)))

(defn- create-test-system [name]
  (let [config (ConfigFactory/load "persistence-test.conf")]
    (ActorSystem/create name config)))

(defn- terminate-system [sys]
  (.terminate sys)
  (Await/result (.whenTerminated sys) (Duration/create 10 "seconds")))

(defn- unique-id []
  (str (UUID/randomUUID)))

;; ---------------------------------------------------------------------------
;; Test Actor Definitions
;; ---------------------------------------------------------------------------

(p/defactor-persistent counter-actor
  :persistence-id (fn [args] (str "counter-" (:id args)))

  (init [args] {:count (or (:initial args) 0)})

  (command :increment
    (p/persist [:incremented]))

  (command [:add n]
    (p/persist [:added n]))

  (command :get
    (.reply this (:count state))
    nil)

  (command :get-state
    (.reply this state)
    nil)

  (event [:incremented]
    (update state :count inc))

  (event [:added n]
    (update state :count + n)))

(p/defactor-persistent counter-with-snapshot
  :persistence-id (fn [args] (str "counter-snap-" (:id args)))

  (init [args] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command :get
    (.reply this (:count state))
    nil)

  (event [:incremented]
    (update state :count inc))

  (snapshot-every 5))

(p/defactor-persistent multi-event-actor
  :persistence-id (fn [args] (str "multi-" (:id args)))

  (init [_] {:items []})

  (command [:add-two a b]
    (p/persist [[:added a] [:added b]]))

  (command :get
    (.reply this (:items state))
    nil)

  (event [:added item]
    (update state :items conj item)))

(def recovery-completed (atom false))

(p/defactor-persistent recovery-callback-actor
  :persistence-id (fn [args] (str "recovery-" (:id args)))

  (init [_] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command :get
    (.reply this (:count state))
    nil)

  (event [:incremented]
    (update state :count inc))

  (on-recovery-complete [this]
    (reset! recovery-completed true)))

(p/defactor-persistent reply-helper-actor
  "Exercises p/reply, p/recovering?, and p/trigger-snapshot!."
  :persistence-id (fn [args] (str "reply-helper-" (:id args)))

  (init [_] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  ;; p/reply returns nil, so no trailing nil is needed to skip persistence.
  (command :get
    (p/reply (:count state)))

  (command :recovering?
    (p/reply (p/recovering? this)))

  (command :snapshot!
    (p/trigger-snapshot! this)
    (p/reply :ok))

  (event [:incremented]
    (update state :count inc)))

(p/defactor-persistent retained-counter
  "snapshot-every with retention: snapshot every 2 events, keep only 1 snapshot."
  :persistence-id (fn [args] (str "retained-" (:id args)))

  (init [_] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command :get
    (p/reply (:count state)))

  (event [:incremented]
    (update state :count inc))

  (snapshot-every 2 1))

(p/defactor-persistent retained-deleting-counter
  "snapshot-every with retention AND delete-events-on-snapshot: events the kept
   snapshot subsumes are deleted from the journal."
  :persistence-id (fn [args] (str "retained-del-" (:id args)))

  (init [_] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command :get
    (p/reply (:count state)))

  (event [:incremented]
    (update state :count inc))

  (snapshot-every 2 1)
  (delete-events-on-snapshot))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest persistent-actor-basic-operations
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys counter-actor {:id id})]
    (try
      ;; Initial state
      (is (= 0 (core/<! actor :get 3000)))

      ;; Increment
      (core/! actor :increment)
      (is (eventually (= 1 (core/<! actor :get 3000))))

      ;; Add
      (core/! actor [:add 5])
      (is (eventually (= 6 (core/<! actor :get 3000))))
      (finally
        (terminate-system sys)))))

(deftest persistent-actor-recovery
  (let [id (unique-id)]
    ;; First run - persist some events
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys counter-actor {:id id})]
          (core/! actor :increment)
          (core/! actor :increment)
          (core/! actor [:add 10])
          (is (eventually (= 12 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))

    ;; Second run - actor should recover state
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys counter-actor {:id id})]
          (is (eventually (= 12 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))))

(deftest persistent-actor-with-initial-state
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys counter-actor {:id id :initial 100})]
    (try
      (is (= 100 (core/<! actor :get 3000)))

      (core/! actor :increment)
      (is (eventually (= 101 (core/<! actor :get 3000))))
      (finally
        (terminate-system sys)))))

(deftest persistent-actor-snapshot
  (let [id (unique-id)]
    ;; First run - trigger snapshot (after 5 events)
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys counter-with-snapshot {:id id})]
          ;; Send 7 increments (snapshot at 5)
          (dotimes [_ 7]
            (core/! actor :increment))
          (is (eventually (= 7 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))

    ;; Second run - should recover from snapshot + remaining events
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys counter-with-snapshot {:id id})]
          (is (eventually (= 7 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))))

(deftest persistent-actor-multiple-events
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys multi-event-actor {:id id})]
    (try
      ;; Add two items at once
      (core/! actor [:add-two :a :b])
      (is (eventually (= [:a :b] (core/<! actor :get 3000))))

      ;; Add two more
      (core/! actor [:add-two :c :d])
      (is (eventually (= [:a :b :c :d] (core/<! actor :get 3000))))
      (finally
        (terminate-system sys)))))

(deftest persistent-actor-multiple-events-recovery
  (let [id (unique-id)]
    ;; First run
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys multi-event-actor {:id id})]
          (core/! actor [:add-two :x :y])
          ;; Confirm both events applied (hence persisted) before terminating.
          (is (eventually (= [:x :y] (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))

    ;; Second run - recover
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys multi-event-actor {:id id})]
          (is (eventually (= [:x :y] (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))))

(deftest persistent-actor-recovery-callback
  (reset! recovery-completed false)
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys recovery-callback-actor {:id id})]
    (try
      (is (eventually @recovery-completed) "Recovery callback should have been called")
      (finally
        (terminate-system sys)))))

(deftest persistent-actor-no-event-for-query
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys counter-actor {:id id})]
    (try
      ;; Query commands return nil (no event to persist)
      (is (= 0 (core/<! actor :get 3000)))

      ;; Still at 0 after multiple queries
      (core/<! actor :get 3000)
      (core/<! actor :get 3000)
      (is (= 0 (core/<! actor :get 3000)))
      (finally
        (terminate-system sys)))))

(deftest spawn-named-persistent-actor
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn-named sys counter-actor {:id id} "my-counter")]
    (try
      (is (= "my-counter" (.name (.path actor))))
      (is (= 0 (core/<! actor :get 3000)))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: p/reply helper, recovering?, trigger-snapshot! (B4)
;; ---------------------------------------------------------------------------

(deftest persistent-reply-helper-works
  ;; Regression: p/reply previously did (resolve 'this) -> nil -> NPE. It must
  ;; now reply via the dynamically-bound current persistent actor.
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys reply-helper-actor {:id id})]
    (try
      (is (= 0 (core/<! actor :get 3000)))
      (core/! actor :increment)
      (is (eventually (= 1 (core/<! actor :get 3000))))
      (finally
        (terminate-system sys)))))

(deftest persistent-recovering?-false-after-recovery
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys reply-helper-actor {:id id})]
    (try
      ;; :recovering? is only handled after recovery, so it is already false.
      (is (false? (core/<! actor :recovering? 3000)))
      (finally
        (terminate-system sys)))))

(deftest persistent-trigger-snapshot!-and-recover
  (let [id (unique-id)]
    ;; First run: persist events, then manually snapshot.
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys reply-helper-actor {:id id})]
          (core/! actor :increment)
          (core/! actor :increment)
          ;; snapshot!/get are handled FIFO after both increments.
          (is (= :ok (core/<! actor :snapshot! 3000)))
          (is (= 2 (core/<! actor :get 3000))))
        (finally
          (terminate-system sys))))
    ;; Second run: state recovers (from snapshot + events).
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys reply-helper-actor {:id id})]
          (is (eventually (= 2 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))))

(deftest defactor-persistent-docstring-preserved
  (is (= "Exercises p/reply, p/recovering?, and p/trigger-snapshot!."
         (:doc (meta #'reply-helper-actor)))))

;; ---------------------------------------------------------------------------
;; Tests: snapshot retention (N2)
;; ---------------------------------------------------------------------------

(deftest snapshot-retention-recovers-correctly
  ;; snapshot-every 2 keep 1 deletes older snapshots as new ones are taken;
  ;; recovery must still reconstruct the correct state from the kept snapshot
  ;; (+ any events after it).
  (let [id (unique-id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys retained-counter {:id id})]
          (dotimes [_ 6] (core/! actor :increment))
          (is (eventually (= 6 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))
    ;; Recover in a fresh system.
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys retained-counter {:id id})]
          (is (eventually (= 6 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))))

(deftest snapshot-retention-with-event-deletion-recovers-correctly
  ;; delete-events-on-snapshot removes events the kept snapshot already covers.
  ;; Recovery loads the latest snapshot and replays only events after it, so the
  ;; state is still correct even though early events are gone.
  (let [id (unique-id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys retained-deleting-counter {:id id})]
          (dotimes [_ 6] (core/! actor :increment))
          (is (eventually (= 6 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys retained-deleting-counter {:id id})]
          (is (eventually (= 6 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))))

(deftest defactor-persistent-rejects-delete-events-without-retention
  ;; delete-events-on-snapshot needs (snapshot-every n keep) — deleting events
  ;; without a retained snapshot would drop unrecoverable history.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires"
        (try
          (macroexpand-1 '(pekko-clj.persistence/defactor-persistent bad-retention
                            :persistence-id (fn [_] "x")
                            (init [_] {})
                            (command :x (p/persist [:e]))
                            (event [:e] state)
                            (snapshot-every 5)
                            (delete-events-on-snapshot)))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

(deftest defactor-persistent-rejects-state-shadow-in-command
  ;; H6: `this`/`state` are reserved anaphors in command bodies (macroexpand-1
  ;; wraps the guard's ExceptionInfo in a CompilerException).
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reserved"
        (try
          (macroexpand-1 '(pekko-clj.persistence/defactor-persistent bad-persistent
                            :persistence-id (fn [_] "x")
                            (init [_] {})
                            (command [:set state] (p/persist [state]))))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))
