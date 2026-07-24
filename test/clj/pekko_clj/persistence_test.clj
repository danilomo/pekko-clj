(ns pekko-clj.persistence-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.persistence :as p]
            [pekko-clj.persistence.query :as q]
            [pekko-clj.stream :as s]
            [pekko-clj.core :as core]
            [pekko-clj.event-stream :as es]
            [pekko-clj.supervision :as sup]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.persistence Recovery SnapshotSelectionCriteria]
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

;; H13: a persistent command handler that sends to another actor via core/!.
;; The reply path only works if ! resolves the sender to this entity (via
;; *current-self*); *current-actor* is unbound inside a persistent body.
(p/defactor-persistent h13-persistent-sender
  :persistence-id (fn [args] (str "h13-sender-" (:id args)))

  (init [args] {:probe (:probe args)})

  (command [:ping-probe]
    (core/! (:probe state) :hi)
    nil))

(defn- sender-recording-probe
  "A classic actor that records the sender of the first message it receives."
  [sys got-sender]
  (core/new-actor sys
                  {:function (fn [this _msg]
                               (binding [core/*current-actor* this]
                                 (deliver got-sender (core/sender)))
                               nil)
                   :state nil}))

;; H17: a persistent actor spawned as a *child* of a classic defactor via
;; (persistence/spawn (core/context) ...).
(p/defactor-persistent h17-child
  :persistence-id (fn [args] (str "h17-child-" (:id args)))
  (init [args] {:v (:v args)})
  (command :get (p/reply (:v state)) nil))

(core/defactor h17-parent
  (init [_] {})
  (handle [:spawn-child id v]
    (core/reply (p/spawn (core/context) h17-child {:id id :v v}))))

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

;; B12: a persist-all batch must reach the journal as ONE atomic write. The
;; batches here carry an event the journal cannot store (a bare Object is not
;; java.io.Serializable), so the write is rejected — with an atomic write the
;; whole batch is rejected together, leaving nothing behind.
(p/defactor-persistent atomic-batch-actor
  :persistence-id (fn [args] (str "atomic-" (:id args)))

  (init [_] {:log []})

  (command [:batch values]
    (p/persist-all (mapv (fn [v] [:v v]) values)))

  (command :get
    (.reply this (:log state))
    nil)

  (event [:v v]
    (update state :log conj v)))

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

(deftest persist-all-batch-is-atomic
  ;; B12: persist-all must hand the journal ONE atomic write covering the whole
  ;; batch, not one write per event. The batch below carries an event the journal
  ;; cannot store (a bare Object is not java.io.Serializable), so the write is
  ;; rejected — and being one write, it is rejected as a unit: not one event of
  ;; the batch is applied. Persisting the batch as nested single `persist` calls
  ;; instead wrote and applied every event *before* the bad one, leaving the
  ;; half-applied command persist-all exists to prevent.
  ;;
  ;; The assertion is on actor state, not on journal contents: the LevelDB plugin
  ;; serializes an AtomicWrite's events straight into a shared LevelDB write batch
  ;; that it commits even when a later event of that same atomic write fails to
  ;; serialize, so the on-disk effect of a *rejected* write is plugin-specific and
  ;; not a fair probe of our contract. What the batching does control — and what
  ;; the actor and every recovery from a well-behaved journal see — is whether the
  ;; command was applied in part.
  (let [id (unique-id)
        sys (create-test-system "persistence-test")]
    (try
      (let [actor (p/spawn sys atomic-batch-actor {:id id})]
        ;; Control: a storable batch lands whole.
        (core/! actor [:batch [1 2]])
        (is (eventually (= [1 2] (core/<! actor :get 3000))))
        ;; The rejected batch, then a good one to prove the actor is still alive
        ;; and that we are not just reading a stale reply.
        (core/! actor [:batch [3 (Object.) 4]])
        (core/! actor [:batch [5 6]])
        (is (eventually (= [1 2 5 6] (core/<! actor :get 3000)))
            "no event of the rejected batch was applied — all or nothing"))
      (finally
        (terminate-system sys)))))

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

(deftest defactor-persistent-rejects-unknown-clause-head
  ;; H8: an unknown clause head (a typo) used to be silently discarded by the
  ;; parse-*'s (filter #(= 'head (first %)) clauses) pattern.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown clause"
        (try
          (macroexpand-1 '(pekko-clj.persistence/defactor-persistent bad-clause-head
                            :persistence-id (fn [_] "x")
                            (init [_] {})
                            (command :x (p/persist [:e]))
                            (event [:e] state)
                            (tagged [event] #{"x"})))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

(deftest defactor-persistent-rejects-duplicate-tagger-clause
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only one `tagger`"
        (try
          (macroexpand-1 '(pekko-clj.persistence/defactor-persistent dup-tagger
                            :persistence-id (fn [_] "x")
                            (init [_] {})
                            (command :x (p/persist [:e]))
                            (event [:e] state)
                            (tagger [event] #{"a"})
                            (tagger [event] #{"b"})))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

(deftest defactor-persistent-rejects-duplicate-persistence-id
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only one `:persistence-id`"
        (try
          (macroexpand-1 '(pekko-clj.persistence/defactor-persistent dup-pid
                            :persistence-id (fn [_] "x")
                            :persistence-id (fn [_] "y")
                            (init [_] {})
                            (command :x (p/persist [:e]))
                            (event [:e] state)))
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

;; ---------------------------------------------------------------------------
;; N11: persistence depth — lifecycle, async persists, plugins, recovery
;; ---------------------------------------------------------------------------

(def stopped-actors (atom []))
(def child-failures (atom 0))

(core/defactor fragile-child
  "Child of a persistent actor, used to observe its supervisor strategy."
  (init [_] {:n 0})
  (handle [:bump] (update state :n inc))
  (handle [:boom] (do (swap! child-failures inc)
                      (throw (RuntimeException. "child boom"))))
  (handle [:get] (core/reply (:n state))))

(p/defactor-persistent lifecycle-actor
  "Exercises the on-stop, supervision and timer additions."
  :persistence-id (fn [args] (str "lifecycle-" (:id args)))

  (init [args] {:id (:id args) :ticks 0 :child nil})

  ;; Children of this actor are resumed on failure instead of restarted, so a
  ;; failing child keeps the state it had.
  (supervision (sup/one-for-one sup/resume-decider))

  (on-stop (swap! stopped-actors conj (:id state)))

  (command :spawn-child
    (p/reply (core/new-actor (p/context) ((:make-props fragile-child) nil)))
    nil)

  (command [:start-ticking ms]
    (p/start-timer :tick (java.time.Duration/ofMillis ms) :tick)
    (p/reply :ticking)
    nil)
  ;; H14: the timer fns accept a plain ms number, not only a Duration.
  (command [:start-ticking-ms ms]
    (p/start-timer :tick ms :tick)
    (p/reply :ticking)
    nil)
  (command [:start-once-ms ms]
    (p/start-single-timer :once ms :tick)
    (p/reply :once-set)
    nil)
  (command :tick
    (p/persist [:ticked]))
  (command :stop-ticking
    (p/cancel-timer :tick)
    (p/reply (p/timer-active? :tick))
    nil)

  (command :get-ticks (p/reply (:ticks state)) nil)
  (command :who (p/reply (str (.path (p/self)))) nil)

  (event [:ticked] (update state :ticks inc)))

(deftest persistent-on-stop-clause-runs
  (let [sys (create-test-system "persistence-test")
        id (unique-id)]
    (try
      (reset! stopped-actors [])
      (let [actor (p/spawn sys lifecycle-actor {:id id})]
        (is (eventually (= 0 (core/<! actor :get-ticks 3000))))
        (core/poison-pill actor)
        (is (eventually (= [id] @stopped-actors))
            "on-stop ran with `state` bound"))
      (finally (terminate-system sys)))))

(deftest persistent-supervision-clause-supervises-children
  (let [sys (create-test-system "persistence-test")]
    (try
      (reset! child-failures 0)
      (let [actor (p/spawn sys lifecycle-actor {:id (unique-id)})
            child (core/<! actor :spawn-child 3000)]
        (is (some? child))
        (core/! child [:bump])
        (core/! child [:bump])
        (is (eventually (= 2 (core/<! child [:get] 3000))))
        ;; :resume keeps the child's state; the default (restart) would zero it.
        (core/! child [:boom])
        (is (eventually (= 1 @child-failures)))
        (is (eventually (= 2 (core/<! child [:get] 3000)))
            "the (supervision (resume-decider)) clause reached the child"))
      (finally (terminate-system sys)))))

(deftest persistent-timers-work
  (let [sys (create-test-system "persistence-test")]
    (try
      (let [actor (p/spawn sys lifecycle-actor {:id (unique-id)})]
        (is (= :ticking (core/<! actor [:start-ticking 100] 3000)))
        ;; Each tick persists an event, so the ticks are journalled, not just counted.
        (is (eventually (<= 3 (or (core/<! actor :get-ticks 3000) 0))))
        (is (false? (core/<! actor :stop-ticking 3000))
            "cancel-timer removed the timer"))
      (finally (terminate-system sys)))))

(deftest persistent-timers-accept-millis
  ;; H14: p/start-timer and p/start-single-timer take a plain ms number, not only
  ;; a java.time.Duration (previously a ClassCastException on the Java hint).
  (let [sys (create-test-system "persistence-test")]
    (try
      (let [periodic (p/spawn sys lifecycle-actor {:id (unique-id)})]
        (is (= :ticking (core/<! periodic [:start-ticking-ms 30] 3000)))
        (is (eventually (<= 3 (or (core/<! periodic :get-ticks 3000) 0)))
            "periodic timer (ms) persisted several ticks")
        (core/<! periodic :stop-ticking 3000))
      (let [once (p/spawn sys lifecycle-actor {:id (unique-id)})]
        (is (= :once-set (core/<! once [:start-once-ms 30] 3000)))
        (is (eventually (<= 1 (or (core/<! once :get-ticks 3000) 0)))
            "single timer (ms) fired once"))
      (finally (terminate-system sys)))))

;; --- persist-async / defer / then -----------------------------------------

(p/defactor-persistent async-actor
  "Uses persist-async, defer and then."
  :persistence-id (fn [args] (str "async-" (:id args)))

  (init [_] {:events [] :deferred []})

  (command [:observe v]
    (p/persist-async [:observed v]))

  (command [:observe-many vs]
    (p/persist-all-async (mapv (fn [v] [:observed v]) vs)))

  ;; persist, then reply only once the write has completed
  (command [:record v]
    (p/then (p/persist [:observed v])
            (p/defer [:written v])))

  (command [:written v]
    (p/reply {:written v :events (:events state)})
    nil)

  (command :get (p/reply (:events state)) nil)

  (event [:observed v] (update state :events conj v)))

(deftest persist-async-applies-events
  (let [sys (create-test-system "persistence-test")]
    (try
      (let [actor (p/spawn sys async-actor {:id (unique-id)})]
        (core/! actor [:observe 1])
        (core/! actor [:observe 2])
        (core/! actor [:observe-many [3 4]])
        (is (eventually (= [1 2 3 4] (core/<! actor :get 3000)))
            "async writes still reach the event handler, in order"))
      (finally (terminate-system sys)))))

(deftest persist-async-events-recover
  ;; The point of the marker is that these are real journal writes, not a
  ;; fire-and-forget side channel.
  (let [id (unique-id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys async-actor {:id id})]
          (core/! actor [:observe :a])
          (core/! actor [:observe :b])
          (is (eventually (= [:a :b] (core/<! actor :get 3000)))))
        (finally (terminate-system sys))))
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys async-actor {:id id})]
          (is (eventually (= [:a :b] (core/<! actor :get 3000)))
              "replayed from the journal after a restart"))
        (finally (terminate-system sys))))))

(deftest defer-runs-after-the-write
  (let [sys (create-test-system "persistence-test")]
    (try
      (let [actor (p/spawn sys async-actor {:id (unique-id)})
            reply (core/<! actor [:record :x] 3000)]
        (is (= :x (:written reply)))
        (is (= [:x] (:events reply))
            "the deferred command ran after the event was applied, not before"))
      (finally (terminate-system sys)))))

(deftest deferred-values-are-not-journalled
  ;; `defer` sequences a side effect; it must not add anything to the journal.
  (let [id (unique-id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys async-actor {:id id})]
          (is (= :x (:written (core/<! actor [:record :x] 3000)))))
        (finally (terminate-system sys))))
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys async-actor {:id id})]
          (is (eventually (= [:x] (core/<! actor :get 3000)))
              "one event replayed — the deferred value was never written"))
        (finally (terminate-system sys))))))

;; --- per-actor plugin ids --------------------------------------------------

(p/defactor-persistent inmem-actor
  "Writes to the in-memory journal instead of the configured LevelDB one."
  :persistence-id (fn [args] (str "inmem-" (:id args)))
  (journal-plugin-id "pekko.persistence.journal.inmem")

  (init [_] {:n 0})
  (command :inc (p/persist [:inced]))
  (command :get (p/reply (:n state)) nil)
  (event [:inced] (update state :n inc)))

(deftest journal-plugin-id-clause-redirects-writes
  (let [sys (create-test-system "persistence-test")
        id (unique-id)
        pid (str "inmem-" id)]
    (try
      (let [actor (p/spawn sys inmem-actor {:id id})]
        (core/! actor :inc)
        (core/! actor :inc)
        (is (eventually (= 2 (core/<! actor :get 3000))))
        ;; The default (LevelDB) journal — which the query journal reads — has
        ;; nothing for this persistence id, because the writes went to inmem.
        (let [j (q/read-journal sys)
              mat (s/materializer sys)
              in-leveldb (vec (s/await-completion
                               (s/run-to-seq (q/current-events-by-persistence-id j pid) mat)
                               5000))]
          (is (empty? in-leveldb)
              "the events are in the inmem journal, not the configured default")))
      (finally (terminate-system sys)))))

;; --- recovery customization ------------------------------------------------

(p/defactor-persistent no-recovery-actor
  "Write-only: never replays its journal on start."
  :persistence-id (fn [args] (str "no-recovery-" (:id args)))
  (recovery :none)

  (init [_] {:n 0})
  (command :inc (p/persist [:inced]))
  (command :get (p/reply (:n state)) nil)
  (event [:inced] (update state :n inc)))

(p/defactor-persistent bounded-recovery-actor
  "Replays at most two events."
  :persistence-id (fn [args] (str "bounded-recovery-" (:id args)))
  (recovery {:replay-max 2})

  (init [_] {:n 0})
  (command :inc (p/persist [:inced]))
  (command :get (p/reply (:n state)) nil)
  (event [:inced] (update state :n inc)))

(deftest recovery-none-skips-replay
  (let [id (unique-id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys no-recovery-actor {:id id})]
          (core/! actor :inc)
          (core/! actor :inc)
          (is (eventually (= 2 (core/<! actor :get 3000)))))
        (finally (terminate-system sys))))
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys no-recovery-actor {:id id})]
          (is (eventually (= 0 (core/<! actor :get 3000)))
              "(recovery :none) starts from init, ignoring the journal"))
        (finally (terminate-system sys))))))

(deftest recovery-replay-max-bounds-replay
  (let [id (unique-id)]
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys bounded-recovery-actor {:id id})]
          (dotimes [_ 5] (core/! actor :inc))
          (is (eventually (= 5 (core/<! actor :get 3000)))))
        (finally (terminate-system sys))))
    (let [sys (create-test-system "persistence-test")]
      (try
        (let [actor (p/spawn sys bounded-recovery-actor {:id id})]
          (is (eventually (= 2 (core/<! actor :get 3000)))
              "only :replay-max events were replayed"))
        (finally (terminate-system sys))))))

(deftest recovery-settings-shapes
  (is (= (Recovery/none) (p/recovery-settings :none)))
  (is (= (Recovery/create) (p/recovery-settings :default)))
  (is (= 7 (.replayMax (p/recovery-settings {:replay-max 7}))))
  (is (= 3 (.toSequenceNr (p/recovery-settings {:to-sequence-nr 3}))))
  (is (= (SnapshotSelectionCriteria/none)
         (.fromSnapshot (p/recovery-settings {:from-snapshot :none}))))
  (is (= 9 (.maxSequenceNr (.fromSnapshot
                            (p/recovery-settings {:from-snapshot {:max-sequence-nr 9}})))))
  (is (thrown-with-msg? IllegalArgumentException #"recovery must be"
        (p/recovery-settings :bogus)))
  (is (thrown-with-msg? IllegalArgumentException #":from-snapshot must be"
        (p/recovery-settings {:from-snapshot :bogus}))))

(deftest defactor-persistent-rejects-duplicate-lifecycle-clause
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only one `on-stop`"
        (try
          (macroexpand-1 '(pekko-clj.persistence/defactor-persistent dup-on-stop
                            :persistence-id (fn [_] "x")
                            (init [_] {})
                            (command :x (p/persist [:e]))
                            (event [:e] state)
                            (on-stop nil)
                            (on-stop nil)))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

;; ---------------------------------------------------------------------------
;; H15: friendly errors
;; ---------------------------------------------------------------------------

(deftest defactor-persistent-requires-persistence-id
  ;; A missing :persistence-id used to NPE at runtime inside :make-props; now it
  ;; is rejected at macro-expansion, naming the clause.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":persistence-id"
        (try
          (macroexpand-1 '(pekko-clj.persistence/defactor-persistent no-id
                            (init [_] {})
                            (command :x (p/persist [:e]))
                            (event [:e] state)))
          (catch clojure.lang.Compiler$CompilerException e
            (throw (.getCause e)))))))

(deftest persistence-out-of-context-calls-name-the-fn
  ;; Accessor/timer fns called outside a persistent handler throw a friendly
  ;; IllegalStateException naming the fn, not a bare NPE.
  (is (thrown-with-msg? IllegalStateException #"pekko-clj\.persistence/self" (p/self)))
  (is (thrown-with-msg? IllegalStateException #"pekko-clj\.persistence/context" (p/context)))
  (is (thrown-with-msg? IllegalStateException #"pekko-clj\.persistence/start-timer"
        (p/start-timer :k 10 :m))))

;; ---------------------------------------------------------------------------
;; H13: core/! resolves the sender inside a persistent command handler
;; ---------------------------------------------------------------------------

(deftest persistent-command-tell-uses-entity-as-sender
  ;; core/! inside a persistent command body must send with the entity as sender
  ;; (so the recipient can reply), not noSender. *current-actor* is unbound in a
  ;; persistent body; the fix resolves the sender via *current-self*.
  (let [sys (create-test-system "h13-persistent-sender")]
    (try
      (let [got-sender (promise)
            probe (sender-recording-probe sys got-sender)
            entity (p/spawn sys h13-persistent-sender {:id (unique-id) :probe probe})]
        (core/! entity [:ping-probe])
        (is (= entity (deref got-sender 5000 :timeout))
            "the probe saw the persistent entity as sender (noSender before the fix)"))
      (finally
        (terminate-system sys)))))

(deftest top-level-tell-still-sends-as-no-sender
  ;; Guard: outside any actor, core/! must still send as noSender — Pekko then
  ;; reports deadLetters as the sender. The *current-self* fallback must not leak.
  (let [sys (create-test-system "h13-top-level")]
    (try
      (let [got-sender (promise)
            probe (sender-recording-probe sys got-sender)]
        (core/! probe :hi)
        (is (= (.deadLetters sys) (deref got-sender 5000 :timeout))
            "a top-level send has no sender"))
      (finally
        (terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; H17: persistent actors spawn as children (ActorRefFactory)
;; ---------------------------------------------------------------------------

(deftest persistent-actor-spawns-as-child-via-context
  ;; persistence/spawn now takes an ActorRefFactory, so a persistent actor can be
  ;; spawned as a child of a classic actor via (core/context) — it recovers,
  ;; replies, and a parent stop tears it down.
  (let [sys (create-test-system "h17-parent")]
    (try
      (let [parent (core/spawn sys h17-parent nil)
            child (core/<! parent [:spawn-child (unique-id) 42] 3000)]
        (is (some? child))
        (is (= 42 (core/<! child :get 3000)) "the persistent child recovered and replied")
        (core/poison-pill parent)
        (is (ts/stopped-within? sys child 5000) "parent stop tore down the child"))
      (finally
        (terminate-system sys)))))
