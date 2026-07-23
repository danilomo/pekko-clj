---
title: Routing
---
# Routing

Routers help you distribute messages among multiple actors, which is particularly useful for parallelizing workloads or ensuring high availability. `pekko-clj` provides a lightweight wrapper around Pekko's routing capabilities.

There are two primary router styles:
- **Pool Routers**: The router itself creates and manages a pool of child routees (workers).
- **Group Routers**: The router forwards messages to an existing, pre-defined list of independent routees specified by their paths.

## Routing Strategies

Both Pool and Group routers can use different strategies to determine which routee should receive the next message:

| Strategy | Description |
|---|---|
| `:round-robin` | Distributes messages in a sequential, rotating order. |
| `:random` | Randomly selects a routee for each message. |
| `:broadcast` | Sends every message to *all* routees. |
| `:smallest-mailbox` | Sends the message to the routee with the fewest messages currently in its mailbox. |
| `:balancing` | All routees share a single mailbox (work-stealing style). |

Consistent hashing isn't a `:strategy` value accepted by `spawn-pool`/`spawn-group` — it needs a hash function, so it has its own functions instead:

```clojure
(def hashed-pool
  (routing/spawn-consistent-hash-pool sys worker 5
    {:hash-fn (fn [msg] (:user-id msg))}))
```

`spawn-consistent-hash-group` is the group-router equivalent, taking a collection of paths in place of a size.

## Setting up a Pool Router

A pool router acts as the supervisor for the given actors it creates.

### In `pekko-clj`

```clojure
(ns my-app.routing
  (:require [pekko-clj.core :as core]
            [pekko-clj.routing :as routing]))

;; Defining a simple worker actor
(core/defactor worker
  (core/handle task
    (println "Processing:" task)
    state))

;; Start a pool of 10 workers using a round-robin strategy
(def my-pool
  (routing/spawn-pool sys worker 10
    {:strategy :round-robin}))

;; Sending messages to the pool
(core/! my-pool "task-1")
(core/! my-pool "task-2")
```

You can even create cluster-aware pools that deploy their workers across the distributed network simply by changing the function call:

```clojure
(def cluster-pool
  (routing/spawn-cluster-pool sys worker
    {:strategy :round-robin
     :total-instances 10
     :max-per-node 2
     :allow-local true}))
```

## Named Actors and Group Routers

A group router routes to actors at *known paths* — but until an actor has a
name, its path is only knowable after spawning it (by reading back `.path`).
`core/spawn` (and `core/spawn-props`) take an optional trailing opts map with
`:name`, so you can pick the path upfront:

```clojure
(core/spawn sys worker {:id 1} {:name "w1"})
(core/spawn sys worker {:id 2} {:name "w2"})

(def group
  (routing/spawn-group sys ["/user/w1" "/user/w2"] {:strategy :round-robin}))
```

Every `routing/spawn-*` function also accepts an `ActorRefFactory` (e.g.
`(core/context)` inside an actor) in place of the `ActorSystem`, so a router
can be a child of another actor instead of always top-level:

```clojure
(core/defactor supervisor
  (init [_]
    {:pool (routing/spawn-pool (core/context) worker 5)}))
```

### Contrast with Scala (Pekko Typed)

In Pekko Typed Scala, routers are just another sort of behavior that you `spawn`.

```scala
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.scaladsl.Routers

// Define the router behavior
val poolBehavior: Behavior[String] = 
  Routers.pool(poolSize = 10)(Worker())
    .withRoundRobinRouting()

// Spawn the router
val myPool: ActorRef[String] = context.spawn(poolBehavior, "worker-pool")
myPool ! "task-1"
myPool ! "task-2"
```

**Key Differences:**
1. **API Topography**: In `pekko-clj`, routing feels like another mechanism alongside `spawn` (`spawn-pool`), configuring all settings seamlessly via a single map `{:strategy :round-robin}`. In Typed Scala, you mutate a base `Routers.pool` behavior using methods like `.withRoundRobinRouting()`.
2. **Cluster Ease**: Converting a localized pool to a clustered pool in `pekko-clj` is as simple as switching to `spawn-cluster-pool` and passing mapping options like `:max-per-node`. In Scala Typed, distributing workers requires integrating with the separate `ClusterRouting` extension context.
