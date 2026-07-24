(ns pekko-clj.persistence.delivery
  "At-least-once delivery for persistent actors (Pekko's `AtLeastOnceDelivery`).

   A message is redelivered on an interval until the destination confirms it, and
   the outstanding set is rebuilt on restart by replaying the journal. Use this
   when a message *must* arrive at another actor even across crashes and restarts —
   the classic reliable-messaging / saga pattern.

   This is a sibling of `pekko-clj.persistence`, not part of it: at-least-once
   delivery and the persistent-actor timers of `defactor-persistent` come from two
   Scala traits that cannot be combined in one Java class without a Scala compiler
   (which this build does not have), and a delivery actor deliberately does not
   carry timers. `persist`, `persist-all`, `persist-async`, `persist-all-async`,
   `defer` and `then` are re-exported here unchanged, since they are just markers.

   The pattern (delivery ids live in the messages, deliver/confirm run in the
   *event* handler so replay reconstructs them):

     (defactor-delivery notifier
       :persistence-id (fn [args] (str \"notifier-\" (:id args)))
       (init [args] {:target (:target args)})

       (command [:notify payload]
         (persist [:queued payload]))
       (command [:ack delivery-id]
         (persist [:confirmed delivery-id]))

       (event [:queued payload]
         ;; deliver returns the message to send for the assigned delivery id
         (deliver (:target state) (fn [delivery-id] [:deliver delivery-id payload]))
         state)
       (event [:confirmed delivery-id]
         (confirm-delivery! delivery-id)
         state)

       (redeliver-interval (java.time.Duration/ofSeconds 5)))

   The destination replies to `(sender)` (or to the delivery id it received) with
   `[:ack delivery-id]` once it has processed the message."
  (:refer-clojure :exclude [deliver])
  (:require [clojure.core.match :refer [match]]
            [pekko-clj.persistence :as p]
            [pekko-clj.internal.context :as ctx])
  (:import [org.apache.pekko.actor ActorSystem ActorRef ActorPath]
           [pekko_clj.actor CljAtLeastOnceDeliveryActor]))

(def ^:dynamic *current-delivery-actor*
  "Bound to the current CljAtLeastOnceDeliveryActor while a command or event is
   handled. Used by reply/self/deliver/confirm-delivery! and friends."
  nil)

;; Re-exported persist helpers — plain markers, so they work here unchanged. See
;; pekko-clj.persistence for their full docstrings.
(def ^{:doc "See pekko-clj.persistence/persist."} persist p/persist)
(def ^{:doc "See pekko-clj.persistence/persist-all."} persist-all p/persist-all)
(def ^{:doc "See pekko-clj.persistence/persist-async."} persist-async p/persist-async)
(def ^{:doc "See pekko-clj.persistence/persist-all-async."} persist-all-async p/persist-all-async)
(def ^{:doc "See pekko-clj.persistence/defer."} defer p/defer)
(def ^{:doc "See pekko-clj.persistence/then."} then p/then)

;; ---------------------------------------------------------------------------
;; Clause parsing
;; ---------------------------------------------------------------------------

(defn- find-clause [clauses head]
  (first (filter #(and (seq? %) (= head (first %))) clauses)))

(defn- parse-value-clause [clauses head]
  (second (find-clause clauses head)))

(defn- parse-persistence-id [clauses]
  (loop [remaining clauses]
    (when (seq remaining)
      (if (= :persistence-id (first remaining))
        (second remaining)
        (recur (rest remaining))))))

(defn- parse-init [clauses]
  (when-let [[_ bindings & body] (find-clause clauses 'init)]
    `(fn ~bindings ~@body)))

(defn- parse-fn-clause [clauses head]
  (when-let [[_ bindings & body] (find-clause clauses head)]
    `(fn ~bindings ~@body)))

(defn- clauses-headed [clauses head]
  (filter #(and (seq? %) (= head (first %))) clauses))

(defn- catch-all-pattern?
  "True if a core.match pattern already matches every message (a bare symbol or
   :else), so the user supplied their own catch-all. Mirrors the persistence one."
  [pattern]
  (or (= pattern :else) (symbol? pattern)))

(def ^:private delivery-clause-heads
  '#{init command event on-recovery-complete on-stop supervision
     redeliver-interval redelivery-burst-limit warn-after-unconfirmed max-unconfirmed})

(def ^:private delivery-singleton-heads
  "Clauses allowed at most once; command/event repeat freely."
  '#{init on-recovery-complete on-stop supervision
     redeliver-interval redelivery-burst-limit warn-after-unconfirmed max-unconfirmed})

(defn- validate-delivery-clauses
  "Throw at macro-expansion on an unknown clause head, a duplicate singleton
   clause, or more than one :persistence-id. Mirrors validate-persistent-clauses."
  [name clauses]
  (loop [remaining clauses
         seen {}]
    (if (empty? remaining)
      (doseq [[head n] seen]
        (when (and (> n 1)
                   (or (= head :persistence-id)
                       (contains? delivery-singleton-heads head)))
          (throw (ex-info (str "defactor-delivery " name ": only one `" head
                               "` clause is allowed, found " n)
                          {:clause head :count n}))))
      (let [item (first remaining)]
        (if (= :persistence-id item)
          (recur (drop 2 remaining) (update seen :persistence-id (fnil inc 0)))
          (let [head (when (seq? item) (first item))]
            (when-not (contains? delivery-clause-heads head)
              (throw (ex-info (str "defactor-delivery " name ": unknown clause `"
                                   (pr-str item) "` — expected :persistence-id or one of "
                                   "init, command, event, on-recovery-complete, on-stop, "
                                   "supervision, redeliver-interval, redelivery-burst-limit, "
                                   "warn-after-unconfirmed, max-unconfirmed")
                              {:clause item})))
            (recur (rest remaining) (update seen head (fnil inc 0)))))))))

(defn- build-command-handler [commands]
  (let [this-sym (with-meta (gensym "this") {:tag 'pekko_clj.actor.CljAtLeastOnceDeliveryActor})
        command-sym (gensym "command")
        match-clauses (mapcat (fn [[_ pattern & body]] [pattern `(do ~@body)]) commands)
        has-catch-all? (some catch-all-pattern? (map second commands))]
    `(fn [~this-sym ~command-sym]
       (binding [*current-delivery-actor* ~this-sym
                 ctx/*current-self* (.selfRef ~this-sym)]
         (let [~'this ~this-sym
               ~'state @~this-sym]
           (match ~command-sym
             ~@match-clauses
             ~@(when-not has-catch-all?
                 [:else `(do (.unhandled ~this-sym ~command-sym) nil)])))))))

(defn- build-event-handler
  "The event handler is (fn [this state event] -> new-state). `this` is passed —
   unlike defactor-persistent's two-arg event handler — because delivery events call
   `deliver`/`confirm-delivery!`, which need the actor bound, during recovery too."
  [events]
  (let [this-sym (with-meta (gensym "this") {:tag 'pekko_clj.actor.CljAtLeastOnceDeliveryActor})
        state-sym (gensym "state")
        event-sym (gensym "event")
        match-clauses (mapcat (fn [[_ pattern & body]] [pattern `(do ~@body)]) events)]
    `(fn [~this-sym ~state-sym ~event-sym]
       (binding [*current-delivery-actor* ~this-sym
                 ctx/*current-self* (.selfRef ~this-sym)]
         (let [~'this ~this-sym
               ~'state ~state-sym]
           (match ~event-sym
             ~@match-clauses
             :else ~'state))))))

(defmacro defactor-delivery
  "Define a persistent actor with at-least-once delivery.

   Clauses (mostly a subset of `defactor-persistent`):
   - :persistence-id fn        - (fn [args] -> string) unique id
   - (init [args] ...)         - initial state from spawn args
   - (command pattern & body)  - handle a command, return events via `persist`.
                                 An unmatched command goes to Pekko's unhandled().
   - (event pattern & body)    - apply an event to state; `this`/`state` are bound,
                                 and `deliver` / `confirm-delivery!` may be called
                                 here (they must, so replay rebuilds the outstanding
                                 set). Return the new state.
   - (on-recovery-complete [this] ...)
   - (on-stop ...)             - side effects on stop; `this`/`state` bound
   - (supervision strat)       - child supervisor strategy
   - (redeliver-interval d)         - java.time.Duration between redelivery attempts
   - (redelivery-burst-limit n)     - cap redeliveries per interval
   - (warn-after-unconfirmed n)     - deliver an UnconfirmedWarning after n attempts
   - (max-unconfirmed n)            - cap outstanding messages (deliver then throws)

   In command/event bodies `this` and `state` are reserved anaphors (don't shadow
   them in a pattern). Delivery helpers: `deliver`, `confirm-delivery!`,
   `num-unconfirmed`; plus `reply`, `self`, `sender`, `tell`, `watch`, `unwatch`
   and the re-exported persist helpers."
  [name & clauses]
  (let [docstring (when (string? (first clauses)) (first clauses))
        clauses   (if docstring (rest clauses) clauses)
        _ (validate-delivery-clauses name clauses)
        persistence-id-fn (parse-persistence-id clauses)
        init-fn (parse-init clauses)
        commands (clauses-headed clauses 'command)
        events (clauses-headed clauses 'event)
        on-recovery-complete (parse-fn-clause clauses 'on-recovery-complete)
        on-stop-clause (find-clause clauses 'on-stop)
        supervision-expr (parse-value-clause clauses 'supervision)
        redeliver-interval (parse-value-clause clauses 'redeliver-interval)
        burst-limit (parse-value-clause clauses 'redelivery-burst-limit)
        warn-after (parse-value-clause clauses 'warn-after-unconfirmed)
        max-unconfirmed (parse-value-clause clauses 'max-unconfirmed)
        ;; Tagged so (.selfRef this-sym) in post-stop-fn resolves without reflection.
        this-sym (with-meta (gensym "this") {:tag 'pekko_clj.actor.CljAtLeastOnceDeliveryActor})
        post-stop-fn (when on-stop-clause
                       `(fn [~this-sym]
                          (binding [*current-delivery-actor* ~this-sym
                                    ctx/*current-self* (.selfRef ~this-sym)]
                            (let [~'this ~this-sym
                                  ~'state (deref ~this-sym)]
                              ~@(rest on-stop-clause)
                              nil))))
        command-handler (build-command-handler commands)
        event-handler (build-event-handler events)]
    (when-not persistence-id-fn
      (throw (ex-info (str "defactor-delivery " name ": a :persistence-id clause is required")
                      {:name name})))
    ;; Reserved-anaphor guard for both command and event patterns.
    (doseq [clause (concat commands events)]
      (let [pattern (second clause)]
        (when (some #{'this 'state} (tree-seq coll? seq pattern))
          (throw (ex-info (str "defactor-delivery " name ": `this`/`state` are reserved "
                               "bindings — rename them in the " (first clause) " pattern")
                          {:pattern pattern})))))
    `(def ~(if docstring (vary-meta name assoc :doc docstring) name)
       (let [command-handler#      ~command-handler
             event-handler#        ~event-handler
             init-fn#              ~init-fn
             persistence-id-fn#    ~persistence-id-fn
             on-recovery-complete# ~on-recovery-complete
             post-stop#            ~post-stop-fn
             supervisor-strategy#  ~supervision-expr
             redeliver-interval#   ~redeliver-interval
             burst-limit#          ~burst-limit
             warn-after#           ~warn-after
             max-unconfirmed#      ~max-unconfirmed]
         {:type :at-least-once-delivery
          :make-props (fn [args#]
                        {:command-handler command-handler#
                         :event-handler event-handler#
                         :on-recovery-complete on-recovery-complete#
                         :post-stop post-stop#
                         :supervisor-strategy supervisor-strategy#
                         :redeliver-interval redeliver-interval#
                         :redelivery-burst-limit burst-limit#
                         :warn-after-unconfirmed warn-after#
                         :max-unconfirmed max-unconfirmed#
                         :state (when init-fn# (init-fn# args#))
                         :persistence-id (persistence-id-fn# args#)})}))))

;; ---------------------------------------------------------------------------
;; Spawning
;; ---------------------------------------------------------------------------

(defn spawn
  "Spawn an at-least-once-delivery actor. args are passed to init and
   :persistence-id. Returns an ActorRef."
  [^ActorSystem system actor-def args]
  (.actorOf system (CljAtLeastOnceDeliveryActor/create ((:make-props actor-def) args))))

(defn spawn-named
  "Spawn an at-least-once-delivery actor with a specific name."
  [^ActorSystem system actor-def args name]
  (.actorOf system (CljAtLeastOnceDeliveryActor/create ((:make-props actor-def) args)) name))

;; ---------------------------------------------------------------------------
;; Handlers' actor context + delivery API
;; ---------------------------------------------------------------------------

(defn reply
  "Reply to the sender of the current command."
  [msg]
  (.reply ^CljAtLeastOnceDeliveryActor *current-delivery-actor* msg)
  nil)

(defn self
  "This actor's own ActorRef."
  ^ActorRef []
  (.selfRef ^CljAtLeastOnceDeliveryActor *current-delivery-actor*))

(defn sender
  "The ActorRef that sent the command being handled."
  ^ActorRef []
  (.senderRef ^CljAtLeastOnceDeliveryActor *current-delivery-actor*))

(defn context
  "This actor's ActorContext."
  ^org.apache.pekko.actor.ActorContext []
  (.actorContext ^CljAtLeastOnceDeliveryActor *current-delivery-actor*))

(defn tell
  "Send `msg` to `target` with this actor as sender."
  [^ActorRef target msg]
  (.tell ^CljAtLeastOnceDeliveryActor *current-delivery-actor* target msg)
  nil)

(defn watch
  "Watch `actor-ref`; its termination arrives as a Terminated command."
  [^ActorRef actor-ref]
  (.watch ^CljAtLeastOnceDeliveryActor *current-delivery-actor* actor-ref)
  nil)

(defn unwatch
  "Stop watching `actor-ref`."
  [^ActorRef actor-ref]
  (.unwatch ^CljAtLeastOnceDeliveryActor *current-delivery-actor* actor-ref)
  nil)

(defn deliver
  "Send `(id->message delivery-id)` to `destination` (an ActorRef or ActorPath),
   redelivering on the actor's redeliver-interval until `confirm-delivery!` is
   called with that delivery id. Call from an event handler so a replay
   reconstructs the outstanding deliveries. Returns nil."
  [destination id->message]
  (let [^ActorPath path (if (instance? ActorPath destination)
                          destination
                          (.path ^ActorRef destination))]
    (.deliverTo ^CljAtLeastOnceDeliveryActor *current-delivery-actor* path id->message))
  nil)

(defn confirm-delivery!
  "Confirm the delivery `id`, stopping its redelivery. Returns true if it was
   outstanding. Call from an event handler."
  [id]
  (.confirmDeliveryId ^CljAtLeastOnceDeliveryActor *current-delivery-actor* (long id)))

(defn num-unconfirmed
  "How many messages this actor is still awaiting confirmation for."
  []
  (.unconfirmedCount ^CljAtLeastOnceDeliveryActor *current-delivery-actor*))

(defn recovering?
  "True while the actor is replaying its journal."
  [actor]
  (.isRecovering ^CljAtLeastOnceDeliveryActor actor))
