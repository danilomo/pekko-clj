(ns pekko-clj.persistence-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.persistence :as p]
            [pekko-clj.persistence.query :as q]
            [pekko-clj.stream :as s]
            [pekko-clj.core :as core]
            [pekko-clj.event-stream :as es]
            [pekko-clj.test-support :refer [eventually]])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [java.util UUID]))

(def timeout-duration (Duration/create 5 "seconds"))

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

  (init [_args] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command :get
    (.reply this (:count state))
    nil)

  (event [:incremented]
    (update state :count inc))

  (snapshot-every 5))

;; B15: handles only :known — anything else must route to unhandled() rather
;; than being silently dropped.
(p/defactor-persistent picky-persistent
  :persistence-id (fn [args] (str "picky-" (:id args)))

  (init [_] {:count 0})

  (command :known
    (p/persist [:incremented]))

  (command :get
    (p/reply (:count state)))

  (event [:incremented]
    (update state :count inc)))

;; B15: a user-supplied catch-all (`other`) must still win over the default
;; unhandled() fallback.
(p/defactor-persistent catch-all-persistent
  :persistence-id (fn [args] (str "catchall-" (:id args)))

  (init [_] {:log []})

  (command :known
    (p/persist [:known-hit]))

  (command :get
    (p/reply (:log state)))

  (command other
    (p/persist [:caught other]))

  (event [:known-hit]
    (update state :log conj :known))

  (event [:caught other]
    (update state :log conj other)))

(p/defactor-persistent multi-event-actor
  :persistence-id (fn [args] (str "multi-" (:id args)))

  (init [_] {:items []})

  (command [:add-two a b]
    (p/persist-all [[:added a] [:added b]]))

  (command :get
    (.reply this (:items state))
    nil)

  (event [:added item]
    (update state :items conj item)))

;; Regression: a single event whose value is itself a vector-of-vectors must be
;; stored as ONE event (the old shape-inspection heuristic would have split it).
(p/defactor-persistent vector-event-actor
  :persistence-id (fn [args] (str "vecev-" (:id args)))

  (init [_] {:events []})

  (command [:record pairs]
    ;; `pairs` is e.g. [[1 2] [3 4]] — one event, not two.
    (p/persist [:recorded pairs]))

  (command :get
    (.reply this (:events state))
    nil)

  (event [:recorded pairs]
    (update state :events conj pairs)))

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

  (on-recovery-complete [_this]
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

;; B13: snapshot-every 2, keep 1, delete subsumed events — used to make the
;; snapshot cadence observable across a restart via the resulting journal
;; truncation point (see snapshot-cadence-survives-recovery below).
(p/defactor-persistent cadence-counter
  :persistence-id (fn [args] (str "cadence-" (:id args)))

  (init [_] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command :get
    (p/reply (:count state)))

  (event [:incremented]
    (update state :count inc))

  (snapshot-every 2 1)
  (delete-events-on-snapshot))

;; Stress actor: mixes single (persist) and multi-event (persist-all) commands
;; and records every value in arrival order, so a recovered :log reveals any
;; reordering, loss or duplication. Aggressive retention (snapshot every 5, keep
;; 2, delete subsumed events) means recovery must lean on snapshots + a truncated
;; journal — exactly the interaction to stress against persist-all's sequential,
;; in-order persistence.
(p/defactor-persistent stress-actor
  :persistence-id (fn [args] (str "stress-" (:id args)))

  (init [_] {:log [] :sum 0 :n 0})

  (command [:batch values]
    (p/persist-all (mapv (fn [v] [:v v]) values)))

  (command [:one v]
    (p/persist [:v v]))

  (command :get
    (.reply this state)
    nil)

  (event [:v v]
    (-> state
        (update :log conj v)
        (update :sum + v)
        (update :n inc)))

  (snapshot-every 5 2)
  (delete-events-on-snapshot))

(defn- stress-plan
  "Build [commands expected-log] for the stress actor: `steps` operations mixing
   single events (every 3rd op) and persist-all batches of 1–4 events (cycling), with
   globally increasing values so the expected log is simply (range total) in order."
  [steps]
  (loop [i 0, v 0, cmds [], expected []]
    (if (= i steps)
      [cmds expected]
      (if (zero? (mod i 3))
        (recur (inc i) (inc v) (conj cmds [:one v]) (conj expected v))
        (let [size (inc (mod i 4))          ; batch of 1..4 events
              vals (vec (range v (+ v size)))]
          (recur (inc i) (+ v size) (conj cmds [:batch vals]) (into expected vals)))))))

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

(deftest persistent-actor-unmatched-command-goes-unhandled
  ;; B15: an unmatched command must not be silently dropped — it routes to
  ;; Pekko's unhandled() and shows up as an UnhandledMessage, mirroring
  ;; defactor's B3 fix. The actor survives and keeps handling known commands.
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys picky-persistent {:id id})
        received (atom [])]
    (try
      (es/subscribe-unhandled sys (fn [m] (swap! received conj m)))
      (is (eventually (do (core/! actor :mystery)
                          (some (fn [m] (= :mystery (:message m))) @received))))
      (core/! actor :known)
      (is (eventually (= 1 (core/<! actor :get 3000))))
      (finally
        (terminate-system sys)))))

(deftest persistent-actor-user-catch-all-wins
  ;; A user-supplied catch-all command clause still wins over the default
  ;; unhandled() fallback — unmatched commands reach it instead.
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        actor (p/spawn sys catch-all-persistent {:id id})]
    (try
      (core/! actor :known)
      (core/! actor :anything-else)
      (is (eventually (= [:known :anything-else] (core/<! actor :get 3000))))
      (finally
        (terminate-system sys)))))

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

(deftest persistent-actor-single-vector-of-vectors-event
  ;; A single event that is a vector whose first element is itself a vector must
  ;; be persisted as ONE event, and survive recovery intact — proving persist no
  ;; longer splits by shape (only persist-all persists multiple events).
  (let [id (unique-id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys vector-event-actor {:id id})]
          (core/! actor [:record [[1 2] [3 4]]])
          ;; One event recorded, held whole (not split into [1 2] and [3 4]).
          (is (eventually (= [[[1 2] [3 4]]] (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))
    ;; Recover: the single compound event replays unchanged.
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys vector-event-actor {:id id})]
          (is (eventually (= [[[1 2] [3 4]]] (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))))

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

(deftest persist-all-stress-ordering-under-retention
  ;; Stress persist-all's sequential, in-order persistence against aggressive
  ;; snapshot retention (every 5, keep 2, delete subsumed events): drive many
  ;; single + batch commands whose values increase monotonically, then verify the
  ;; live and RECOVERED logs are exactly (range total) — no reorder, loss or dup —
  ;; even though most of the journal has been snapshotted and truncated.
  (let [id (unique-id)
        [cmds expected] (stress-plan 60)          ; 120 events, ~24 snapshots
        total (count expected)
        expected-sum (reduce + expected)]
    (is (> total 100) "sanity: the plan should produce a large event count")
    ;; Run 1 — drive every command (tell; FIFO + persist stashing preserve order),
    ;; then read the live state back.
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys stress-actor {:id id})]
          (doseq [c cmds] (core/! actor c))
          (let [st (core/<! actor :get 10000)]
            (is (= expected (:log st)) "live log is in exact arrival order")
            (is (= expected-sum (:sum st)))
            (is (= total (:n st)) "no events lost or duplicated")))
        (finally
          (terminate-system sys))))
    ;; Run 2 — recover in a fresh system. Retention deleted most events, so this
    ;; reconstructs from the newest snapshot + the surviving tail; the result must
    ;; still be identical.
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys stress-actor {:id id})
              st (core/<! actor :get 10000)]
          (is (= expected (:log st)) "recovered log matches, order preserved")
          (is (= expected-sum (:sum st)))
          (is (= total (:n st)) "recovery neither lost nor duplicated events")
          ;; A further command after recovery appends correctly (sequence continues).
          (core/! actor [:one total])
          (is (eventually (= (conj expected total) (:log (core/<! actor :get 5000))))))
        (finally
          (terminate-system sys))))))

(deftest persist-all-large-batch-crosses-snapshot-boundaries
  ;; A single persist-all bigger than snapshot-every (23 events, snapshot every 5)
  ;; fires several snapshots from WITHIN one command's nested persist callbacks —
  ;; the hardest case for sequential persistence. State and recovery must be exact
  ;; and in order.
  (let [id (unique-id)
        vals (vec (range 23))]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys stress-actor {:id id})]
          (core/! actor [:batch vals])
          (let [st (core/<! actor :get 10000)]
            (is (= vals (:log st)) "single large batch applied in order")
            (is (= 23 (:n st)))
            (is (= (reduce + vals) (:sum st)))))
        (finally
          (terminate-system sys))))
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys stress-actor {:id id})
              st (core/<! actor :get 10000)]
          (is (= vals (:log st)) "recovers the large batch in order after retention")
          (is (= 23 (:n st))))
        (finally
          (terminate-system sys))))))

(deftest persistent-actor-recovery-callback
  (reset! recovery-completed false)
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        ;; Spawning is the trigger: recovery runs on start and fires the callback.
        _actor (p/spawn sys recovery-callback-actor {:id id})]
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

(deftest snapshot-cadence-survives-recovery
  ;; B13: eventsSinceSnapshot must not reset to 0 on restart. With
  ;; snapshot-every 2, persist 1 event, restart (recovery replays that 1 event
  ;; with no snapshot offer yet), then persist 3 more. If cadence carries over
  ;; correctly, the two snapshot boundaries land at seq 2 and seq 4; a counter
  ;; that forgot the pre-restart event would instead fire at seq 3.
  ;; (snapshot-every 2 1) + delete-events-on-snapshot makes the boundary
  ;; observable: querying the journal afterwards reveals which events survived
  ;; truncation — [3 4] proves the correct boundary, [2 3 4] the buggy one.
  (let [id (unique-id)
        pid (str "cadence-" id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys cadence-counter {:id id})]
          (core/! actor :increment)
          (is (eventually (= 1 (core/<! actor :get 3000)))))
        (finally
          (terminate-system sys))))
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys cadence-counter {:id id})]
          (core/! actor :increment)
          (core/! actor :increment)
          (core/! actor :increment)
          (is (eventually (= 4 (core/<! actor :get 3000)))))
        (let [j (q/read-journal sys)
              mat (s/materializer sys)
              remaining (eventually
                         (let [es (vec (s/await-completion
                                        (s/run-to-seq
                                         (q/current-events-by-persistence-id j pid) mat)
                                        5000))]
                           (when (= 2 (count es)) es)))]
          (is (= [3 4] (mapv :sequence-nr remaining))
              "snapshot cadence carried the pre-restart event forward: boundaries at seq 2 and 4, not 3"))
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
