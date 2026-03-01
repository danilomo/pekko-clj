(ns pekko-clj.persistence
  "Event sourcing and persistence support for pekko-clj.

   Provides persistent actors that:
   - Store events in a journal
   - Rebuild state by replaying events
   - Support snapshots for faster recovery

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

       (snapshot-every 100))"
  (:require [clojure.core.match :refer [match]])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.persistence SnapshotSelectionCriteria]
           [pekko_clj.actor CljPersistentActor]))

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

(defn- parse-snapshot-every [clauses]
  (let [snapshot-clause (first (filter #(and (seq? %) (= 'snapshot-every (first %))) clauses))]
    (when snapshot-clause
      (second snapshot-clause))))

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
       (let [~'this ~this-sym
             ~'state @~this-sym]
         (match ~command-sym
           ~@match-clauses
           :else nil)))))

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
   - (snapshot-every n)       - Take snapshot every n events
   - (on-recovery-complete [this] ...) - Called when recovery finishes

   The `this` binding is available in command handlers for:
   - @this          - Current state
   - (reply msg)    - Reply to sender
   - (persist event) - Return event(s) to persist

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

       (snapshot-every 50))"
  [name & clauses]
  (let [persistence-id-fn (parse-persistence-id clauses)
        init-fn (parse-init clauses)
        commands (parse-commands clauses)
        events (parse-events clauses)
        snapshot-every (parse-snapshot-every clauses)
        on-recovery-complete (parse-on-recovery-complete clauses)
        command-handler (build-command-handler commands)
        event-handler (build-event-handler events)]
    `(def ~name
       {:type :persistent-actor
        :persistence-id-fn ~persistence-id-fn
        :init-fn ~init-fn
        :command-handler ~command-handler
        :event-handler ~event-handler
        :snapshot-every ~snapshot-every
        :on-recovery-complete ~on-recovery-complete
        :make-props (fn [args#]
                      (let [init-fn# ~init-fn
                            initial-state# (when init-fn# (init-fn# args#))
                            persistence-id# (~persistence-id-fn args#)]
                        {:state initial-state#
                         :persistence-id persistence-id#
                         :command-handler ~command-handler
                         :event-handler ~event-handler
                         :snapshot-every ~snapshot-every
                         :on-recovery-complete ~on-recovery-complete}))})))

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
  "Return an event (or events) to be persisted.
   Use this in command handlers.

   Examples:
     (persist [:item-added item])
     (persist [[:item-added item] [:inventory-updated]])  ; multiple events"
  [event-or-events]
  event-or-events)

(defn reply
  "Reply to the sender of the current command.
   Use this in command handlers."
  [msg]
  (.reply ^CljPersistentActor (resolve 'this) msg))

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
