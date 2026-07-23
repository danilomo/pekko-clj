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

   Additional routers:
   - scatter-gather     - Send to all, return first response
   - tail-chopping      - Latency reduction via speculative sends
   - cluster-pool/group - Cluster-aware routers across nodes"
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorRef ActorRefFactory Props]
           [org.apache.pekko.routing Pool Group
            RoundRobinPool RoundRobinGroup
            RandomPool RandomGroup
            BroadcastPool BroadcastGroup
            SmallestMailboxPool BalancingPool
            ConsistentHashingPool ConsistentHashingGroup
            ConsistentHashingRouter$ConsistentHashMapper
            ScatterGatherFirstCompletedPool
            TailChoppingPool
            DefaultResizer
            AddRoutee RemoveRoutee AdjustPoolSize
            ActorRefRoutee]
           [org.apache.pekko.cluster.routing ClusterRouterPool ClusterRouterPoolSettings
            ClusterRouterGroup ClusterRouterGroupSettings]
           [scala.concurrent.duration FiniteDuration]
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

(defn- strategy->pool
  "Convert a strategy keyword to a Pool router. `nil` selects the default,
   :round-robin; any other unrecognized keyword throws."
  ^Pool [strategy size]
  (let [n (int size)]
    (case strategy
      (nil :round-robin) (RoundRobinPool. n)
      :random (RandomPool. n)
      :broadcast (BroadcastPool. n)
      :smallest-mailbox (SmallestMailboxPool. n)
      :balancing (BalancingPool. n)
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
  "A pool of `strategy` with `resizer` attached.

   `withResizer` is declared on each concrete pool class, not on the `Pool`
   interface, so the strategy is re-dispatched here to keep every call direct.
   BalancingPool does not declare it at all — its routees share one mailbox, so
   there is nothing per-routee to resize — and is rejected with a clear message
   rather than an opaque \"no matching method\" from the reflective call."
  ^Pool [strategy size ^DefaultResizer resizer]
  (let [n (int size)]
    (case strategy
      :random           (.withResizer (RandomPool. n) resizer)
      :broadcast        (.withResizer (BroadcastPool. n) resizer)
      :smallest-mailbox (.withResizer (SmallestMailboxPool. n) resizer)
      :balancing (throw (IllegalArgumentException.
                         (str ":balancing pools cannot be resized — all routees share a "
                              "single mailbox. Use :round-robin, :random, :broadcast or "
                              ":smallest-mailbox with :min-size/:max-size.")))
      (.withResizer (RoundRobinPool. n) resizer))))

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

   Returns an ActorRef for the router.

   Example:
     (def pool (spawn-pool sys worker-actor 5))
     (def pool (spawn-pool sys worker-actor 5 {:strategy :random}))
     (! pool :work) ; Routes to one worker"
  ([system actor-def size]
   (spawn-pool system actor-def size {}))
  ([^ActorRefFactory system actor-def size {:keys [strategy args name]
                                            :or {strategy :round-robin args nil}}]
   (let [props (make-props actor-def args)
         router (strategy->pool strategy size)
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

   Example:
     (spawn-consistent-hash-pool sys worker-actor 5
       {:hash-fn (fn [msg] (:user-id msg))
        :virtual-nodes 100})"
  [^ActorRefFactory system actor-def size {:keys [hash-fn virtual-nodes args name]
                                           :or {virtual-nodes 10}}]
  (when-not hash-fn
    (throw (IllegalArgumentException. ":hash-fn is required for consistent-hash-pool")))
  (let [props (make-props actor-def args)
        mapper (make-hash-mapper hash-fn)
        pool (-> (ConsistentHashingPool. (int size))
                 (.withVirtualNodesFactor virtual-nodes)
                 (.withHashMapper mapper))
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

   Example:
     (spawn-scatter-gather-pool sys search-actor 3
       {:timeout-ms 5000})"
  [^ActorRefFactory system actor-def size {:keys [timeout-ms args name]}]
  (when-not timeout-ms
    (throw (IllegalArgumentException. ":timeout-ms is required for scatter-gather-pool")))
  (let [props (make-props actor-def args)
        timeout (FiniteDuration/create (long timeout-ms) TimeUnit/MILLISECONDS)
        pool (ScatterGatherFirstCompletedPool. (int size) timeout)
        router-props (.props pool props)]
    (actor-of system router-props name)))

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

   Example:
     (spawn-tail-chopping-pool sys worker-actor 3
       {:timeout-ms 5000
        :interval-ms 100})"
  [^ActorRefFactory system actor-def size {:keys [timeout-ms interval-ms args name]}]
  (when-not (and timeout-ms interval-ms)
    (throw (IllegalArgumentException. ":timeout-ms and :interval-ms are required for tail-chopping-pool")))
  (let [props (make-props actor-def args)
        timeout (FiniteDuration/create (long timeout-ms) TimeUnit/MILLISECONDS)
        interval (FiniteDuration/create (long interval-ms) TimeUnit/MILLISECONDS)
        pool (TailChoppingPool. (int size) timeout interval)
        router-props (.props pool props)]
    (actor-of system router-props name)))

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

   Example:
     (spawn-pool-with-resizer sys worker-actor
       {:min-size 2
        :max-size 10
        :pressure-threshold 1})"
  [^ActorRefFactory system actor-def {:keys [strategy min-size max-size pressure-threshold
                                             rampup-rate backoff-threshold backoff-rate
                                             messages-per-resize args name]
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
        pool (pool-with-resizer strategy min-size resizer)
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
        local-pool (strategy->pool strategy max-per-node)
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
