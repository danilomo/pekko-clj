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

With `pekko-clj`, the actor can destructure an `entity-id` string internally seamlessly from initialization.

```clojure
(ns my-app.sharding
  (:require [pekko-clj.core :as core :refer [defactor]]
            [pekko-clj.cluster.sharding :as sharding]))

(defactor user-cart
  (init [args] 
    {:cart-id (:entity-id args) 
     :items []})
  
  (core/handle [:entity-message id msg]
    ;; The standard message envelope arrives as [:entity-message entity-id nested-msg]
    (case (first msg)
      :add (update state :items conj (second msg))
      :checkout (do (println "Checking out cart!" (:cart-id state)) state))))

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
2. **Implicit Enveloping vs Behaviors**: `pekko-clj` standardizes sharding messages uniformly as `[:entity-message id msg]`, which handles mapping automatically inside the defactor macros' handlers. Scala forces the definition of an explicit `EntityContext` mapper on behavior creation.
