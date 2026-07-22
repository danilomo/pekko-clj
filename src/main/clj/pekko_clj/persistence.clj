(ns pekko-clj.persistence
  "Event sourcing and persistence support for pekko-clj.

   Provides persistent actors that:
   - Store events in a journal
   - Rebuild state by replaying events
   - Support snapshots for faster recovery, with optional retention
   - Tag events so they can be read back with pekko-clj.persistence.query

   Example:
     (defactor-persistent shopping-cart
       :persistence-id (fn [args] (str \"cart-\" (:id args)))

       (init [args] {:items []})

       (command [:add-item item]
         (persist [:item-added item]))

       (command [:remove-item item-id]
         (persist [:item-removed item-id]))

       (event [:item-added item]
         (update state :items conj item))

       (event [:item-removed item-id]
         (update state :items #(remove (fn [i] (= (:id i) item-id)) %)))

       ;; Index every event under \"cart\" for (query/events-by-tag j \"cart\")
       (tagger [event] #{\"cart\"})

       ;; Snapshot every 100 events, keeping the 2 most recent
       (snapshot-every 100 2))"
  (:require [clojure.core.match :refer [match]])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.persistence SnapshotSelectionCriteria]
           [pekko_clj.actor CljPersistentActor PersistAll]))

(def ^:dynamic *current-persistent-actor*
  "Bound to the current CljPersistentActor during command handling.
   Used by (reply ...). Mirrors pekko-clj.core/*current-actor*."
  nil)

;; ---------------------------------------------------------------------------
;; Persistent Actor Definition
;; ---------------------------------------------------------------------------

(defn- parse-persistence-id [clauses]
  ;; Find :persistence-id keyword and get the value that follows it
  (loop [remaining clauses]
    (when (seq remaining)
      (let [item (first remaining)]
        (if (= :persistence-id item)
          (second remaining)
          (recur (rest remaining)))))))

(defn- parse-init [clauses]
  (let [init-clause (first (filter #(and (seq? %) (= 'init (first %))) clauses))]
    (when init-clause
      (let [[_ bindings & body] init-clause]
        `(fn ~bindings ~@body)))))

(defn- parse-commands [clauses]
  (filter #(and (seq? %) (= 'command (first %))) clauses))

(defn- parse-events [clauses]
  (filter #(and (seq? %) (= 'event (first %))) clauses))

(defn- parse-snapshot-every
  "Returns [every keep-n] for a (snapshot-every n) / (snapshot-every n keep-n)
   clause; keep-n is nil when retention is not requested."
  [clauses]
  (let [snapshot-clause (first (filter #(and (seq? %) (= 'snapshot-every (first %))) clauses))]
    (when snapshot-clause
      [(second snapshot-clause) (nth snapshot-clause 2 nil)])))

(defn- parse-delete-events-on-snapshot [clauses]
  (boolean (some #(and (seq? %) (= 'delete-events-on-snapshot (first %))) clauses)))

(defn- parse-tagger [clauses]
  (let [tagger-clause (first (filter #(and (seq? %) (= 'tagger (first %))) clauses))]
    (when tagger-clause
      (let [[_ bindings & body] tagger-clause]
        `(fn ~bindings ~@body)))))

(defn- parse-on-recovery-complete [clauses]
  (let [recovery-clause (first (filter #(and (seq? %) (= 'on-recovery-complete (first %))) clauses))]
    (when recovery-clause
      (let [[_ bindings & body] recovery-clause]
        `(fn ~bindings ~@body)))))

(defn- build-command-handler [commands]
  (let [this-sym (gensym "this")
        command-sym (gensym "command")
        match-clauses (mapcat (fn [[_ pattern & body]]
                                [pattern `(do ~@body)])
                              commands)]
    `(fn [~this-sym ~command-sym]
       (binding [*current-persistent-actor* ~this-sym]
         (let [~'this ~this-sym
               ~'state @~this-sym]
           (match ~command-sym
             ~@match-clauses
             :else nil))))))

(defn- build-event-handler [events]
  (let [state-sym (gensym "state")
        event-sym (gensym "event")
        match-clauses (mapcat (fn [[_ pattern & body]]
                                [pattern `(do ~@body)])
                              events)]
    `(fn [~state-sym ~event-sym]
       (let [~'state ~state-sym]
         (match ~event-sym
           ~@match-clauses
           :else ~'state)))))

(defmacro defactor-persistent
  "Define a persistent actor with event sourcing.

   Clauses:
   - :persistence-id fn  - Function (fn [args] -> string) returning unique ID
   - (init [args] ...)   - Initialize state from args
   - (command pattern & body) - Handle commands, return events via (persist ...)
   - (event pattern & body)   - Apply events to state, return new state
   - (tagger [event] ...)     - Tags (a collection of strings) to index the event
                                under, queryable via events-by-tag; nil/empty for
                                none. Tags never reach the event handler.
   - (snapshot-every n)       - Take snapshot every n events
   - (snapshot-every n keep)  - ... and keep only the `keep` most recent snapshots
   - (delete-events-on-snapshot) - Also delete the events those dropped snapshots
                                cover. Requires (snapshot-every n keep). Events are
                                gone for good: only use it when nothing replays this
                                actor's journal (no events-by-tag consumer, no audit).
   - (on-recovery-complete [this] ...) - Called when recovery finishes

   In command bodies, `this` (the actor) and `state` (its current value) are
   reserved anaphors:
   - @this / state   - Current state
   - (reply msg)     - Reply to sender
   - (persist event) - Return a single event to persist
   - (persist-all events) - Return several events to persist, in order
   Do not shadow `this`/`state` in a command pattern — that throws at
   macro-expansion.

   Example:
     (defactor-persistent counter
       :persistence-id (fn [args] (str \"counter-\" (:id args)))

       (init [args] {:count 0})

       (command :increment
         (persist [:incremented]))

       (command [:add n]
         (persist [:added n]))

       (command :get
         (reply (:count state))
         nil)  ; no event to persist

       (event [:incremented]
         (update state :count inc))

       (event [:added n]
         (update state :count + n))

       (tagger [event] #{\"counter\"})

       (snapshot-every 50 2))"
  [name & clauses]
  (let [docstring (when (string? (first clauses)) (first clauses))
        clauses   (if docstring (rest clauses) clauses)
        persistence-id-fn (parse-persistence-id clauses)
        init-fn (parse-init clauses)
        commands (parse-commands clauses)
        events (parse-events clauses)
        [snapshot-every keep-snapshots] (parse-snapshot-every clauses)
        delete-events-on-snapshot (parse-delete-events-on-snapshot clauses)
        tagger-fn (parse-tagger clauses)
        on-recovery-complete (parse-on-recovery-complete clauses)
        command-handler (build-command-handler commands)
        event-handler (build-event-handler events)]
    ;; Reserved-anaphor guard: `this`/`state` are auto-bound in command bodies.
    (doseq [cmd commands]
      (let [pattern (second cmd)]
        (when (some #{'this 'state} (tree-seq coll? seq pattern))
          (throw (ex-info (str "defactor-persistent " name ": `this`/`state` are reserved "
                               "bindings in command bodies — rename them in the command pattern")
                          {:pattern pattern})))))
    ;; Retention needs a snapshot cadence and a count of snapshots to keep;
    ;; without both, deleting events would drop history nothing can replace.
    (when (and delete-events-on-snapshot (not (and snapshot-every keep-snapshots)))
      (throw (ex-info (str "defactor-persistent " name ": (delete-events-on-snapshot) requires "
                           "(snapshot-every n keep-n)")
                      {:snapshot-every snapshot-every :keep-snapshots keep-snapshots})))
    ;; Bind each generated form to a local exactly once (no double splice), then
    ;; reference the locals from both the actor-def map and its :make-props.
    `(def ~(if docstring (vary-meta name assoc :doc docstring) name)
       (let [command-handler#      ~command-handler
             event-handler#        ~event-handler
             init-fn#              ~init-fn
             persistence-id-fn#    ~persistence-id-fn
             snapshot-every#       ~snapshot-every
             keep-snapshots#       ~keep-snapshots
             delete-events#        ~delete-events-on-snapshot
             tagger#               ~tagger-fn
             on-recovery-complete# ~on-recovery-complete]
         {:type :persistent-actor
          :persistence-id-fn persistence-id-fn#
          :init-fn init-fn#
          :command-handler command-handler#
          :event-handler event-handler#
          :snapshot-every snapshot-every#
          :keep-snapshots keep-snapshots#
          :delete-events-on-snapshot delete-events#
          :tagger tagger#
          :on-recovery-complete on-recovery-complete#
          :make-props (fn [args#]
                        (let [initial-state# (when init-fn# (init-fn# args#))
                              persistence-id# (persistence-id-fn# args#)]
                          {:state initial-state#
                           :persistence-id persistence-id#
                           :command-handler command-handler#
                           :event-handler event-handler#
                           :snapshot-every snapshot-every#
                           :keep-snapshots keep-snapshots#
                           :delete-events-on-snapshot delete-events#
                           :tagger tagger#
                           :on-recovery-complete on-recovery-complete#}))}))))

;; ---------------------------------------------------------------------------
;; Spawning Persistent Actors
;; ---------------------------------------------------------------------------

(defn spawn
  "Spawn a persistent actor.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition from defactor-persistent
   - args: Arguments passed to init and persistence-id functions

   Returns an ActorRef."
  [^ActorSystem system actor-def args]
  (let [props-map ((:make-props actor-def) args)
        props (CljPersistentActor/create props-map)]
    (.actorOf system props)))

(defn spawn-named
  "Spawn a persistent actor with a specific name.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition from defactor-persistent
   - args: Arguments passed to init and persistence-id functions
   - name: Actor name

   Returns an ActorRef."
  [^ActorSystem system actor-def args name]
  (let [props-map ((:make-props actor-def) args)
        props (CljPersistentActor/create props-map)]
    (.actorOf system props name)))

;; ---------------------------------------------------------------------------
;; Command Helpers (for use in command handlers)
;; ---------------------------------------------------------------------------

(defn persist
  "Return a single event to be persisted. Use this in a command handler; the event
   may be any shape (keyword, vector, map, …) and is stored as one event. For more
   than one event from a single command, use `persist-all`.

   Example:
     (persist [:item-added item])"
  [event]
  event)

(defn persist-all
  "Return several events to be persisted, in order, from a single command. Takes a
   collection of events and wraps it in a marker the persistent actor recognizes,
   so it is unambiguous which vectors are separate events. (A plain collection
   returned from `persist` is always a single event, whatever its shape.)

   Example:
     (persist-all [[:item-added item] [:inventory-updated]])"
  [events]
  (PersistAll/of events))

(defn reply
  "Reply to the sender of the current command. Call inside a command handler,
   where defactor-persistent binds the current persistent actor. Returns nil, so
   a command whose last form is (reply ...) persists no event."
  [msg]
  (.reply ^CljPersistentActor *current-persistent-actor* msg)
  nil)

;; ---------------------------------------------------------------------------
;; Actor State Access
;; ---------------------------------------------------------------------------

(defn recovering?
  "Check if the actor is currently recovering (replaying events)."
  [actor]
  (.isRecovering ^CljPersistentActor actor))

;; ---------------------------------------------------------------------------
;; Snapshot Management
;; ---------------------------------------------------------------------------

(defn trigger-snapshot!
  "Manually trigger a snapshot of the current state."
  [actor]
  (.triggerSnapshot ^CljPersistentActor actor))

(defn delete-events!
  "Delete persisted events up to and including the given sequence number."
  [actor sequence-nr]
  (.deleteEventsTo ^CljPersistentActor actor sequence-nr))

(defn delete-snapshots!
  "Delete snapshots matching the given criteria.

   Criteria can be created using snapshot-criteria."
  [actor criteria]
  (.deleteSnapshotsMatching ^CljPersistentActor actor criteria))

(defn snapshot-criteria
  "Create snapshot selection criteria.

   Options:
   - :max-sequence-nr - Maximum sequence number (inclusive)
   - :max-timestamp   - Maximum timestamp in milliseconds
   - :min-sequence-nr - Minimum sequence number (inclusive)
   - :min-timestamp   - Minimum timestamp in milliseconds"
  [{:keys [max-sequence-nr max-timestamp min-sequence-nr min-timestamp]
    :or {max-sequence-nr Long/MAX_VALUE
         max-timestamp Long/MAX_VALUE
         min-sequence-nr 0
         min-timestamp 0}}]
  (SnapshotSelectionCriteria/create max-sequence-nr max-timestamp min-sequence-nr min-timestamp))
