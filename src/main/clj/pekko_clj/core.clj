(ns pekko-clj.core
  (:require [clojure.core.match :as m])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.pattern Patterns]
           [pekko_clj.actor CljActor BecomeResult FnWrapper]))

(def ^:dynamic *current-actor*
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

(defn !
  "Send a message to an actor. Inside an actor context, sender is self.
   Outside, sender is noSender."
  [target msg]
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
  "Create a new ActorSystem."
  ([] (ActorSystem/create))
  ([name] (ActorSystem/create name)))

(defn- make-props
  "Given an actor-def map and args, produce a CljActor Props."
  [actor-def args]
  (CljActor/create ((:make-props actor-def) args)))

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
       (.actorOf (.getContext *current-actor*) props))))
  ([system actor-def args]
   (.actorOf system (make-props actor-def args))))

(def ^:dynamic *timeout* 30000)

(defn <?>
  "Send a message and expect a reply. Returns a Scala Future.
   Use @(<?> actor msg) with a FnWrapper callback, or see <! for blocking."
  ([target msg]
   (<?> target msg *timeout*))
  ([target msg timeout]
   (Patterns/ask target msg (long timeout))))

(defn <!
  "Blocking ask. Requires an actor-system for the execution context."
  ([system target msg]
   (<! system target msg *timeout*))
  ([system target msg timeout]
   (let [result (promise)
         future (<?> target msg timeout)]
     (.onComplete
      future
      (FnWrapper/create #(deliver result (.get %)))
      (.dispatcher system))
     (deref result timeout nil))))

(defn become
  "Switch the current actor's behavior to another defactor's handler.
   Returns a BecomeResult that the runtime interprets."
  [actor-def new-state]
  (BecomeResult/of (:receive actor-def) new-state))

(defn new-actor
  "Create an actor from a raw function and initial state (low-level API)."
  ([src props] (.actorOf src (CljActor/create props)))
  ([src func initial] (.actorOf src (CljActor/create initial func))))

(defn- parse-actor-clauses [body]
  (let [clauses (group-by first body)]
    {:init       (first (get clauses 'init))
     :handlers   (get clauses 'handle)
     :on-stop    (first (get clauses 'on-stop))
     :on-restart (first (get clauses 'on-restart))}))

(defmacro defactor [name & body]
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

        ;; lifecycle
        on-stop    (:on-stop parsed)
        on-restart (:on-restart parsed)

        ;; gensyms
        this-sym (gensym "this")
        msg-sym  (gensym "msg")
        args-sym (gensym "args")]

    `(def ~(vary-meta name assoc :doc (or docstring ""))
       (let [receive-fn#
             (fn [~this-sym ~msg-sym]
               (binding [*current-actor* ~this-sym]
                 (let [~'state (deref ~this-sym)]
                   (m/match ~msg-sym
                     ~@match-pairs))))]
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
                                  ~@(rest on-stop)))})))}))))

(defn schedule-once
  "Schedule a function to run once after a duration (java.time.Duration)."
  [duration f]
  (.scheduleOnce *current-actor* duration f))

(defn forward
  "Forward the current message to another actor, preserving original sender."
  [target msg]
  (.forward *current-actor* target msg))
