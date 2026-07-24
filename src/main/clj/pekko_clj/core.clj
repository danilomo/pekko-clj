(ns pekko-clj.core
  (:require [clojure.core.match :as m]
            [pekko-clj.internal.context :as ctx])
  (:import [org.apache.pekko.actor ActorSystem ActorRef ActorRefFactory ActorContext
            ActorSelection PoisonPill Props ReceiveTimeout]
           [org.apache.pekko.pattern Patterns AskTimeoutException]
           [pekko_clj.actor CljActor BecomeResult]
           [com.typesafe.config Config]
           [java.time Duration]
           [java.util.concurrent TimeUnit ExecutionException TimeoutException
            CancellationException CompletableFuture]))

(def ^{:dynamic true :tag CljActor} *current-actor*
  "Bound to the current CljActor instance during message handling.
   Used by !, reply, sender, self, parent, spawn."
  nil)

(defn- current-actor
  "The current CljActor, or a friendly IllegalStateException if called outside an
   actor handler / init (where *current-actor* is nil — a bare NPE otherwise).
   `fn-name` is the public fn being guarded. Allocation-free on the bound path."
  ^CljActor [fn-name]
  (or *current-actor*
      (throw (IllegalStateException.
              (str "pekko-clj.core/" fn-name " must be called inside an actor "
                   "handler or init (there is no current actor here)")))))

(defn self
  "Returns the ActorRef of the current actor."
  []
  (.selfRef (current-actor "self")))

(defn sender
  "Returns the ActorRef of the message sender."
  []
  (.senderRef (current-actor "sender")))

(defn parent
  "Returns the ActorRef of the current actor's parent."
  []
  (.parentRef (current-actor "parent")))

(defn context
  "Returns the current actor's ActorContext (valid only during message handling
   or init). Used for actor-selection, stop, sharding/passivate, etc."
  ^ActorContext []
  (.getContext (current-actor "context")))

(defn !
  "Send a message to an actor. Inside any actor handler the sender is self, so the
   recipient can `reply`; outside an actor the sender is noSender.

   The self resolution works in every actor kind: a classic `defactor` binds
   `*current-actor*` (the fast path here), while `defactor-persistent` /
   `defactor-delivery` bind only `pekko-clj.internal.context/*current-self*` — so
   without the second branch `!` (and `sharding/tell`, which routes through it)
   would send as noSender inside a persistent/delivery body and replies would go
   to dead letters."
  [^ActorRef target msg]
  (if *current-actor*
    (.tell *current-actor* target msg)
    (let [^ActorRef sender (or ctx/*current-self* (ActorRef/noSender))]
      (.tell target msg sender))))

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

(defn- actor-of
  "actorOf via the (Props) or (Props, String) overload, depending on whether
   name is given."
  ^ActorRef [^ActorRefFactory factory ^Props props name]
  (if name
    (.actorOf factory props ^String name)
    (.actorOf factory props)))

(defn spawn
  "Spawn a new actor. An optional trailing opts map supports {:name \"child-name\"}
   to give the actor a stable path (e.g. \"/user/child-name\"), which is what lets
   actor-selection and group routers address it directly.

   Inside actor context:
     (spawn actor-def)
     (spawn actor-def args)
     (spawn actor-def args opts)

   Top-level:
     (spawn system actor-def)
     (spawn system actor-def args)
     (spawn system actor-def args opts)"
  ([actor-def]
   (spawn actor-def nil))
  ([first-arg second-arg]
   (if (instance? ActorSystem first-arg)
     ;; (spawn system actor-def) — top-level, no args
     (spawn first-arg second-arg nil)
     ;; (spawn actor-def args) — inside actor context
     (actor-of (.getContext *current-actor*) (make-props first-arg second-arg) nil)))
  ([first-arg second-arg third-arg]
   (if (instance? ActorSystem first-arg)
     ;; (spawn system actor-def args) — top-level
     (actor-of first-arg (make-props second-arg third-arg) nil)
     ;; (spawn actor-def args opts) — inside actor context, optionally named
     (actor-of (.getContext *current-actor*) (make-props first-arg second-arg) (:name third-arg))))
  ([^ActorSystem system actor-def args opts]
   (actor-of system (make-props actor-def args) (:name opts))))

(defn spawn-props
  "Spawn an actor from a raw Pekko `Props` (e.g. one from `actor-props` decorated
   with `.withMailbox`/`.withDispatcher`). With one argument, spawns a child of the
   current actor; otherwise pass an ActorSystem or ActorContext. An optional
   trailing opts map supports {:name \"child-name\"}."
  ([^Props props] (actor-of (context) props nil))
  ([^ActorRefFactory factory ^Props props] (actor-of factory props nil))
  ([^ActorRefFactory factory ^Props props opts] (actor-of factory props (:name opts))))

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
   window → AskTimeoutException, or the block guard elapsing) returns nil, and so
   does a cancelled future (CancellationException) — there's no reply to return
   either way, so it's treated the same as a timeout rather than escaping raw."
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
    (catch TimeoutException _ nil)
    (catch CancellationException _ nil)))

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
   UnhandledMessage. For a watched actor's Terminated that no clause consumed,
   this throws DeathPactException instead (Pekko's death-pact contract), which by
   default stops the watcher — even though `defactor` presents Terminated to
   handlers as a [:terminated ref] vector, the raw Terminated is restored here.
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

(def ^:private actor-clause-heads
  '#{init handle on-stop on-restart supervision on-error})

(def ^:private actor-singleton-clause-heads
  "Clauses that may appear at most once; handle is the only clause allowed to
   repeat, so it's excluded here."
  '#{init on-stop on-restart supervision on-error})

(defn- validate-actor-clauses
  "Throw at macro-expansion for common defactor authoring mistakes that
   `parse-actor-clauses`'s `group-by` would otherwise silently swallow: an
   unknown clause head (a typo like `(on-stap ...)` or `(handel ...)`), or a
   duplicate of a clause that may only appear once."
  [name body]
  (doseq [clause body]
    ;; A stray non-list clause (a bare keyword/string in the body) would make
    ;; (first clause) throw "Don't know how to create ISeq"; name it instead.
    (when-not (seq? clause)
      (throw (ex-info (str "defactor " name ": unknown clause `" (pr-str clause)
                           "` — every clause must be a list like (init ...) or (handle ...)")
                      {:clause clause})))
    (let [head (first clause)]
      (when-not (contains? actor-clause-heads head)
        (throw (ex-info (str "defactor " name ": unknown clause `" (pr-str clause)
                             "` — expected one of init, handle, on-stop, on-restart, "
                             "supervision, on-error")
                        {:clause head})))))
  (let [grouped (group-by first body)]
    (doseq [head actor-singleton-clause-heads]
      (let [n (count (get grouped head))]
        (when (> n 1)
          (throw (ex-info (str "defactor " name ": only one `" head "` clause is allowed, found " n)
                          {:clause head :count n})))))))

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
   - (on-stop ...)          Run when the actor stops (post-stop). Also runs on
                            the OLD instance during a supervised restart.
   - (on-restart [reason] ...) Run on the FRESH instance after a supervised
                            restart, once init has re-run. `reason` (optional
                            binding) is the Throwable that caused the restart;
                            `state` is bound to the re-initialized state and the
                            body's value becomes the new state (nil leaves it
                            unchanged). A restart fires on-stop (old instance)
                            then on-restart (new instance).

   Reserved anaphor: `state` is implicitly bound to the current state inside
   handle/on-error/on-restart bodies — do not shadow it with an init argument or
   an on-error/on-restart binding named `state` (defactor throws at
   macro-expansion if you do).
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
        _         (validate-actor-clauses name clauses)
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

        ;; on-restart: (on-restart [reason] body...) or (on-restart body...) —
        ;; runs on the fresh instance after a supervised restart.
        on-restart-clause  (:on-restart parsed)
        on-restart-binding (when (and on-restart-clause (vector? (second on-restart-clause)))
                             (second on-restart-clause))
        on-restart-body    (when on-restart-clause
                             (if on-restart-binding
                               (drop 2 on-restart-clause)
                               (rest on-restart-clause)))

        ;; supervision: (supervision strategy-expr)
        supervision-clause (:supervision parsed)
        supervision-expr   (when supervision-clause (second supervision-clause))

        ;; on-error: (on-error [ex msg] body...) - error handler
        on-error-clause (:on-error parsed)
        on-error-params (when on-error-clause (second on-error-clause))  ;; [ex msg]
        on-error-body   (when on-error-clause (drop 2 on-error-clause))  ;; body...

        ;; gensyms
        ;; Tagged so the generated code calls CljActor methods directly. The tag is
        ;; fully qualified because it is emitted into the caller's namespace, which
        ;; need not import CljActor — without it every defactor body in every
        ;; downstream project reflects on .unhandled and deref.
        this-sym   (with-meta (gensym "this") {:tag 'pekko_clj.actor.CljActor})
        msg-sym    (gensym "msg")
        args-sym   (gensym "args")
        ex-sym     (gensym "ex")
        reason-sym (gensym "reason")]

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
    (when (some #{'state} on-restart-binding)
      (throw (ex-info (str "defactor " name ": `state` is a reserved binding (the "
                           "current state) — rename the on-restart binding")
                      {:clause 'on-restart :binding 'state})))
    (when (and on-error-clause
               (not (and (vector? on-error-params) (= 2 (count on-error-params)))))
      (throw (ex-info (str "defactor " name ": (on-error [ex msg] ...) needs a 2-element "
                           "binding vector, got " (pr-str on-error-params))
                      {:clause 'on-error :binding on-error-params})))

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
                         ~(when on-restart-clause
                            `{:post-restart
                              (fn [~this-sym ~reason-sym]
                                (binding [*current-actor* ~this-sym]
                                  (let [~'state (deref ~this-sym)
                                        ~@(when on-restart-binding
                                            [(first on-restart-binding) reason-sym])]
                                    ~@on-restart-body)))})
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

(defn- ->duration
  "Coerce a java.time.Duration or a number of milliseconds to a Duration.
   Mirrors pekko-clj.stream's private helper so the timer/schedule fns accept
   either (the ms-or-Duration convention used across the library)."
  ^Duration [d]
  (if (instance? Duration d) d (Duration/ofMillis (long d))))

(defn schedule-once
  "Schedule a function to run once after a delay (a java.time.Duration or a number
   of milliseconds)."
  [duration f]
  (.scheduleOnce (current-actor "schedule-once") (->duration duration) f))

;; Timer functions
(defn start-timer
  "Start a periodic timer that sends a message to self at fixed intervals.
   key: timer key for cancellation/checking
   interval: ms or java.time.Duration between messages
   message: message to send to self
   Optional initial-delay: ms or java.time.Duration before first message"
  ([key interval message]
   (.startTimer (current-actor "start-timer") key (->duration interval) message))
  ([key initial-delay interval message]
   (.startTimerWithInitialDelay (current-actor "start-timer") key (->duration initial-delay)
                                (->duration interval) message)))

(defn start-single-timer
  "Start a single-shot timer that sends a message to self after a delay.
   key: timer key for cancellation/checking
   delay: ms or java.time.Duration before message is sent
   message: message to send to self"
  [key delay message]
  (.startSingleTimer (current-actor "start-single-timer") key (->duration delay) message))

(defn cancel-timer
  "Cancel a timer by key."
  [key]
  (.cancelTimer (current-actor "cancel-timer") key))

(defn timer-active?
  "Check if a timer is active."
  [key]
  (.isTimerActive (current-actor "timer-active?") key))

(defn cancel-all-timers
  "Cancel all timers for this actor."
  []
  (.cancelAllTimers (current-actor "cancel-all-timers")))

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
  "Watch an actor for termination.

   - (watch actor-ref)     — when the watched actor stops, this actor receives
     [:terminated actor-ref].
   - (watch actor-ref msg) — Pekko's watchWith: this actor receives `msg` (as-is)
     instead, so a custom marker can ride along (e.g. which child died and why).
     `msg` is delivered through the normal handler and is NOT translated to a
     [:terminated ...] vector."
  ([actor-ref]
   (.watch *current-actor* actor-ref))
  ([actor-ref msg]
   (.watchWith *current-actor* actor-ref msg)))

(defn unwatch
  "Stop watching an actor for termination."
  [actor-ref]
  (.unwatch *current-actor* actor-ref))

;; Stash functions
(defn stash
  "Stash the current message for later processing.
   Use this to defer handling of messages until the actor is ready.
   Returns nil (doesn't affect state).

   Lifecycle: stashed messages survive a supervised restart — they are put back
   in the mailbox before the old instance is discarded, so the fresh instance
   receives them (at the tail, see `unstash-all`'s ordering note). When the actor
   stops for good, whatever is still stashed becomes dead letters, visible via
   `pekko-clj.event-stream/subscribe-dead-letters`."
  []
  (.stash (current-actor "stash"))
  nil)

(defn unstash-all
  "Re-enqueue all stashed messages, in the order they were stashed, each with its
   original sender. Returns nil (doesn't affect state).

   Note: unlike Pekko's Stash (which prepends to the mailbox front), this re-sends
   the messages to self, so they land at the TAIL of the mailbox — after any
   messages that are already queued. For the common 'stash until ready, then
   unstash' pattern this is equivalent; it differs only if other messages queued
   up between stashing and unstashing and their relative order matters."
  []
  (.unstashAll (current-actor "unstash-all"))
  nil)

(defn unstash
  "Re-enqueue the first stashed message only (with its original sender), placing it
   at the tail of the mailbox (see `unstash-all` for the ordering note).
   Returns nil (doesn't affect state)."
  []
  (.unstash (current-actor "unstash"))
  nil)

(defn stash-size
  "Returns the number of stashed messages."
  []
  (.stashSize (current-actor "stash-size")))

(defn clear-stash
  "Clear all stashed messages without processing them.
   Returns nil (doesn't affect state)."
  []
  (.clearStash (current-actor "clear-stash"))
  nil)
