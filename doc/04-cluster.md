---
title: Clustering, Singletons & Sharding
---
# Clustering, Singletons & Sharding

A powerful feature of Apache Pekko is the ability to connect multiple instances (nodes) of your application into a distributed system. The `pekko-clj` wrapper integrates directly into Pekko's rich clustering abstractions.

## The Cluster Basics

The first step in clustering is bootstrapping nodes and subscribing to membership events (e.g., when nodes join or leave).

### In `pekko-clj`

```clojure
(ns my-app.cluster
  (:require [pekko-clj.cluster :as cluster]))

;; Join the cluster via seed nodes
(def sys
  (cluster/create-system "my-app"
    {:hostname "127.0.0.1"
     :port 7355
     :seed-nodes ["pekko://my-app@127.0.0.1:7355"]}))

;; Subscribe a simple handler to react to changes
(cluster/subscribe sys 
  (fn [event]
    (println "Cluster event:" (:type event) "from node:" (:member event))))

;; Direct state queries
(println "Current leader is:" (cluster/leader sys))
```

## Cluster Singletons

A Singleton ensures that exactly **one instance** of an actor runs somewhere across the entire cluster, migrating automatically if that node fails.

### In `pekko-clj`

We use the `singleton` namespace to start the singleton locally on capable nodes. Access is done through a singleton proxy.

```clojure
(ns my-app.singleton
  (:require [pekko-clj.core :as core :refer [defactor]]
            [pekko-clj.cluster.singleton :as singleton]))

(defactor job-coordinator
  (init [_] {:jobs []})
  (core/handle [:register job]
    (println "Registered" job)
    (update state :jobs conj job)))

;; Start the singleton manager on this node
(def coordinator-ref
  (singleton/start sys job-coordinator
    {:name "coordinator"
     :role "backend"
     :supervision {:strategy :restart-with-backoff
                   :min-backoff-ms 1000
                   :max-backoff-ms 30000}}))

;; Create proxy to communicate anywhere in the cluster without knowing where it is!
(def proxy
  (singleton/proxy sys
    {:singleton-manager-path "/user/coordinator"}))

(core/! proxy [:register "job-xyz"])
```

## Cluster Sharding

While a singleton limits you to one actor, **Cluster Sharding** distributes millions of actors across the cluster, letting you route messages seamlessly to an actor by its logical ID without knowing which node it resides on. 

### In `pekko-clj`

With `pekko-clj`, the actor matches the raw message it was sent — the sharding
envelope is unwrapped before delivery — and reads its own id at runtime with
`(sharding/entity-id)`.

```clojure
(ns my-app.sharding
  (:require [pekko-clj.core :as core :refer [defactor]]
            [pekko-clj.cluster.sharding :as sharding]))

(defactor user-cart
  (init [_]
    {:items []})

  (core/handle [:add item]
    (update state :items conj item))
  (core/handle :checkout
    (println "Checking out cart!" (sharding/entity-id))
    state))

;; Initialize the sharding region
(def region
  (sharding/start sys user-cart
    {:type-name "Cart" :num-shards 100}))

;; Option 1: Tell the sharding region explicitly
(sharding/tell region "cart-123" [:add "Apple"])

;; Option 2: Wrap it inside an EntityRef for ease
(def cart-123 (sharding/entity-ref sys "Cart" "cart-123"))
(sharding/tell-entity cart-123 [:add "Orange"])
```

#### Entity ids

An entity id is any string, and it is coerced with `str` — so `42` and `"42"`
address the same entity. Pekko names each entity actor
`URLEncoder.encode(id, "utf-8")` so that arbitrary ids are legal actor names, but
it never decodes that name again; `pekko-clj` does, so `(sharding/entity-id)`
always returns the id exactly as you sent it:

```clojure
(sharding/tell region "order/2026 a@b" [:add "Apple"])
;; inside the entity: (sharding/entity-id) => "order/2026 a@b"
;; (the actor is really named "order%2F2026+a%40b")
```

This matters most for persistent entities below, whose journal key is derived
from the id: `/`, spaces, `@`, `:`, `+` and non-ASCII are all ordinary in emails,
order keys and dates, and they journal under the id you chose rather than under
its encoded spelling.

### Persistent Entities (Event-Sourced Aggregates)

The canonical sharding pattern is one *event-sourced* aggregate per entity id:
the entity is passivated when idle and replays its journal whenever a message
brings it back. Pass a `defactor-persistent` definition to `sharding/start` — no
other change is needed. Since every entity of a type is created from one shared
Props, the `:persistence-id` function and the `init` clause receive the **entity
id** instead of spawn args:

```clojure
(persistence/defactor-persistent cart-entity
  :persistence-id (fn [id] (str "cart-" id))

  (init [id] {:id id :items []})

  (command [:add item] (persistence/persist [:added item]))
  (command :get-items  (persistence/reply (:items state)) nil)

  (event [:added item] (update state :items conj item))

  (snapshot-every 100 2))

(def region (sharding/start sys cart-entity {:type-name "Cart"}))

(sharding/tell region "cart-123" [:add "Apple"])
;; …passivated later, recreated on the next message, state replayed from the journal
```

Use `:args` for the classic (non-persistent) case when every entity of a type
needs the same init arguments: `(sharding/start sys my-entity {:type-name "T"
:args {:region "eu"}})`.

Taking a node out of the cluster gracefully? `(sharding/graceful-shutdown!
region)` hands the region's shards to the other nodes (entities are stopped with
the type's stop-message, not killed) and then stops the region.

### Contrast with Scala (Pekko Typed)

Scala requires establishing extracting interfaces to bind cluster message extraction logic logically at startup.

```scala
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef}

// Sharding in Scala requires a specific envelope pattern
val sharding = ClusterSharding(system)

val shardRegion = sharding.init(
  Entity(typeKey = EntityTypeKey[Command]("Cart")) { entityContext =>
    UserCart(entityContext.entityId)
  }
)

// You must acquire an entity ref from the sharding extension
val cart123: EntityRef[Command] = sharding.entityRefFor(EntityTypeKey[Command]("Cart"), "cart-123")

cart123 ! UserCart.Add("Apple")
```

**Key Differences:**
1. **Config Maps vs Extensions**: Bootstrapping in `pekko-clj` uses simple config maps with logical mapping fields rather than strictly typed context extensions (`ClusterSharding(system)` vs `(sharding/start sys ...)`).
2. **Implicit Enveloping vs Behaviors**: `pekko-clj` wraps outgoing messages in an internal envelope (via `sharding/tell`/`ask`) purely to carry the entity id for routing, then unwraps it before delivery — the entity actor's handlers match the raw message and call `(sharding/entity-id)` if they need the id. Scala forces the definition of an explicit `EntityContext` mapper on behavior creation.
