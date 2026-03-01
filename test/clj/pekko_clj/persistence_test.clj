(ns pekko-clj.persistence-test
  (:require [clojure.test :refer :all]
            [pekko-clj.persistence :as p]
            [pekko-clj.core :as core])
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

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest persistent-actor-basic-operations
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys counter-actor {:id id})]
    (try
      ;; Initial state
      (is (= 0 (Await/result (core/<?> actor :get 3000) timeout-duration)))

      ;; Increment
      (core/! actor :increment)
      (Thread/sleep 100)
      (is (= 1 (Await/result (core/<?> actor :get 3000) timeout-duration)))

      ;; Add
      (core/! actor [:add 5])
      (Thread/sleep 100)
      (is (= 6 (Await/result (core/<?> actor :get 3000) timeout-duration)))
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
          (Thread/sleep 200)
          (is (= 12 (Await/result (core/<?> actor :get 3000) timeout-duration))))
        (finally
          (terminate-system sys))))

    ;; Second run - actor should recover state
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys counter-actor {:id id})]
          ;; Give time for recovery
          (Thread/sleep 300)
          (is (= 12 (Await/result (core/<?> actor :get 3000) timeout-duration))))
        (finally
          (terminate-system sys))))))

(deftest persistent-actor-with-initial-state
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys counter-actor {:id id :initial 100})]
    (try
      (is (= 100 (Await/result (core/<?> actor :get 3000) timeout-duration)))

      (core/! actor :increment)
      (Thread/sleep 100)
      (is (= 101 (Await/result (core/<?> actor :get 3000) timeout-duration)))
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
          (Thread/sleep 300)
          (is (= 7 (Await/result (core/<?> actor :get 3000) timeout-duration))))
        (finally
          (terminate-system sys))))

    ;; Second run - should recover from snapshot + remaining events
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys counter-with-snapshot {:id id})]
          (Thread/sleep 300)
          (is (= 7 (Await/result (core/<?> actor :get 3000) timeout-duration))))
        (finally
          (terminate-system sys))))))

(deftest persistent-actor-multiple-events
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys multi-event-actor {:id id})]
    (try
      ;; Add two items at once
      (core/! actor [:add-two :a :b])
      (Thread/sleep 100)
      (is (= [:a :b] (Await/result (core/<?> actor :get 3000) timeout-duration)))

      ;; Add two more
      (core/! actor [:add-two :c :d])
      (Thread/sleep 100)
      (is (= [:a :b :c :d] (Await/result (core/<?> actor :get 3000) timeout-duration)))
      (finally
        (terminate-system sys)))))

(deftest persistent-actor-multiple-events-recovery
  (let [id (unique-id)]
    ;; First run
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys multi-event-actor {:id id})]
          (core/! actor [:add-two :x :y])
          ;; Wait for both events to be persisted
          (Thread/sleep 500))
        (finally
          (terminate-system sys))))

    ;; Second run - recover
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys multi-event-actor {:id id})]
          ;; Wait for recovery
          (Thread/sleep 500)
          (is (= [:x :y] (Await/result (core/<?> actor :get 3000) timeout-duration))))
        (finally
          (terminate-system sys))))))

(deftest persistent-actor-recovery-callback
  (reset! recovery-completed false)
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys recovery-callback-actor {:id id})]
    (try
      (Thread/sleep 200)
      (is @recovery-completed "Recovery callback should have been called")
      (finally
        (terminate-system sys)))))

(deftest persistent-actor-no-event-for-query
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys counter-actor {:id id})]
    (try
      ;; Query commands return nil (no event to persist)
      (is (= 0 (Await/result (core/<?> actor :get 3000) timeout-duration)))

      ;; Still at 0 after multiple queries
      (Await/result (core/<?> actor :get 3000) timeout-duration)
      (Await/result (core/<?> actor :get 3000) timeout-duration)
      (is (= 0 (Await/result (core/<?> actor :get 3000) timeout-duration)))
      (finally
        (terminate-system sys)))))

(deftest spawn-named-persistent-actor
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn-named sys counter-actor {:id id} "my-counter")]
    (try
      (is (= "my-counter" (.name (.path actor))))
      (is (= 0 (Await/result (core/<?> actor :get 3000) timeout-duration)))
      (finally
        (terminate-system sys)))))
