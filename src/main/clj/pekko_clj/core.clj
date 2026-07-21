(ns pekko-clj.core
  (:require [clojure.core.match :as m])
  (:import [org.apache.pekko.actor ActorSystem ActorRef ActorRefFactory ActorContext
                                   ActorSelection PoisonPill Props ReceiveTimeout]
           [org.apache.pekko.pattern Patterns AskTimeoutException]
           [pekko_clj.actor CljActor BecomeResult]
           [com.typesafe.config Config]
           [java.time Duration]
           [java.util.concurrent TimeUnit ExecutionException TimeoutException CompletableFuture]))

(set! *warn-on-reflection* true)

(def ^{:dynamic true :tag CljActor} *current-actor*
  "Bound to the current CljActor instance during message handling.
   Used by !, reply, sender, self, parent, spawn."
  nil)

(defn self
  "Returns the ActorRef of the current actor."
  []
  (.selfRef *current-actor*))

(defn sender
  "Returns the ActorRef of the message sender."
  []
  (.senderRef *current-actor*))

(defn parent
  "Returns the ActorRef of the current actor's parent."
  []
  (.parentRef *current-actor*))

(defn context
  "Returns the current actor's ActorContext (valid only during message handling
   or init). Used for actor-selection, stop, sharding/passivate, etc."
  ^ActorContext []
  (.getContext *current-actor*))

(defn !
  "Send a message to an actor. Inside an actor context, sender is self.
   Outside, sender is noSender."
  [^ActorRef target msg]
  (if *current-actor*
    (.tell *current-actor* target msg)
    (.tell target msg (ActorRef/noSender))))

(defn reply
  "Reply to the sender of the current message. Returns nil (so it doesn't
   affect handler return value / state)."
  [msg]
  (.reply *current-actor* msg)
  nil)

(defn actor-system
  "Create a new ActorSystem, optionally named and/or with a
   com.typesafe.config.Config."
  ([] (ActorSystem/create))
  ([^String name] (ActorSystem/create name))
  ([^String name ^Config config] (ActorSystem/create name config)))

(defn shutdown-system
  "Terminate an ActorSystem and block until it has fully stopped (up to timeout-ms,
   default 30000). Returns the Terminated event, or nil on the block timeout."
  ([system] (shutdown-system system 30000))
  ([^ActorSystem system timeout-ms]
   (.terminate system)
   (try
     (.get (.toCompletableFuture (.getWhenTerminated system))
           (long timeout-ms) TimeUnit/MILLISECONDS)
     (catch TimeoutException _ nil))))

(defn- make-props
  "Given an actor-def map and args, produce a CljActor Props."
  [actor-def args]
  (CljActor/create ((:make-props actor-def) args)))

(defn actor-props
  "The raw Pekko Props for a `defactor` `actor-def` (optionally with init `args`).
   Use for advanced setups — decorate the returned Props with `.withMailbox` /
   `.withDispatcher` and spawn it with `spawn-props`."
  (^Props [actor-def] (make-props actor-def nil))
  (^Props [actor-def args] (make-props actor-def args)))

(defn spawn
  "Spawn a new actor.

   Inside actor context:
     (spawn actor-def)
     (spawn actor-def args)

   Top-level:
     (spawn system actor-def)
     (spawn system actor-def args)"
  ([actor-def]
   (spawn actor-def nil))
  ([first-arg second-arg]
   (if (instance? ActorSystem first-arg)
     ;; (spawn system actor-def) — top-level, no args
     (spawn first-arg second-arg nil)
     ;; (spawn actor-def args) — inside actor context
     (let [props (make-props first-arg second-arg)]
       (.actorOf ^ActorContext (.getContext *current-actor*) props))))
  ([^ActorSystem system actor-def args]
   (.actorOf system (make-props actor-def args))))

(defn spawn-props
  "Spawn an actor from a raw Pekko `Props` (e.g. one from `actor-props` decorated
   with `.withMailbox`/`.withDispatcher`). With one argument, spawns a child of the
   current actor; otherwise pass an ActorSystem or ActorContext."
  ([^Props props] (.actorOf (context) props))
  ([^ActorRefFactory factory ^Props props] (.actorOf factory props)))

(def ^:dynamic *timeout* 30000)

(defn <?>
  "Send a message and expect a reply. Returns a java.util.concurrent.CompletableFuture
   (a CompletionStage) — deref it with @, compose with .thenApply/.thenCompose, or
   block on it with <!. The future completes with the reply, or completes
   exceptionally if the actor's handler throws or the ask times out
   (AskTimeoutException). timeout is in milliseconds."
  ([target msg]
   (<?> target msg *timeout*))
  ([^ActorRef target msg timeout]
   (.toCompletableFuture
    (Patterns/ask target msg (Duration/ofMillis (long timeout))))))

(defn- ask-blocking
  "Block for the reply to (<?> target msg timeout).

   Distinguishes failure from timeout: a genuine failure (the ask future
   completing exceptionally with anything other than an AskTimeoutException — e.g.
   a Status/Failure reply) is rethrown unwrapped, so callers see the real error
   instead of a nil that looks like a timeout. A timeout (no reply within the
   window → AskTimeoutException, or the block guard elapsing) returns nil."
  [target msg timeout]
  (try
    ;; The ask has its own timeout, so it always completes; the +1000 block guard
    ;; only protects against a pathological never-completing future.
    (.get ^CompletableFuture (<?> target msg timeout) (+ (long timeout) 1000) TimeUnit/MILLISECONDS)
    (catch ExecutionException e
      (let [cause (or (.getCause e) e)]
        (if (instance? AskTimeoutException cause)
          nil
          (throw cause))))
    (catch TimeoutException _ nil)))

(defn <!
  "Blocking ask: send msg and block for the reply, returning it. BLOCKS the calling
   thread — never call it from inside an actor handler or on a dispatcher thread.

   If the actor's handler throws (or the ask fails), that exception is rethrown — it
   is NOT silently turned into nil. nil is returned only on a block timeout.

   Arities:
     (<! target msg)
     (<! target msg timeout-ms)
   A leading ActorSystem is accepted but ignored (legacy — no execution context is
   needed any more):
     (<! system target msg)
     (<! system target msg timeout-ms)"
  ([target msg]
   (ask-blocking target msg *timeout*))
  ([a b c]
   (if (instance? ActorSystem a)
     (ask-blocking b c *timeout*)   ; (<! system target msg)
     (ask-blocking a b c)))         ; (<! target msg timeout-ms)
  ([_system target msg timeout]
   (ask-blocking target msg timeout)))

(defn forward
  "Forward the current message to another actor, preserving original sender."
  [target msg]
  (.forward *current-actor* target msg))

(defn unhandled
  "Mark `msg` as unhandled: publishes it to the actor system's event stream as an
   UnhandledMessage (and, for an unwatched Terminated, throws DeathPactException).
   `defactor` calls this automatically for a message matching no `handle` clause,
   unless you supply your own catch-all. Returns nil (state is left unchanged)."
  [msg]
  (.unhandled *current-actor* msg)
  nil)

(defn become
  "Switch the current actor's behavior to another defactor's handler.
   Returns a BecomeResult that the runtime interprets."
  [actor-def new-state]
  (BecomeResult/of (:receive actor-def) new-state))

(defn new-actor
  "Create an actor from a raw function and initial state (low-level API)."
  ([^ActorRefFactory src props] (.actorOf src (CljActor/create props)))
  ([^ActorRefFactory src func initial] (.actorOf src (CljActor/create initial func))))

;; ---------------------------------------------------------------------------
;; Stopping actors
;; ---------------------------------------------------------------------------

(defn stop
  "Stop `target` (self or a child) via the current actor's context. Call inside a
   handler; the actor stops after the current message, children before the parent.
   Returns nil."
  [^ActorRef target]
  (.stop (context) target)
  nil)

(defn poison-pill
  "Send a PoisonPill to `target`, stopping it after it drains its mailbox. Works
   from anywhere (not only inside an actor). Returns nil."
  [^ActorRef target]
  (.tell target (PoisonPill/getInstance) (ActorRef/noSender))
  nil)

(defn graceful-stop
  "Ask `target` to stop; returns a CompletableFuture completing with true once it
   has terminated (or exceptionally with AskTimeoutException). timeout-ms optional."
  ([target] (graceful-stop target *timeout*))
  ([^ActorRef target timeout-ms]
   (.toCompletableFuture
    (Patterns/gracefulStop target (Duration/ofMillis (long timeout-ms))))))

;; ---------------------------------------------------------------------------
;; Actor selection
;; ---------------------------------------------------------------------------

(defn actor-selection
  "Look up an ActorSelection for a path string. One arg resolves relative to the
   current actor's context; two args resolve from a given ActorSystem or context."
  ([path] (actor-selection (context) path))
  ([^ActorRefFactory from ^String path] (.actorSelection from path)))

(defn identify
  "Resolve an ActorSelection to its ActorRef, returning a CompletableFuture (fails
   with ActorNotFound if nothing matches within timeout-ms, default *timeout*)."
  ([selection] (identify selection *timeout*))
  ([^ActorSelection selection timeout-ms]
   (.toCompletableFuture (.resolveOne selection (Duration/ofMillis (long timeout-ms))))))

(defn- catch-all-pattern?
  "True if a core.match `handle` pattern already matches every message — a bare
   local symbol (binds anything, e.g. `msg` or `_`) or the `:else` keyword — so
   the user has provided their own catch-all and `defactor` must not append one."
  [pattern]
  (or (= pattern :else)
      (symbol? pattern)))

(defn- parse-actor-clauses [body]
  (let [clauses (group-by first body)]
    {:init        (first (get clauses 'init))
     :handlers    (get clauses 'handle)
     :on-stop     (first (get clauses 'on-stop))
     :on-restart  (first (get clauses 'on-restart))
     :supervision (first (get clauses 'supervision))
     :on-error    (first (get clauses 'on-error))}))

(defmacro defactor
  "Define an actor. An optional docstring may follow `name`, then clauses:

   - (init [args] ...)      Compute the initial state from spawn args.
   - (handle pattern ...)   Handle a message matching `pattern` (core.match); the
                            body's value becomes the new state. `state` is bound
                            to the current state; use (become other-def st) to
                            switch behavior. A message matching no clause is sent
                            to Pekko's unhandled() (see `unhandled`) rather than
                            crashing, unless you supply your own catch-all.
   - (on-stop ...)          Run when the actor stops (post-stop).

   Reserved anaphor: `state` is implicitly bound to the current state inside
   handle/on-error bodies — do not shadow it with an init argument or on-error
   binding named `state` (defactor throws at macro-expansion if you do).
   - (supervision strat)    Supervisor strategy for this actor's children.
   - (on-error [ex msg] ...) Handle a recoverable Exception thrown while handling
                            a message; the body's value becomes the new state, so
                            the actor recovers IN PLACE and the parent's
                            supervisor strategy does NOT see the failure. Without
                            on-error, exceptions propagate to supervision. Errors
                            and InterruptedException always bypass on-error and
                            propagate (see pekko-clj.supervision)."
  [name & body]
  (let [;; optional docstring
        docstring (when (string? (first body)) (first body))
        clauses   (if docstring (rest body) body)
        parsed    (parse-actor-clauses clauses)

        ;; destructure init clause: (init [args] body...)
        init-clause  (:init parsed)
        init-params  (when init-clause (second init-clause))   ;; [args]
        init-body    (when init-clause (drop 2 init-clause))   ;; body...

        ;; destructure handle clauses: (handle pattern body...)
        handlers (:handlers parsed)
        ;; Build match pairs: pattern1 (do body1) pattern2 (do body2) ...
        match-pairs (mapcat (fn [h]
                              (let [pattern (second h)
                                    hbody   (drop 2 h)]
                                [pattern `(do ~@hbody)]))
                            handlers)
        ;; If the user didn't supply a catch-all, append a default that routes
        ;; unmatched messages to Pekko's unhandled() instead of throwing a
        ;; MatchError (which would crash/restart the actor). Mirrors the `:else`
        ;; branch in defactor-persistent.
        has-catch-all? (some catch-all-pattern? (map second handlers))

        ;; lifecycle
        on-stop    (:on-stop parsed)
        on-restart (:on-restart parsed)

        ;; supervision: (supervision strategy-expr)
        supervision-clause (:supervision parsed)
        supervision-expr   (when supervision-clause (second supervision-clause))

        ;; on-error: (on-error [ex msg] body...) - error handler
        on-error-clause (:on-error parsed)
        on-error-params (when on-error-clause (second on-error-clause))  ;; [ex msg]
        on-error-body   (when on-error-clause (drop 2 on-error-clause))  ;; body...

        ;; gensyms
        this-sym (gensym "this")
        msg-sym  (gensym "msg")
        args-sym (gensym "args")
        ex-sym   (gensym "ex")]

    ;; Reserved-anaphor guard: `state` is auto-bound to the current state in
    ;; handle/on-error bodies — don't let the init or on-error bindings shadow it.
    (when (some #{'state} init-params)
      (throw (ex-info (str "defactor " name ": `state` is a reserved binding (the "
                           "current state) — rename the init argument")
                      {:clause 'init :binding 'state})))
    (when (some #{'state} on-error-params)
      (throw (ex-info (str "defactor " name ": `state` is a reserved binding (the "
                           "current state) — rename the on-error binding")
                      {:clause 'on-error :binding 'state})))

    `(def ~(if docstring (vary-meta name assoc :doc docstring) name)
       (let [receive-fn#
             (fn [~this-sym ~msg-sym]
               (binding [*current-actor* ~this-sym]
                 (let [~'state (deref ~this-sym)]
                   (m/match ~msg-sym
                     ~@match-pairs
                     ~@(when-not has-catch-all?
                         [:else `(do (.unhandled ~this-sym ~msg-sym) nil)])))))]
         {:receive    receive-fn#
          :make-props (fn [~args-sym]
                        (merge
                         {:function receive-fn#}
                         ~(if init-clause
                            `{:pre-start
                              (fn [~this-sym]
                                (binding [*current-actor* ~this-sym]
                                  (let [~(first init-params) ~args-sym]
                                    ~@init-body)))}
                            `{:state ~args-sym})
                         ~(when on-stop
                            `{:post-stop
                              (fn [~this-sym]
                                (binding [*current-actor* ~this-sym]
                                  ~@(rest on-stop)))})
                         ~(when supervision-expr
                            `{:supervisor-strategy ~supervision-expr})
                         ~(when on-error-clause
                            `{:error-handler
                              (fn [~this-sym ~ex-sym ~msg-sym]
                                (binding [*current-actor* ~this-sym]
                                  (let [~'state (deref ~this-sym)
                                        ~(first on-error-params) ~ex-sym
                                        ~(second on-error-params) ~msg-sym]
                                    ~@on-error-body)))})))}))))

(defn schedule-once
  "Schedule a function to run once after a duration (java.time.Duration)."
  [duration f]
  (.scheduleOnce *current-actor* duration f))

;; Timer functions
(defn start-timer
  "Start a periodic timer that sends a message to self at fixed intervals.
   key: timer key for cancellation/checking
   interval: java.time.Duration between messages
   message: message to send to self
   Optional initial-delay: java.time.Duration before first message"
  ([key interval message]
   (.startTimer *current-actor* key interval message))
  ([key initial-delay interval message]
   (.startTimerWithInitialDelay *current-actor* key initial-delay interval message)))

(defn start-single-timer
  "Start a single-shot timer that sends a message to self after a delay.
   key: timer key for cancellation/checking
   delay: java.time.Duration before message is sent
   message: message to send to self"
  [key delay message]
  (.startSingleTimer *current-actor* key delay message))

(defn cancel-timer
  "Cancel a timer by key."
  [key]
  (.cancelTimer *current-actor* key))

(defn timer-active?
  "Check if a timer is active."
  [key]
  (.isTimerActive *current-actor* key))

(defn cancel-all-timers
  "Cancel all timers for this actor."
  []
  (.cancelAllTimers *current-actor*))

;; ReceiveTimeout
(def receive-timeout
  "The Pekko ReceiveTimeout singleton — the message an actor receives when its
   receive-timeout elapses (see `set-receive-timeout`). Match it with
   `receive-timeout?`."
  (ReceiveTimeout/getInstance))

(defn receive-timeout?
  "True if `msg` is the ReceiveTimeout message."
  [msg]
  (identical? msg receive-timeout))

(defn set-receive-timeout
  "Arrange for the current actor to be sent the `receive-timeout` message if it
   receives no message for `timeout` (a java.time.Duration or a number of
   milliseconds). Any received message resets the timer. Setting a new timeout
   replaces the previous one; use `cancel-receive-timeout` to turn it off. Call
   during init or a handler. Returns nil.

   Note: the timer is scheduled with the arrival of the last message and is not
   reset by cancel/reschedule while a message is being processed."
  [timeout]
  (let [ms (if (instance? Duration timeout) (.toMillis ^Duration timeout) (long timeout))]
    (.setReceiveTimeout (context)
                        (scala.concurrent.duration.Duration/create ms TimeUnit/MILLISECONDS)))
  nil)

(defn cancel-receive-timeout
  "Disable the current actor's receive timeout. Returns nil."
  []
  (.setReceiveTimeout (context) (scala.concurrent.duration.Duration/Undefined))
  nil)

;; DeathWatch functions
(defn watch
  "Watch an actor for termination. When the watched actor stops,
   this actor will receive [:terminated actor-ref]."
  [actor-ref]
  (.watch *current-actor* actor-ref))

(defn unwatch
  "Stop watching an actor for termination."
  [actor-ref]
  (.unwatch *current-actor* actor-ref))

;; Stash functions
(defn stash
  "Stash the current message for later processing.
   Use this to defer handling of messages until the actor is ready.
   Returns nil (doesn't affect state)."
  []
  (.stash *current-actor*)
  nil)

(defn unstash-all
  "Unstash all messages, prepending them to the mailbox.
   Messages will be processed in the order they were stashed.
   Returns nil (doesn't affect state)."
  []
  (.unstashAll *current-actor*)
  nil)

(defn unstash
  "Unstash the first stashed message only.
   Returns nil (doesn't affect state)."
  []
  (.unstash *current-actor*)
  nil)

(defn stash-size
  "Returns the number of stashed messages."
  []
  (.stashSize *current-actor*))

(defn clear-stash
  "Clear all stashed messages without processing them.
   Returns nil (doesn't affect state)."
  []
  (.clearStash *current-actor*)
  nil)

;; Reset so the flag doesn't leak into namespaces compiled after this one.
(set! *warn-on-reflection* false)
