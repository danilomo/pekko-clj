(ns pekko-clj.routing
  "Router support for pekko-clj actors.

   Routers distribute messages across multiple actor instances (routees).

   Pool routers: Create and manage a pool of routee actors
   Group routers: Route to a group of existing actors at specified paths

   Strategies:
   - :round-robin       - Rotates through routees sequentially
   - :random            - Randomly selects a routee
   - :broadcast         - Sends to all routees
   - :smallest-mailbox  - Sends to routee with fewest queued messages (pool only)
   - :balancing         - All routees share a single mailbox (work-stealing)
   - :consistent-hash   - Routes based on message hash key

   Additional routers:
   - scatter-gather     - Send to all, return first response
   - tail-chopping      - Latency reduction via speculative sends
   - cluster-pool/group - Cluster-aware routers across nodes"
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.routing RoundRobinPool RoundRobinGroup
                                     RandomPool RandomGroup
                                     BroadcastPool BroadcastGroup
                                     SmallestMailboxPool BalancingPool
                                     ConsistentHashingPool ConsistentHashingGroup
                                     ConsistentHashingRouter$ConsistentHashMapper
                                     ScatterGatherFirstCompletedPool
                                     TailChoppingPool
                                     DefaultResizer
                                     AddRoutee RemoveRoutee AdjustPoolSize
                                     ActorRefRoutee
                                     FromConfig]
           [org.apache.pekko.cluster.routing ClusterRouterPool ClusterRouterPoolSettings
                                             ClusterRouterGroup ClusterRouterGroupSettings]
           [scala.concurrent.duration FiniteDuration]
           [java.util.concurrent TimeUnit]
           [pekko_clj.actor CljActor]))

(defn- make-props
  "Create Props from an actor-def and args."
  [actor-def args]
  (CljActor/create ((:make-props actor-def) args)))

(defn- strategy->pool
  "Convert a strategy keyword to a Pool router."
  [strategy size]
  (case strategy
    :round-robin (RoundRobinPool. size)
    :random (RandomPool. size)
    :broadcast (BroadcastPool. size)
    :smallest-mailbox (SmallestMailboxPool. size)
    :balancing (BalancingPool. size)
    ;; Default to round-robin
    (RoundRobinPool. size)))

(defn- strategy->group
  "Convert a strategy keyword to a Group router."
  [strategy paths]
  (let [path-list (java.util.ArrayList. paths)]
    (case strategy
      :round-robin (RoundRobinGroup. path-list)
      :random (RandomGroup. path-list)
      :broadcast (BroadcastGroup. path-list)
      ;; Default to round-robin
      (RoundRobinGroup. path-list))))

(defn spawn-pool
  "Create a pool router that spawns and manages N worker actors.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition created with defactor
   - size: Number of worker instances to create
   - opts: Options map (optional)
     - :strategy - Routing strategy (:round-robin, :random, :broadcast, :smallest-mailbox)
     - :args - Arguments to pass to each worker's init

   Returns an ActorRef for the router.

   Example:
     (def pool (spawn-pool sys worker-actor 5))
     (def pool (spawn-pool sys worker-actor 5 {:strategy :random}))
     (! pool :work) ; Routes to one worker"
  ([system actor-def size]
   (spawn-pool system actor-def size {}))
  ([system actor-def size {:keys [strategy args] :or {strategy :round-robin args nil}}]
   (let [props (make-props actor-def args)
         router (strategy->pool strategy size)
         router-props (.props router props)]
     (.actorOf system router-props))))

(defn spawn-group
  "Create a group router that routes to existing actors at specified paths.

   Arguments:
   - system: ActorSystem
   - paths: Collection of actor paths (strings like \"/user/worker1\")
   - opts: Options map (optional)
     - :strategy - Routing strategy (:round-robin, :random, :broadcast)

   Returns an ActorRef for the router.

   Note: The actors at the specified paths must already exist.

   Example:
     (def group (spawn-group sys [\"/user/w1\" \"/user/w2\" \"/user/w3\"]))
     (def group (spawn-group sys paths {:strategy :broadcast}))
     (! group :work) ; Routes to existing workers"
  ([system paths]
   (spawn-group system paths {}))
  ([system paths {:keys [strategy] :or {strategy :round-robin}}]
   (let [router (strategy->group strategy paths)
         props (.props router)]
     (.actorOf system props))))

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
   - system: ActorSystem
   - actor-def: Actor definition
   - size: Number of routees
   - opts: Options map
     - :hash-fn - Function (msg) -> hash-key (required)
     - :virtual-nodes - Virtual nodes per routee (default: 10)
     - :args - Arguments for actor init

   Example:
     (spawn-consistent-hash-pool sys worker-actor 5
       {:hash-fn (fn [msg] (:user-id msg))
        :virtual-nodes 100})"
  [system actor-def size {:keys [hash-fn virtual-nodes args]
                          :or {virtual-nodes 10}}]
  (when-not hash-fn
    (throw (IllegalArgumentException. ":hash-fn is required for consistent-hash-pool")))
  (let [props (make-props actor-def args)
        mapper (make-hash-mapper hash-fn)
        pool (-> (ConsistentHashingPool. size)
                 (.withVirtualNodesFactor virtual-nodes)
                 (.withHashMapper mapper))
        router-props (.props pool props)]
    (.actorOf system router-props)))

(defn spawn-consistent-hash-group
  "Create a group router with consistent hashing.

   Routes to existing actors at specified paths using consistent hashing.

   Arguments:
   - system: ActorSystem
   - paths: Collection of actor paths
   - opts: Options map
     - :hash-fn - Function (msg) -> hash-key (required)
     - :virtual-nodes - Virtual nodes per routee (default: 10)

   Example:
     (spawn-consistent-hash-group sys [\"/user/w1\" \"/user/w2\"]
       {:hash-fn (fn [msg] (:session-id msg))})"
  [system paths {:keys [hash-fn virtual-nodes]
                 :or {virtual-nodes 10}}]
  (when-not hash-fn
    (throw (IllegalArgumentException. ":hash-fn is required for consistent-hash-group")))
  (let [path-list (java.util.ArrayList. paths)
        mapper (make-hash-mapper hash-fn)
        group (-> (ConsistentHashingGroup. path-list)
                  (.withVirtualNodesFactor virtual-nodes)
                  (.withHashMapper mapper))
        props (.props group)]
    (.actorOf system props)))

;; ---------------------------------------------------------------------------
;; Scatter-Gather Router
;; ---------------------------------------------------------------------------

(defn spawn-scatter-gather-pool
  "Create a scatter-gather pool that returns the first response.

   Sends message to all routees and returns the first response
   received within the timeout.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition
   - size: Number of routees
   - opts: Options map
     - :timeout-ms - Timeout for gathering responses (required)
     - :args - Arguments for actor init

   Example:
     (spawn-scatter-gather-pool sys search-actor 3
       {:timeout-ms 5000})"
  [system actor-def size {:keys [timeout-ms args]}]
  (when-not timeout-ms
    (throw (IllegalArgumentException. ":timeout-ms is required for scatter-gather-pool")))
  (let [props (make-props actor-def args)
        timeout (FiniteDuration/create timeout-ms TimeUnit/MILLISECONDS)
        pool (ScatterGatherFirstCompletedPool. size timeout)
        router-props (.props pool props)]
    (.actorOf system router-props)))

;; ---------------------------------------------------------------------------
;; Tail-Chopping Router
;; ---------------------------------------------------------------------------

(defn spawn-tail-chopping-pool
  "Create a tail-chopping pool for latency reduction.

   Sends to a random routee, then sends to another after interval
   if no response. Returns first response received.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition
   - size: Number of routees
   - opts: Options map
     - :timeout-ms - Overall timeout (required)
     - :interval-ms - Interval between sends (required)
     - :args - Arguments for actor init

   Example:
     (spawn-tail-chopping-pool sys worker-actor 3
       {:timeout-ms 5000
        :interval-ms 100})"
  [system actor-def size {:keys [timeout-ms interval-ms args]}]
  (when-not (and timeout-ms interval-ms)
    (throw (IllegalArgumentException. ":timeout-ms and :interval-ms are required for tail-chopping-pool")))
  (let [props (make-props actor-def args)
        timeout (FiniteDuration/create timeout-ms TimeUnit/MILLISECONDS)
        interval (FiniteDuration/create interval-ms TimeUnit/MILLISECONDS)
        pool (TailChoppingPool. size timeout interval)
        router-props (.props pool props)]
    (.actorOf system router-props)))

;; ---------------------------------------------------------------------------
;; Pool with Resizer
;; ---------------------------------------------------------------------------

(defn spawn-pool-with-resizer
  "Create a pool router with dynamic resizing.

   The pool automatically scales up when routees are busy and
   scales down when idle.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition
   - opts: Options map
     - :strategy - Routing strategy (default: :round-robin)
     - :min-size - Minimum pool size (default: 1)
     - :max-size - Maximum pool size (default: 10)
     - :pressure-threshold - % busy routees to scale up (default: 1)
     - :rampup-rate - Rate to add routees (default: 0.2)
     - :backoff-rate - Rate to remove routees (default: 0.1)
     - :messages-per-resize - Messages between resize checks (default: 10)
     - :args - Arguments for actor init

   Example:
     (spawn-pool-with-resizer sys worker-actor
       {:min-size 2
        :max-size 10
        :pressure-threshold 0.8})"
  [system actor-def {:keys [strategy min-size max-size pressure-threshold
                            rampup-rate backoff-rate messages-per-resize args]
                     :or {strategy :round-robin
                          min-size 1
                          max-size 10
                          pressure-threshold 1
                          rampup-rate 0.2
                          backoff-rate 0.1
                          messages-per-resize 10}}]
  (let [props (make-props actor-def args)
        resizer (DefaultResizer. min-size max-size
                                 pressure-threshold
                                 rampup-rate
                                 backoff-rate
                                 messages-per-resize
                                 3)  ; backoff-threshold
        pool (-> (strategy->pool strategy min-size)
                 (.withResizer resizer))
        router-props (.props pool props)]
    (.actorOf system router-props)))

;; ---------------------------------------------------------------------------
;; Cluster-Aware Routers
;; ---------------------------------------------------------------------------

(defn spawn-cluster-pool
  "Create a cluster-aware pool router.

   Deploys routees across cluster nodes based on configuration.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition
   - opts: Options map
     - :strategy - Local routing strategy (default: :round-robin)
     - :total-instances - Total routees across cluster (required)
     - :max-per-node - Max routees per node (required)
     - :role - Only deploy to nodes with this role (optional)
     - :allow-local - Allow routees on local node (default: true)
     - :args - Arguments for actor init

   Example:
     (spawn-cluster-pool sys worker-actor
       {:total-instances 10
        :max-per-node 3
        :role \"compute\"})"
  [system actor-def {:keys [strategy total-instances max-per-node
                            role allow-local args]
                     :or {strategy :round-robin
                          allow-local true}}]
  (when-not (and total-instances max-per-node)
    (throw (IllegalArgumentException.
             ":total-instances and :max-per-node are required for cluster-pool")))
  (let [props (make-props actor-def args)
        local-pool (strategy->pool strategy max-per-node)
        settings (ClusterRouterPoolSettings. total-instances
                                              max-per-node
                                              allow-local
                                              (if role (java.util.HashSet. [role]) (java.util.HashSet.)))
        cluster-pool (ClusterRouterPool. local-pool settings)
        router-props (.props cluster-pool props)]
    (.actorOf system router-props)))

(defn spawn-cluster-group
  "Create a cluster-aware group router.

   Routes to actors at specified paths across cluster nodes.

   Arguments:
   - system: ActorSystem
   - paths: Collection of actor paths (relative to each node)
   - opts: Options map
     - :strategy - Routing strategy (default: :round-robin)
     - :role - Only route to nodes with this role (optional)
     - :allow-local - Allow routing to local node (default: true)

   Example:
     (spawn-cluster-group sys [\"/user/worker\"]
       {:role \"compute\"})"
  [system paths {:keys [strategy role allow-local]
                 :or {strategy :round-robin
                      allow-local true}}]
  (let [path-list (java.util.ArrayList. paths)
        local-group (strategy->group strategy paths)
        settings (ClusterRouterGroupSettings. Integer/MAX_VALUE
                                               path-list
                                               allow-local
                                               (if role (java.util.HashSet. [role]) (java.util.HashSet.)))
        cluster-group (ClusterRouterGroup. local-group settings)
        props (.props cluster-group)]
    (.actorOf system props)))

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
