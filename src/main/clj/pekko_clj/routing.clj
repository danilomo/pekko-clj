(ns pekko-clj.routing
  "Router support for pekko-clj actors.

   Routers distribute messages across multiple actor instances (routees).

   Pool routers: Create and manage a pool of routee actors
   Group routers: Route to a group of existing actors at specified paths

   Strategies (spawn-pool / spawn-group / spawn-cluster-pool / spawn-cluster-group):
   - :round-robin       - Rotates through routees sequentially (default)
   - :random            - Randomly selects a routee
   - :broadcast         - Sends to all routees
   - :smallest-mailbox  - Sends to routee with fewest queued messages (pool only)
   - :balancing         - All routees share a single mailbox (pool only, work-stealing)

   Consistent hashing is not a :strategy value here — it needs a hash function,
   so it has its own functions: spawn-consistent-hash-pool / -group.

   Additional routers (each has a pool and a group form):
   - scatter-gather     - Send to all, return first response
     (spawn-scatter-gather-pool / spawn-scatter-gather-group)
   - tail-chopping      - Latency reduction via speculative sends
     (spawn-tail-chopping-pool / spawn-tail-chopping-group)
   - cluster-pool/group - Cluster-aware routers across nodes

   Supervision / dispatcher (pools only): every pool spawner accepts
   :supervisor-strategy (a pekko-clj.supervision strategy — pools supervise their
   routees; the default is Pekko's escalate) and :dispatcher (the name of a
   configured dispatcher for the routees). Groups route to actors that already
   exist, so they own neither."
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorRef ActorRefFactory Props SupervisorStrategy]
           [org.apache.pekko.routing Pool Group
            RoundRobinPool RoundRobinGroup
            RandomPool RandomGroup
            BroadcastPool BroadcastGroup
            SmallestMailboxPool BalancingPool
            ConsistentHashingPool ConsistentHashingGroup
            ConsistentHashingRouter$ConsistentHashMapper
            ScatterGatherFirstCompletedPool ScatterGatherFirstCompletedGroup
            TailChoppingPool TailChoppingGroup
            DefaultResizer
            AddRoutee RemoveRoutee AdjustPoolSize
            ActorRefRoutee]
           [org.apache.pekko.cluster.routing ClusterRouterPool ClusterRouterPoolSettings
            ClusterRouterGroup ClusterRouterGroupSettings]
           [scala.concurrent.duration FiniteDuration]
           [java.time Duration]
           [java.util.concurrent TimeUnit]
           [pekko_clj.actor CljActor]))

(defn- make-props
  "Create Props from an actor-def and args."
  ^Props [actor-def args]
  (CljActor/create ((:make-props actor-def) args)))

(defn- actor-of
  "actorOf via the (Props) or (Props, String) overload, depending on whether
   name is given. factory is an ActorSystem for a top-level router, or an
   ActorRefFactory (e.g. (core/context) inside an actor) to spawn it as a child."
  ^ActorRef [^ActorRefFactory factory ^Props props name]
  (if name
    (.actorOf factory props ^String name)
    (.actorOf factory props)))

(defmacro ^:private configure-pool
  "Apply the optional `supervisor-strategy` (`.withSupervisorStrategy`) and
   `dispatcher` (`.withDispatcher`) withers to a freshly built pool.

   These withers are declared on each concrete pool class, not on the `Pool`
   interface (the same reason `pool-with-resizer` re-dispatches on the concrete
   type), so `pool-ctor` is inlined into each branch as its constructor
   expression — the wither calls resolve against the concrete type and stay
   reflection-free. `pool-ctor` must therefore be a side-effect-free constructor
   form; only the matching branch evaluates it."
  [pool-ctor supervisor-strategy dispatcher]
  `(let [ss# ~supervisor-strategy
         d#  ~dispatcher]
     (cond
       (and ss# d#) (-> ~pool-ctor (.withSupervisorStrategy ^SupervisorStrategy ss#) (.withDispatcher ^String d#))
       ss#          (.withSupervisorStrategy ~pool-ctor ^SupervisorStrategy ss#)
       d#           (.withDispatcher ~pool-ctor ^String d#)
       :else        ~pool-ctor)))

(defn- strategy->pool
  "Convert a strategy keyword to a Pool router, applying the optional
   `supervisor-strategy` / `dispatcher` withers. `nil` strategy selects the
   default, :round-robin; any other unrecognized keyword throws."
  ^Pool [strategy size supervisor-strategy dispatcher]
  (let [n (int size)]
    (case strategy
      (nil :round-robin) (configure-pool (RoundRobinPool. n) supervisor-strategy dispatcher)
      :random (configure-pool (RandomPool. n) supervisor-strategy dispatcher)
      :broadcast (configure-pool (BroadcastPool. n) supervisor-strategy dispatcher)
      :smallest-mailbox (configure-pool (SmallestMailboxPool. n) supervisor-strategy dispatcher)
      :balancing (configure-pool (BalancingPool. n) supervisor-strategy dispatcher)
      (throw (IllegalArgumentException.
              (str "Unknown pool strategy: " (pr-str strategy)
                   ". Valid options: :round-robin, :random, :broadcast, "
                   ":smallest-mailbox, :balancing (or nil for the default, "
                   ":round-robin)."))))))

(defn- strategy->group
  "Convert a strategy keyword to a Group router. `nil` selects the default,
   :round-robin; any other unrecognized keyword throws."
  ^Group [strategy paths]
  (let [path-list (java.util.ArrayList. ^java.util.Collection paths)]
    (case strategy
      (nil :round-robin) (RoundRobinGroup. path-list)
      :random (RandomGroup. path-list)
      :broadcast (BroadcastGroup. path-list)
      (throw (IllegalArgumentException.
              (str "Unknown group strategy: " (pr-str strategy)
                   ". Valid options: :round-robin, :random, :broadcast "
                   "(or nil for the default, :round-robin)."))))))

(defn- role-set
  "The role set Pekko's cluster router settings take — empty when no role given."
  ^java.util.Set [role]
  (let [s (java.util.HashSet.)]
    (when role (.add s role))
    s))

(defn- pool-with-resizer
  "A pool of `strategy` with `resizer` attached, plus the optional
   `supervisor-strategy` / `dispatcher` withers.

   `withResizer` is declared on each concrete pool class, not on the `Pool`
   interface, so the strategy is re-dispatched here to keep every call direct.
   BalancingPool does not declare it at all — its routees share one mailbox, so
   there is nothing per-routee to resize — and is rejected with a clear message
   rather than an opaque \"no matching method\" from the reflective call."
  ^Pool [strategy size ^DefaultResizer resizer supervisor-strategy dispatcher]
  (let [n (int size)]
    (case strategy
      :random           (configure-pool (.withResizer (RandomPool. n) resizer) supervisor-strategy dispatcher)
      :broadcast        (configure-pool (.withResizer (BroadcastPool. n) resizer) supervisor-strategy dispatcher)
      :smallest-mailbox (configure-pool (.withResizer (SmallestMailboxPool. n) resizer) supervisor-strategy dispatcher)
      :balancing (throw (IllegalArgumentException.
                         (str ":balancing pools cannot be resized — all routees share a "
                              "single mailbox. Use :round-robin, :random, :broadcast or "
                              ":smallest-mailbox with :min-size/:max-size.")))
      (configure-pool (.withResizer (RoundRobinPool. n) resizer) supervisor-strategy dispatcher))))

(defn spawn-pool
  "Create a pool router that spawns and manages N worker actors.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - actor-def: Actor definition created with defactor
   - size: Number of worker instances to create
   - opts: Options map (optional)
     - :strategy - Routing strategy (:round-robin, :random, :broadcast, :smallest-mailbox)
     - :args - Arguments to pass to each worker's init
     - :name - Name for the router actor, so it's addressable by path
     - :supervisor-strategy - A pekko-clj.supervision strategy applied to the
       routees (default: Pekko's escalate). See the ns docstring's Supervision note.
     - :dispatcher - Name of a configured dispatcher for the routees

   Returns an ActorRef for the router.

   Example:
     (def pool (spawn-pool sys worker-actor 5))
     (def pool (spawn-pool sys worker-actor 5 {:strategy :random}))
     (! pool :work) ; Routes to one worker"
  ([system actor-def size]
   (spawn-pool system actor-def size {}))
  ([^ActorRefFactory system actor-def size {:keys [strategy args name supervisor-strategy dispatcher]
                                            :or {strategy :round-robin args nil}}]
   (let [props (make-props actor-def args)
         router (strategy->pool strategy size supervisor-strategy dispatcher)
         router-props (.props router props)]
     (actor-of system router-props name))))

(defn spawn-group
  "Create a group router that routes to existing actors at specified paths.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - paths: Collection of actor paths (strings like \"/user/worker1\")
   - opts: Options map (optional)
     - :strategy - Routing strategy (:round-robin, :random, :broadcast)
     - :name - Name for the router actor, so it's addressable by path

   Returns an ActorRef for the router.

   Note: The actors at the specified paths must already exist.

   Example:
     (def group (spawn-group sys [\"/user/w1\" \"/user/w2\" \"/user/w3\"]))
     (def group (spawn-group sys paths {:strategy :broadcast}))
     (! group :work) ; Routes to existing workers"
  ([system paths]
   (spawn-group system paths {}))
  ([^ActorRefFactory system paths {:keys [strategy name] :or {strategy :round-robin}}]
   (let [router (strategy->group strategy paths)
         props (.props router)]
     (actor-of system props name))))

(defn broadcast
  "Send a message to all routees via a broadcast router.
   This works with any router type - wraps the message in a Broadcast envelope.

   Example:
     (broadcast router :shutdown) ; Sends to ALL routees"
  [router msg]
  (core/! router (org.apache.pekko.routing.Broadcast. msg)))

(defn get-routees
  "Get information about the current routees of a router.
   Sends a GetRoutees message and returns a future.

   Example:
     (let [future (get-routees router)]
       ; Process routees info...)"
  [router]
  (core/<?> router (org.apache.pekko.routing.GetRoutees/getInstance)))

;; ---------------------------------------------------------------------------
;; Consistent Hashing Routers
;; ---------------------------------------------------------------------------

(defn- make-hash-mapper
  "Create a ConsistentHashMapper from a Clojure function."
  [hash-fn]
  (reify ConsistentHashingRouter$ConsistentHashMapper
    (hashKey [_ msg]
      (hash-fn msg))))

(defn spawn-consistent-hash-pool
  "Create a pool router with consistent hashing.

   Messages with the same hash key are always routed to the same routee.
   This is essential for stateful routing patterns.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - actor-def: Actor definition
   - size: Number of routees
   - opts: Options map
     - :hash-fn - Function (msg) -> hash-key (required)
     - :virtual-nodes - Virtual nodes per routee (default: 10)
     - :args - Arguments for actor init
     - :name - Name for the router actor, so it's addressable by path
     - :supervisor-strategy - A pekko-clj.supervision strategy for the routees
     - :dispatcher - Name of a configured dispatcher for the routees

   Example:
     (spawn-consistent-hash-pool sys worker-actor 5
       {:hash-fn (fn [msg] (:user-id msg))
        :virtual-nodes 100})"
  [^ActorRefFactory system actor-def size {:keys [hash-fn virtual-nodes args name
                                                  supervisor-strategy dispatcher]
                                           :or {virtual-nodes 10}}]
  (when-not hash-fn
    (throw (IllegalArgumentException. ":hash-fn is required for consistent-hash-pool")))
  (let [props (make-props actor-def args)
        mapper (make-hash-mapper hash-fn)
        pool (configure-pool (-> (ConsistentHashingPool. (int size))
                                 (.withVirtualNodesFactor virtual-nodes)
                                 (.withHashMapper mapper))
                             supervisor-strategy dispatcher)
        router-props (.props pool props)]
    (actor-of system router-props name)))

(defn spawn-consistent-hash-group
  "Create a group router with consistent hashing.

   Routes to existing actors at specified paths using consistent hashing.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - paths: Collection of actor paths
   - opts: Options map
     - :hash-fn - Function (msg) -> hash-key (required)
     - :virtual-nodes - Virtual nodes per routee (default: 10)
     - :name - Name for the router actor, so it's addressable by path

   Example:
     (spawn-consistent-hash-group sys [\"/user/w1\" \"/user/w2\"]
       {:hash-fn (fn [msg] (:session-id msg))})"
  [^ActorRefFactory system paths {:keys [hash-fn virtual-nodes name]
                                  :or {virtual-nodes 10}}]
  (when-not hash-fn
    (throw (IllegalArgumentException. ":hash-fn is required for consistent-hash-group")))
  (let [path-list (java.util.ArrayList. ^java.util.Collection paths)
        mapper (make-hash-mapper hash-fn)
        group (-> (ConsistentHashingGroup. ^java.util.Collection path-list)
                  (.withVirtualNodesFactor virtual-nodes)
                  (.withHashMapper mapper))
        props (.props group)]
    (actor-of system props name)))

;; ---------------------------------------------------------------------------
;; Scatter-Gather Router
;; ---------------------------------------------------------------------------

(defn spawn-scatter-gather-pool
  "Create a scatter-gather pool that returns the first response.

   Sends message to all routees and returns the first response
   received within the timeout.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - actor-def: Actor definition
   - size: Number of routees
   - opts: Options map
     - :timeout-ms - Timeout for gathering responses (required)
     - :args - Arguments for actor init
     - :name - Name for the router actor, so it's addressable by path
     - :supervisor-strategy - A pekko-clj.supervision strategy for the routees
     - :dispatcher - Name of a configured dispatcher for the routees

   Example:
     (spawn-scatter-gather-pool sys search-actor 3
       {:timeout-ms 5000})"
  [^ActorRefFactory system actor-def size {:keys [timeout-ms args name supervisor-strategy dispatcher]}]
  (when-not timeout-ms
    (throw (IllegalArgumentException. ":timeout-ms is required for scatter-gather-pool")))
  (let [props (make-props actor-def args)
        timeout (FiniteDuration/create (long timeout-ms) TimeUnit/MILLISECONDS)
        pool (configure-pool (ScatterGatherFirstCompletedPool. (int size) timeout)
                             supervisor-strategy dispatcher)
        router-props (.props pool props)]
    (actor-of system router-props name)))

(defn spawn-scatter-gather-group
  "Create a scatter-gather group router over existing actors.

   Like spawn-scatter-gather-pool, but routes to actors that already exist at the
   given paths (as spawn-group does) rather than spawning routees.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - paths: Collection of actor paths (strings like \"/user/worker1\")
   - opts: Options map
     - :timeout-ms - Timeout for gathering responses (required)
     - :name - Name for the router actor, so it's addressable by path

   Example:
     (spawn-scatter-gather-group sys [\"/user/w1\" \"/user/w2\"] {:timeout-ms 5000})"
  [^ActorRefFactory system paths {:keys [timeout-ms name]}]
  (when-not timeout-ms
    (throw (IllegalArgumentException. ":timeout-ms is required for scatter-gather-group")))
  (let [^Iterable path-list (java.util.ArrayList. ^java.util.Collection paths)
        group (ScatterGatherFirstCompletedGroup. path-list (Duration/ofMillis (long timeout-ms)))
        props (.props group)]
    (actor-of system props name)))

;; ---------------------------------------------------------------------------
;; Tail-Chopping Router
;; ---------------------------------------------------------------------------

(defn spawn-tail-chopping-pool
  "Create a tail-chopping pool for latency reduction.

   Sends to a random routee, then sends to another after interval
   if no response. Returns first response received.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - actor-def: Actor definition
   - size: Number of routees
   - opts: Options map
     - :timeout-ms - Overall timeout (required)
     - :interval-ms - Interval between sends (required)
     - :args - Arguments for actor init
     - :name - Name for the router actor, so it's addressable by path
     - :supervisor-strategy - A pekko-clj.supervision strategy for the routees
     - :dispatcher - Name of a configured dispatcher for the routees

   Example:
     (spawn-tail-chopping-pool sys worker-actor 3
       {:timeout-ms 5000
        :interval-ms 100})"
  [^ActorRefFactory system actor-def size {:keys [timeout-ms interval-ms args name
                                                  supervisor-strategy dispatcher]}]
  (when-not (and timeout-ms interval-ms)
    (throw (IllegalArgumentException. ":timeout-ms and :interval-ms are required for tail-chopping-pool")))
  (let [props (make-props actor-def args)
        timeout (FiniteDuration/create (long timeout-ms) TimeUnit/MILLISECONDS)
        interval (FiniteDuration/create (long interval-ms) TimeUnit/MILLISECONDS)
        pool (configure-pool (TailChoppingPool. (int size) timeout interval)
                             supervisor-strategy dispatcher)
        router-props (.props pool props)]
    (actor-of system router-props name)))

(defn spawn-tail-chopping-group
  "Create a tail-chopping group router over existing actors.

   Like spawn-tail-chopping-pool, but routes to actors that already exist at the
   given paths (as spawn-group does) rather than spawning routees.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - paths: Collection of actor paths (strings like \"/user/worker1\")
   - opts: Options map
     - :timeout-ms - Overall timeout (required)
     - :interval-ms - Interval between sends (required)
     - :name - Name for the router actor, so it's addressable by path

   Example:
     (spawn-tail-chopping-group sys [\"/user/w1\" \"/user/w2\"]
       {:timeout-ms 5000 :interval-ms 100})"
  [^ActorRefFactory system paths {:keys [timeout-ms interval-ms name]}]
  (when-not (and timeout-ms interval-ms)
    (throw (IllegalArgumentException. ":timeout-ms and :interval-ms are required for tail-chopping-group")))
  (let [^Iterable path-list (java.util.ArrayList. ^java.util.Collection paths)
        group (TailChoppingGroup. path-list
                                  (Duration/ofMillis (long timeout-ms))
                                  (Duration/ofMillis (long interval-ms)))
        props (.props group)]
    (actor-of system props name)))

;; ---------------------------------------------------------------------------
;; Pool with Resizer
;; ---------------------------------------------------------------------------

(defn spawn-pool-with-resizer
  "Create a pool router with dynamic resizing.

   The pool automatically scales up when routees are busy and
   scales down when idle.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - actor-def: Actor definition
   - opts: Options map
     - :strategy - Routing strategy (default: :round-robin)
     - :min-size - Minimum pool size (default: 1)
     - :max-size - Maximum pool size (default: 10)
     - :pressure-threshold - mailbox-depth threshold (non-negative int, NOT a
       percentage) used to decide whether a routee counts as \"busy\" for
       scale-up purposes (default: 1). 0 = a routee is busy whenever it is
       processing a message; 1 = busy only once a message is also queued
       behind it; N>1 = busy once more than N messages are queued.
     - :rampup-rate - Rate to add routees (default: 0.2)
     - :backoff-threshold - capacity fraction below which to scale down (default: 0.3)
     - :backoff-rate - Rate to remove routees (default: 0.1)
     - :messages-per-resize - Messages between resize checks (default: 10)
     - :args - Arguments for actor init
     - :name - Name for the router actor, so it's addressable by path
     - :supervisor-strategy - A pekko-clj.supervision strategy for the routees
     - :dispatcher - Name of a configured dispatcher for the routees

   Example:
     (spawn-pool-with-resizer sys worker-actor
       {:min-size 2
        :max-size 10
        :pressure-threshold 1})"
  [^ActorRefFactory system actor-def {:keys [strategy min-size max-size pressure-threshold
                                             rampup-rate backoff-threshold backoff-rate
                                             messages-per-resize args name
                                             supervisor-strategy dispatcher]
                                      :or {strategy :round-robin
                                           min-size 1
                                           max-size 10
                                           pressure-threshold 1
                                           rampup-rate 0.2
                                           backoff-threshold 0.3
                                           backoff-rate 0.1
                                           messages-per-resize 10}}]
  (when-not (and (integer? pressure-threshold) (not (neg? pressure-threshold)))
    (throw (IllegalArgumentException.
            (str "spawn-pool-with-resizer :pressure-threshold must be a "
                 "non-negative integer (mailbox-depth threshold), got "
                 (pr-str pressure-threshold)))))
  (let [props (make-props actor-def args)
        resizer (DefaultResizer. (int min-size) (int max-size)
                                 (int pressure-threshold)
                                 (double rampup-rate)
                                 (double backoff-threshold)
                                 (double backoff-rate)
                                 (int messages-per-resize))
        pool (pool-with-resizer strategy min-size resizer supervisor-strategy dispatcher)
        router-props (.props pool props)]
    (actor-of system router-props name)))

;; ---------------------------------------------------------------------------
;; Cluster-Aware Routers
;; ---------------------------------------------------------------------------

(defn spawn-cluster-pool
  "Create a cluster-aware pool router.

   Deploys routees across cluster nodes based on configuration.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - actor-def: Actor definition
   - opts: Options map
     - :strategy - Local routing strategy (default: :round-robin)
     - :total-instances - Total routees across cluster (required)
     - :max-per-node - Max routees per node (required)
     - :role - Only deploy to nodes with this role (optional)
     - :allow-local - Allow routees on local node (default: true)
     - :args - Arguments for actor init
     - :name - Name for the router actor, so it's addressable by path

   Example:
     (spawn-cluster-pool sys worker-actor
       {:total-instances 10
        :max-per-node 3
        :role \"compute\"})"
  [^ActorRefFactory system actor-def {:keys [strategy total-instances max-per-node
                                             role allow-local args name]
                                      :or {strategy :round-robin
                                           allow-local true}}]
  (when-not (and total-instances max-per-node)
    (throw (IllegalArgumentException.
            ":total-instances and :max-per-node are required for cluster-pool")))
  (let [props (make-props actor-def args)
        local-pool (strategy->pool strategy max-per-node nil nil)
        settings (ClusterRouterPoolSettings. (int total-instances)
                                             (int max-per-node)
                                             (boolean allow-local)
                                             (role-set role))
        cluster-pool (ClusterRouterPool. local-pool settings)
        router-props (.props cluster-pool props)]
    (actor-of system router-props name)))

(defn spawn-cluster-group
  "Create a cluster-aware group router.

   Routes to actors at specified paths across cluster nodes.

   Arguments:
   - system: ActorSystem, or an ActorRefFactory (e.g. (core/context) inside an
     actor) to spawn the router as a child instead of top-level
   - paths: Collection of actor paths (relative to each node)
   - opts: Options map
     - :strategy - Routing strategy (default: :round-robin)
     - :role - Only route to nodes with this role (optional)
     - :allow-local - Allow routing to local node (default: true)
     - :name - Name for the router actor, so it's addressable by path

   Example:
     (spawn-cluster-group sys [\"/user/worker\"]
       {:role \"compute\"})"
  [^ActorRefFactory system paths {:keys [strategy role allow-local name]
                                  :or {strategy :round-robin
                                       allow-local true}}]
  (let [path-list (java.util.ArrayList. ^java.util.Collection paths)
        local-group (strategy->group strategy paths)
        settings (ClusterRouterGroupSettings. (int Integer/MAX_VALUE)
                                              path-list
                                              (boolean allow-local)
                                              (role-set role))
        cluster-group (ClusterRouterGroup. local-group settings)
        props (.props cluster-group)]
    (actor-of system props name)))

;; ---------------------------------------------------------------------------
;; Dynamic Routee Management
;; ---------------------------------------------------------------------------

(defn add-routee
  "Add a routee to a router at runtime.

   Example:
     (add-routee router new-worker-ref)"
  [router routee-ref]
  (core/! router (AddRoutee. (ActorRefRoutee. routee-ref))))

(defn remove-routee
  "Remove a routee from a router at runtime.

   Example:
     (remove-routee router old-worker-ref)"
  [router routee-ref]
  (core/! router (RemoveRoutee. (ActorRefRoutee. routee-ref))))

(defn adjust-pool-size
  "Adjust the pool size by delta (+/-).

   Positive delta adds routees, negative removes them.

   Example:
     (adjust-pool-size router 2)   ; Add 2 routees
     (adjust-pool-size router -1)  ; Remove 1 routee"
  [router delta]
  (core/! router (AdjustPoolSize. delta)))
