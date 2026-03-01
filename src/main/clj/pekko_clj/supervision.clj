(ns pekko-clj.supervision
  "Supervision strategies for pekko-clj actors.

   Supervision strategies determine how a parent actor responds when a child
   actor fails (throws an exception). Available directives:
   - :resume   - Resume the actor, keeping its accumulated state
   - :restart  - Restart the actor, clearing its state
   - :stop     - Stop the actor permanently
   - :escalate - Escalate the failure to the parent's supervisor"
  (:import [pekko_clj.actor CljSupervisorStrategy]))

(defn one-for-one
  "Create a one-for-one supervision strategy.

   Only the failing child actor is affected by the directive.

   Options:
   - :max-retries - Maximum number of restarts within the time window (default: -1, infinite)
   - :within-ms   - Time window in milliseconds (default: -1, infinite)

   The decider is a function (fn [throwable] :directive) that returns one of:
   :resume, :restart, :stop, or :escalate

   Example:
     (one-for-one
       {:max-retries 10 :within-ms 60000}
       (fn [ex]
         (cond
           (instance? ArithmeticException ex) :resume
           (instance? NullPointerException ex) :restart
           :else :escalate)))"
  ([decider]
   (one-for-one {} decider))
  ([{:keys [max-retries within-ms] :or {max-retries -1 within-ms -1}} decider]
   (CljSupervisorStrategy/oneForOne max-retries within-ms decider)))

(defn all-for-one
  "Create an all-for-one supervision strategy.

   When one child fails, all children are affected by the directive.

   Options:
   - :max-retries - Maximum number of restarts within the time window (default: -1, infinite)
   - :within-ms   - Time window in milliseconds (default: -1, infinite)

   The decider is a function (fn [throwable] :directive) that returns one of:
   :resume, :restart, :stop, or :escalate

   Example:
     (all-for-one
       {:max-retries 3 :within-ms 30000}
       (fn [ex] :restart))"
  ([decider]
   (all-for-one {} decider))
  ([{:keys [max-retries within-ms] :or {max-retries -1 within-ms -1}} decider]
   (CljSupervisorStrategy/allForOne max-retries within-ms decider)))

(defn restart-decider
  "A simple decider that always restarts on any exception."
  [_ex]
  :restart)

(defn resume-decider
  "A simple decider that always resumes on any exception."
  [_ex]
  :resume)

(defn stop-decider
  "A simple decider that always stops on any exception."
  [_ex]
  :stop)

(defn escalate-decider
  "A simple decider that always escalates on any exception."
  [_ex]
  :escalate)

(defn default-strategy
  "Returns the default Pekko supervisor strategy."
  []
  (CljSupervisorStrategy/defaultStrategy))
