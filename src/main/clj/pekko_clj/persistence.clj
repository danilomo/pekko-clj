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
  (:require [clojure.core.match :refer [match]]
            [pekko-clj.internal.context :as ctx])
  (:import [org.apache.pekko.actor ActorRefFactory]
           [org.apache.pekko.persistence Recovery SnapshotSelectionCriteria]
           [pekko_clj.actor CljPersistentActor Defer PersistAll PersistAsync PersistOps]
           [java.time Duration]))

(def ^:dynamic *current-persistent-actor*
  "Bound to the current CljPersistentActor during command handling.
   Used by (reply ...). Mirrors pekko-clj.core/*current-actor*."
  nil)

(defn- ->duration
  "Coerce a java.time.Duration or a number of milliseconds to a Duration
   (the ms-or-Duration convention; mirrors pekko-clj.core / pekko-clj.stream)."
  ^Duration [d]
  (if (instance? Duration d) d (Duration/ofMillis (long d))))

(defn- current-actor
  "The current CljPersistentActor, or a friendly IllegalStateException if called
   outside a command/event/lifecycle body (where *current-persistent-actor* is
   nil — a bare NPE otherwise). `fn-name` is the public fn being guarded."
  ^CljPersistentActor [fn-name]
  (or *current-persistent-actor*
      (throw (IllegalStateException.
              (str "pekko-clj.persistence/" fn-name " must be called inside a "
                   "persistent actor's command/event/lifecycle body")))))

;; ---------------------------------------------------------------------------
;; Recovery settings
;; ---------------------------------------------------------------------------

(defn snapshot-criteria
  "Create snapshot selection criteria.

   Options:
   - :max-sequence-nr - Maximum sequence number (inclusive)
   - :max-timestamp   - Maximum timestamp in milliseconds
   - :min-sequence-nr - Minimum sequence number (inclusive)
   - :min-timestamp   - Minimum timestamp in milliseconds"
  ^SnapshotSelectionCriteria
  [{:keys [max-sequence-nr max-timestamp min-sequence-nr min-timestamp]
    :or {max-sequence-nr Long/MAX_VALUE
         max-timestamp Long/MAX_VALUE
         min-sequence-nr 0
         min-timestamp 0}}]
  (SnapshotSelectionCriteria/create max-sequence-nr max-timestamp min-sequence-nr min-timestamp))

(defn- ->snapshot-criteria
  ^SnapshotSelectionCriteria [from-snapshot]
  (cond
    (or (nil? from-snapshot) (= :latest from-snapshot)) (SnapshotSelectionCriteria/latest)
    (= :none from-snapshot) (SnapshotSelectionCriteria/none)
    (instance? SnapshotSelectionCriteria from-snapshot) from-snapshot
    (map? from-snapshot) (snapshot-criteria from-snapshot)
    :else (throw (IllegalArgumentException.
                  (str ":from-snapshot must be :latest, :none, a snapshot-criteria map or a "
                       "SnapshotSelectionCriteria, got " (pr-str from-snapshot))))))

(defn recovery-settings
  "Build Pekko's Recovery from a Clojure value. This is what a `(recovery …)`
   clause is passed through, and it is exposed so callers can build one directly.

   Accepts:
   - :none    - do not replay at all. The actor starts from its `init` state and
                only writes; use it for a command-side actor whose state is
                rebuilt elsewhere (a read model, a projection).
   - :default - Pekko's default: latest snapshot, then every event after it
   - a map:
     - :from-snapshot  - :latest (default), :none, a `snapshot-criteria` map, or a
                         SnapshotSelectionCriteria
     - :to-sequence-nr - replay stops here (default: no limit)
     - :replay-max     - replay at most this many events (default: no limit)
   - a Recovery, returned unchanged

   Returns: org.apache.pekko.persistence.Recovery

   Example:
     (recovery-settings {:replay-max 100})
     (recovery-settings :none)"
  ^Recovery [opts]
  (cond
    (instance? Recovery opts) opts
    (= :none opts) (Recovery/none)
    (= :default opts) (Recovery/create)
    (map? opts) (let [{:keys [from-snapshot to-sequence-nr replay-max]} opts]
                  (Recovery/create (->snapshot-criteria from-snapshot)
                                   (long (or to-sequence-nr Long/MAX_VALUE))
                                   (long (or replay-max Long/MAX_VALUE))))
    :else (throw (IllegalArgumentException.
                  (str "recovery must be :none, :default, a map or a Recovery, got "
                       (pr-str opts))))))

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

(defn- find-clause
  "The first clause headed by `head`, or nil."
  [clauses head]
  (first (filter #(and (seq? %) (= head (first %))) clauses)))

(defn- parse-value-clause
  "The single argument of a (head value) clause, or nil when it is absent."
  [clauses head]
  (second (find-clause clauses head)))

(defn- catch-all-pattern?
  "True if a core.match `command` pattern already matches every message — a bare
   local symbol (binds anything, e.g. `cmd` or `_`) or the `:else` keyword — so
   the user has provided their own catch-all and defactor-persistent must not
   append one. Mirrors pekko-clj.core's private predicate of the same name."
  [pattern]
  (or (= pattern :else)
      (symbol? pattern)))

(def ^:private persistent-actor-clause-heads
  "List-clause heads defactor-persistent recognizes. :persistence-id is handled
   separately below — it's a bare keyword/value pair, not a list clause."
  '#{init command event tagger snapshot-every delete-events-on-snapshot
     on-recovery-complete on-stop supervision recovery
     journal-plugin-id snapshot-plugin-id})

(def ^:private persistent-actor-singleton-heads
  "Clauses that may appear at most once; command/event are the only clauses
   allowed to repeat, so they're excluded here."
  '#{init tagger snapshot-every on-recovery-complete on-stop supervision
     recovery journal-plugin-id snapshot-plugin-id})

(defn- validate-persistent-clauses
  "Throw at macro-expansion for common defactor-persistent authoring mistakes
   that `parse-persistence-id`/`parse-init`/etc. would otherwise silently
   swallow: an unknown clause head (a typo like `(tagged ...)`), a duplicate of
   a clause that may only appear once, or more than one :persistence-id."
  [name clauses]
  (loop [remaining clauses
         seen {}]
    (if (empty? remaining)
      (doseq [[head n] seen]
        (when (and (> n 1)
                   (or (= head :persistence-id)
                       (contains? persistent-actor-singleton-heads head)))
          (throw (ex-info (str "defactor-persistent " name ": only one `" head
                               "` clause is allowed, found " n)
                          {:clause head :count n}))))
      (let [item (first remaining)]
        (if (= :persistence-id item)
          (recur (drop 2 remaining) (update seen :persistence-id (fnil inc 0)))
          (let [head (when (seq? item) (first item))]
            (when-not (contains? persistent-actor-clause-heads head)
              (throw (ex-info (str "defactor-persistent " name ": unknown clause `"
                                   (pr-str item) "` — expected :persistence-id or one "
                                   "of init, command, event, tagger, snapshot-every, "
                                   "delete-events-on-snapshot, on-recovery-complete, "
                                   "on-stop, supervision, recovery, journal-plugin-id, "
                                   "snapshot-plugin-id")
                              {:clause item})))
            (recur (rest remaining) (update seen head (fnil inc 0)))))))))

(defn- build-command-handler [commands]
  (let [this-sym (with-meta (gensym "this") {:tag 'pekko_clj.actor.CljPersistentActor})
        command-sym (gensym "command")
        match-clauses (mapcat (fn [[_ pattern & body]]
                                [pattern `(do ~@body)])
                              commands)
        ;; If the user didn't supply a catch-all, append a default that routes
        ;; an unmatched command to Pekko's unhandled() instead of silently
        ;; dropping it. Mirrors the `defactor` catch-all in pekko-clj.core.
        has-catch-all? (some catch-all-pattern? (map second commands))]
    `(fn [~this-sym ~command-sym]
       (binding [*current-persistent-actor* ~this-sym
                 ctx/*current-self* (.selfRef ~this-sym)]
         (let [~'this ~this-sym
               ~'state @~this-sym]
           (match ~command-sym
             ~@match-clauses
             ~@(when-not has-catch-all?
                 [:else `(do (.unhandled ~this-sym ~command-sym) nil)])))))))

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
   - (command pattern & body) - Handle commands, return events via (persist ...).
                                A command matching no clause is sent to Pekko's
                                unhandled() (published as an UnhandledMessage on
                                the event stream) rather than silently dropped,
                                unless you supply your own catch-all.
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
   - (on-stop ...)            - Side effects to run when the actor stops (and, via
                                the default restart path, before a restart). `this`
                                and `state` are bound. There is deliberately no
                                `on-restart` counterpart: a restarted persistent
                                actor rebuilds its state by replaying the journal,
                                so `on-recovery-complete` is the hook that fires
                                once the state is valid again.
   - (supervision strat)      - Supervisor strategy for this actor's children (see
                                pekko-clj.supervision). There is no `on-error`
                                clause: `defactor`'s recovers by returning a new
                                state, which for an event-sourced actor would be
                                state no event produced — gone on the next replay.
                                Let the failure reach supervision instead.
   - (recovery opts)          - How much to replay on start: `:none` for a
                                write-only actor, or a map (`:from-snapshot`,
                                `:to-sequence-nr`, `:replay-max`). See
                                `recovery-settings`.
   - (journal-plugin-id id)   - Journal plugin for this actor only, overriding
                                `pekko.persistence.journal.plugin`
   - (snapshot-plugin-id id)  - Snapshot-store plugin for this actor only

   `:persistence-id` and `init` are called with the spawn args — except under
   cluster sharding, where every entity of a type shares one Props and both are
   called with the **entity id** instead (see `pekko-clj.cluster.sharding/start`).

   In command bodies, `this` (the actor) and `state` (its current value) are
   reserved anaphors:
   - @this / state   - Current state
   - (reply msg)     - Reply to sender
   - (persist event) - Return a single event to persist
   - (persist-all events) - Return several events to persist, in order
   - (persist-async event) / (persist-all-async events) - persist without stashing
     the commands that arrive meanwhile
   - (defer value) - hand `value` back to the command handler once the writes
     issued before it have completed
   - (then op ...) - run several of the above, in order
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
        _ (validate-persistent-clauses name clauses)
        persistence-id-fn (parse-persistence-id clauses)
        ;; Guard at expansion time (like defactor-delivery) rather than letting a
        ;; missing id NPE at runtime inside :make-props.
        _ (when-not persistence-id-fn
            (throw (ex-info (str "defactor-persistent " name ": a :persistence-id "
                                 "clause is required (:persistence-id (fn [args] ...))")
                            {:name name})))
        init-fn (parse-init clauses)
        commands (parse-commands clauses)
        events (parse-events clauses)
        [snapshot-every keep-snapshots] (parse-snapshot-every clauses)
        delete-events-on-snapshot (parse-delete-events-on-snapshot clauses)
        tagger-fn (parse-tagger clauses)
        on-recovery-complete (parse-on-recovery-complete clauses)
        on-stop-clause (find-clause clauses 'on-stop)
        supervision-expr (parse-value-clause clauses 'supervision)
        recovery-expr (parse-value-clause clauses 'recovery)
        journal-plugin-id (parse-value-clause clauses 'journal-plugin-id)
        snapshot-plugin-id (parse-value-clause clauses 'snapshot-plugin-id)
        ;; Tagged so (.selfRef this-sym) in post-stop-fn resolves without reflection.
        this-sym (with-meta (gensym "this") {:tag 'pekko_clj.actor.CljPersistentActor})
        ;; on-stop runs for side effects only — a persistent actor's state comes
        ;; from its events, so a return value has nowhere legitimate to go.
        post-stop-fn (when on-stop-clause
                       `(fn [~this-sym]
                          (binding [*current-persistent-actor* ~this-sym
                                    ctx/*current-self* (.selfRef ~this-sym)]
                            (let [~'this ~this-sym
                                  ~'state (deref ~this-sym)]
                              ~@(rest on-stop-clause)
                              nil))))
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
             on-recovery-complete# ~on-recovery-complete
             post-stop#            ~post-stop-fn
             supervisor-strategy#  ~supervision-expr
             recovery#             ~(when recovery-expr `(recovery-settings ~recovery-expr))
             journal-plugin-id#    ~journal-plugin-id
             snapshot-plugin-id#   ~snapshot-plugin-id
             ;; Everything that is the same for every instance of this definition.
             ;; The two props builders below only add what differs: an eagerly
             ;; computed id + state, or the functions that derive them per entity.
             shared-props#         {:command-handler command-handler#
                                    :event-handler event-handler#
                                    :snapshot-every snapshot-every#
                                    :keep-snapshots keep-snapshots#
                                    :delete-events-on-snapshot delete-events#
                                    :tagger tagger#
                                    :on-recovery-complete on-recovery-complete#
                                    :post-stop post-stop#
                                    :supervisor-strategy supervisor-strategy#
                                    :recovery recovery#
                                    :journal-plugin-id journal-plugin-id#
                                    :snapshot-plugin-id snapshot-plugin-id#}]
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
          :recovery recovery#
          ;; Props shared by every entity of a sharded type: nothing per-instance
          ;; can be baked in, so `:persistence-id` and `init` are handed to the
          ;; actor as functions and invoked with the entity id at construction.
          :entity-props (assoc shared-props#
                               :persistence-id-fn persistence-id-fn#
                               :init-fn init-fn#)
          :make-props (fn [args#]
                        (assoc shared-props#
                               :state (when init-fn# (init-fn# args#))
                               :persistence-id (persistence-id-fn# args#)))}))))

;; ---------------------------------------------------------------------------
;; Spawning Persistent Actors
;; ---------------------------------------------------------------------------

(defn spawn
  "Spawn a persistent actor.

   Arguments:
   - factory: an ActorRefFactory — an ActorSystem (top-level) or an actor context
     (`core/context`, to spawn it as a child of the current actor)
   - actor-def: Actor definition from defactor-persistent
   - args: Arguments passed to init and persistence-id functions

   Returns an ActorRef."
  [^ActorRefFactory factory actor-def args]
  (let [props-map ((:make-props actor-def) args)
        props (CljPersistentActor/create props-map)]
    (.actorOf factory props)))

(defn spawn-named
  "Spawn a persistent actor with a specific name.

   Arguments:
   - factory: an ActorRefFactory — an ActorSystem (top-level) or an actor context
     (`core/context`, to spawn it as a child of the current actor)
   - actor-def: Actor definition from defactor-persistent
   - args: Arguments passed to init and persistence-id functions
   - name: Actor name

   Returns an ActorRef."
  [^ActorRefFactory factory actor-def args name]
  (let [props-map ((:make-props actor-def) args)
        props (CljPersistentActor/create props-map)]
    (.actorOf factory props name)))

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

   The batch is written **atomically**: it reaches the journal as a single write,
   so either every event of the batch is stored or none is. A crash mid-command
   can therefore never leave a half-applied command behind — that guarantee is
   the reason to prefer one `persist-all` over several `persist` calls. The
   event handler still sees the events one at a time, in order, after the write.

   An empty collection persists nothing.

   Example:
     (persist-all [[:item-added item] [:inventory-updated]])"
  [events]
  (PersistAll/of events))

(defn persist-async
  "Return an event to be persisted **without stashing** the commands that arrive
   while the write is in flight (Pekko's `persistAsync`).

   `persist` guarantees that the next command sees the state the event produced,
   by holding those commands back until the journal has acknowledged the write.
   This one does not: the actor keeps processing, and the event handler runs when
   the write completes. Higher throughput, at the cost of a command possibly
   reading state that does not include an event already on its way to the journal.
   Reach for it only when that is genuinely acceptable — a metrics or audit
   stream, say, rather than a balance whose next command must not overdraw it.

   Example:
     (command [:observe v] (persist-async [:observed v]))"
  [event]
  (PersistAsync/of [event]))

(defn persist-all-async
  "Like `persist-async`, for several events at once. Unlike `persist-all` these are
   ordinary asynchronous writes and are **not** atomic — the batch can end up
   partially written if the actor crashes mid-flight.

   An empty collection persists nothing."
  [events]
  (PersistAsync/of events))

(defn defer
  "Return a value the command handler will be called with again, once every event
   the same command asked to persist has actually been written (Pekko's
   `deferAsync`).

   The value is *not* an event: it never reaches the journal or the event handler,
   and it is gone after a restart. Its purpose is to sequence a side effect —
   almost always a reply — after the writes, so the caller only hears back once the
   events are durable. The sender is still in scope, so `(reply …)` works.

   Combine it with a persist using `then`:

     (command [:withdraw n]
       (then (persist [:withdrawn n])
             (defer [:withdrawn-ok n])))

     (command [:withdrawn-ok n]
       (reply {:ok true :balance (:balance state)})
       nil)"
  [value]
  (Defer/of value))

(defn then
  "Return several persist operations to run in order — the way a command handler
   expresses more than one, since it returns a single value.

   Each argument is an operation: a bare event, or the result of `persist`,
   `persist-all`, `persist-async`, `persist-all-async`, `defer`, or a nested
   `then`. Nils are dropped, so a conditional operation can just be nil.

   Example:
     (then (persist-all [[:debited n] [:audited]])
           (defer :done))"
  [& ops]
  (PersistOps/of (remove nil? ops)))

(defn reply
  "Reply to the sender of the current command. Call inside a command handler,
   where defactor-persistent binds the current persistent actor. Returns nil, so
   a command whose last form is (reply ...) persists no event."
  [msg]
  (.reply ^CljPersistentActor *current-persistent-actor* msg)
  nil)

;; ---------------------------------------------------------------------------
;; Actor context (the persistent-actor counterparts of pekko-clj.core's)
;; ---------------------------------------------------------------------------
;;
;; `pekko-clj.core`'s self/sender/context/timers read `core/*current-actor*`, which
;; is type-hinted CljActor — so calling them from a persistent actor's body throws
;; a ClassCastException. These read `*current-persistent-actor*` instead. Same
;; names, same semantics; use these inside defactor-persistent, core's inside
;; defactor. (`reply` above has always worked this way.)

(defn self
  "The current persistent actor's own ActorRef."
  ^org.apache.pekko.actor.ActorRef []
  (.selfRef (current-actor "self")))

(defn sender
  "The ActorRef that sent the command being handled."
  ^org.apache.pekko.actor.ActorRef []
  (.senderRef (current-actor "sender")))

(defn context
  "The current persistent actor's ActorContext."
  ^org.apache.pekko.actor.ActorContext []
  (.actorContext (current-actor "context")))

(defn tell
  "Send `msg` to `target` with this actor as the sender."
  [^org.apache.pekko.actor.ActorRef target msg]
  (.tell ^CljPersistentActor *current-persistent-actor* target msg)
  nil)

(defn stop
  "Stop `target` (self or a child) via the current actor's context. Returns nil."
  [^org.apache.pekko.actor.ActorRef target]
  (.stop (context) target)
  nil)

(defn watch
  "Watch `actor-ref`; its termination arrives as a Terminated command."
  [^org.apache.pekko.actor.ActorRef actor-ref]
  (.watch ^CljPersistentActor *current-persistent-actor* actor-ref)
  nil)

(defn unwatch
  "Stop watching `actor-ref`."
  [^org.apache.pekko.actor.ActorRef actor-ref]
  (.unwatch ^CljPersistentActor *current-persistent-actor* actor-ref)
  nil)

;; ---------------------------------------------------------------------------
;; Stash (Pekko's persistent-actor stash)
;; ---------------------------------------------------------------------------
;;
;; These call Pekko's own AbstractPersistentActor stash — NOT pekko-clj.core's
;; CljActor stash (a LinkedList re-sent to self). The behaviours differ, so mind
;; the asymmetry:
;;   - core/unstash-all re-sends to self, so messages land at the TAIL of the
;;     mailbox (after anything already queued);
;;   - Pekko's unstash-all PREPENDS the stashed messages to the FRONT of the
;;     mailbox (ahead of messages that arrived while they were stashed).
;; Pekko integrates this user stash with its internal persist-stash correctly on
;; its own. The canonical use is stashing commands until on-recovery-complete has
;; warmed the state. Stash capacity comes from the mailbox config; exceeding it
;; raises StashOverflowException (per the mailbox's stash-capacity setting).

(defn stash
  "Stash the command currently being handled, to process later. Call inside a
   command handler. Returns nil. See this section's note on the front-of-mailbox
   unstash semantics (which differ from pekko-clj.core/stash)."
  []
  (.stash (current-actor "stash"))
  nil)

(defn unstash
  "Re-enqueue the oldest stashed command at the FRONT of the mailbox (Pekko
   prepend semantics). Returns nil."
  []
  (.unstash (current-actor "unstash"))
  nil)

(defn unstash-all
  "Re-enqueue all stashed commands, in stash order, at the FRONT of the mailbox
   (Pekko prepend semantics — the opposite of pekko-clj.core/unstash-all's tail
   append). Returns nil."
  []
  (.unstashAll (current-actor "unstash-all"))
  nil)

(defn start-timer
  "Start a repeating timer under `key` at a fixed RATE, delivering `message` to
   self every `interval` (ms or a java.time.Duration). After a pause it may fire
   several ticks to catch up; prefer `start-timer-fixed-delay` for most work.
   Starting a timer with an existing key replaces it. Timers are cancelled
   automatically when the actor stops or restarts."
  ([key interval message]
   (.startTimer (current-actor "start-timer") key (->duration interval) message))
  ([key initial-delay interval message]
   (.startTimerWithInitialDelay (current-actor "start-timer")
                                key (->duration initial-delay) (->duration interval) message)))

(defn start-timer-fixed-delay
  "Start a repeating timer under `key` with a fixed DELAY between ticks: each tick
   fires `interval` (ms or a java.time.Duration) after the previous is delivered,
   so ticks never bunch up to catch up after a pause. Pekko's recommended mode for
   most periodic work; contrast `start-timer` (fixed RATE). Replaces any timer
   already under `key`; cancelled automatically on stop/restart."
  ([key interval message]
   (.startTimerWithFixedDelay (current-actor "start-timer-fixed-delay")
                              key (->duration interval) message))
  ([key initial-delay interval message]
   (.startTimerWithFixedDelayAndInitial (current-actor "start-timer-fixed-delay")
                                        key (->duration initial-delay) (->duration interval) message)))

(defn start-single-timer
  "Deliver `message` to self once after `delay` (ms or a java.time.Duration)."
  [key delay message]
  (.startSingleTimer (current-actor "start-single-timer") key (->duration delay) message))

(defn cancel-timer
  "Cancel the timer registered under `key`."
  [key]
  (.cancelTimer (current-actor "cancel-timer") key))

(defn timer-active?
  "True while a timer is registered under `key`."
  [key]
  (.isTimerActive (current-actor "timer-active?") key))

(defn cancel-all-timers
  "Cancel every timer this actor has started."
  []
  (.cancelAllTimers (current-actor "cancel-all-timers")))

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

