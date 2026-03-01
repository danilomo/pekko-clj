(ns pekko-clj.routing
  "Router support for pekko-clj actors.

   Routers distribute messages across multiple actor instances (routees).

   Pool routers: Create and manage a pool of routee actors
   Group routers: Route to a group of existing actors at specified paths

   Strategies:
   - :round-robin    - Rotates through routees sequentially
   - :random         - Randomly selects a routee
   - :broadcast      - Sends to all routees
   - :smallest-mailbox - Sends to routee with fewest queued messages (pool only)"
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.routing RoundRobinPool RoundRobinGroup
                                     RandomPool RandomGroup
                                     BroadcastPool BroadcastGroup
                                     SmallestMailboxPool
                                     FromConfig]
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
