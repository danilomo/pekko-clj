# pekko-clj

An ergonomic Clojure wrapper for [Apache Pekko](https://pekko.apache.org/) (the open-source Akka fork). Actors should feel native to Clojure - the way GenServer feels native to Elixir.

pekko-clj provides a declarative `defactor` macro with implicit state binding, `core.match` pattern matching, and Erlang-style message passing (`!`, `<?>`, `<!`), so you can focus on message handlers instead of Java interop boilerplate.

## Features

- **Declarative Actor Definition** - `defactor` macro with pattern matching
- **Erlang-style Messaging** - `!` (tell), `<?>` (ask), `<!` (blocking ask)
- **Event Sourcing** - `defactor-persistent` with commands, events, and snapshots
- **Reactive Streams** - Functional stream API with backpressure
- **Clustering** - Cluster membership, events, and state management
- **Cluster Sharding** - Distribute actors across cluster nodes
- **Cluster Singletons** - Exactly-one actor instances with supervision
- **Routers** - Pool and group routers with multiple strategies

## Quick Start

Add to `project.clj`:

```clojure
[pekko-clj "0.1.0-SNAPSHOT"]
```

Define an actor, spawn it, and send messages:

```clojure
(ns my-app.core
  (:require [pekko-clj.core :refer :all]))

(defactor counter
  "A simple counter actor."

  (init [args]
    {:count (or (:start args) 0)})

  (handle :inc
    (update state :count inc))

  (handle [:add n]
    (update state :count + n))

  (handle :get
    (reply (:count state)))

  (handle :reset
    {:count 0}))

(def sys (actor-system "myapp"))
(def c (spawn sys counter {:start 0}))

(! c :inc)
(! c :inc)
(! c [:add 10])
(<! sys c :get)  ;; => 12

(.terminate sys)
```

## Installation

### Leiningen

```clojure
[pekko-clj "0.1.0-SNAPSHOT"]
```

### Dependencies

pekko-clj includes these Apache Pekko modules:

| Module | Version | Purpose |
|--------|---------|---------|
| `pekko-actor_3` | 1.1.3 | Core actor system |
| `pekko-stream_3` | 1.1.3 | Reactive streams |
| `pekko-persistence_3` | 1.1.3 | Event sourcing |
| `pekko-cluster_3` | 1.1.3 | Clustering |
| `pekko-cluster-sharding_3` | 1.1.3 | Cluster sharding |
| `pekko-cluster-tools_3` | 1.1.3 | Singletons, pub-sub |
| `pekko-http_3` | 1.1.0 | HTTP server/client |

## The `defactor` Macro

`defactor` is the primary way to define actors. It compiles declarative clauses into a Pekko actor with `core.match` pattern matching.

### Syntax

```clojure
(defactor name
  "Optional docstring"

  (init [args-binding]
    ;; Called once when actor starts. Return value becomes initial state.
    initial-state)

  (handle pattern
    ;; Message handler. `state` is implicitly bound.
    ;; Return value becomes new state.
    new-state)

  (on-stop
    ;; Cleanup when actor stops.
    cleanup-expr))
```

### Clauses

| Clause | Required | Description |
|--------|----------|-------------|
| `init` | No | Called once before actor starts. Returns initial state. |
| `handle` | Yes (1+) | Message handlers with pattern matching. Returns new state. |
| `on-stop` | No | Lifecycle hook for cleanup when actor stops. |

### Pattern Matching

Patterns in `handle` clauses use `core.match`:

| Pattern | Matches | Example |
|---------|---------|---------|
| `:keyword` | Exact keyword | `(handle :inc ...)` |
| `[:tag arg1 arg2]` | Vector with destructuring | `(handle [:add n] ...)` |
| `{:key val}` | Map with structure | `(handle {:type :cmd} ...)` |
| `symbol` | Anything (catch-all) | `(handle msg ...)` |
| `_` | Anything (wildcard) | `(handle _ ...)` |

### Implicit Bindings

Inside any `handle`, `init`, or `on-stop` body:

| Binding | Description |
|---------|-------------|
| `state` | Current actor state (auto-dereferenced) |
| `(self)` | This actor's `ActorRef` |
| `(sender)` | Sender of current message |
| `(parent)` | Parent actor's `ActorRef` |
| `(reply msg)` | Reply to sender (returns nil) |
| `(spawn actor-def)` | Spawn a child actor |

## API Reference

### Actor System

```clojure
(actor-system)          ;; Create with default name
(actor-system "myapp")  ;; Create with specific name
```

### Spawning Actors

```clojure
;; Top-level (requires ActorSystem)
(spawn system actor-def)
(spawn system actor-def args)

;; Inside an actor (creates child actor)
(spawn actor-def)
(spawn actor-def args)
```

### Message Passing

```clojure
;; Fire-and-forget (tell)
(! actor-ref msg)

;; Non-blocking ask - returns Scala Future
(<?> actor-ref msg)
(<?> actor-ref msg timeout-ms)

;; Blocking ask - returns reply value
(<! system actor-ref msg)
(<! system actor-ref msg timeout-ms)

;; Reply to current message sender
(reply value)

;; Forward message preserving original sender
(forward target msg)
```

### Behavior Switching

```clojure
(defactor happy
  (handle :mood (reply "I'm happy!") state)
  (handle :toggle (become sad state)))

(defactor sad
  (handle :mood (reply "I'm sad...") state)
  (handle :toggle (become happy state)))
```

## Event Sourcing (Persistence)

Define persistent actors that store events and rebuild state:

```clojure
(ns my-app.cart
  (:require [pekko-clj.persistence :refer [defactor-persistent persist]]))

(defactor-persistent shopping-cart
  :persistence-id (fn [args] (str "cart-" (:id args)))

  (init [args] {:items []})

  (command [:add-item item]
    (persist [:item-added item]))

  (command [:remove-item item-id]
    (persist [:item-removed item-id]))

  (event [:item-added item]
    (update state :items conj item))

  (event [:item-removed item-id]
    (update state :items #(remove (fn [i] (= (:id i) item-id)) %)))

  (snapshot-every 100))
```

## Reactive Streams

Build reactive stream pipelines with backpressure:

```clojure
(ns my-app.streams
  (:require [pekko-clj.stream :as s]))

;; Process a collection
(-> (s/source (range 100))
    (s/smap inc)
    (s/sfilter even?)
    (s/run-foreach println sys))

;; Fold to a single value
(-> (s/source [1 2 3 4 5])
    (s/sfold 0 +)
    (s/run sys))  ;; => CompletionStage<15>

;; Build complex graphs
(-> (s/source ["hello" "world"])
    (s/smap clojure.string/upper-case)
    (s/via (s/flow-map #(str % "!")))
    (s/run-to-seq sys))  ;; => ["HELLO!" "WORLD!"]
```

## Clustering

Create cluster-enabled actor systems:

```clojure
(ns my-app.cluster
  (:require [pekko-clj.cluster :as cluster]))

;; Create cluster system
(def sys (cluster/create-system "my-app"
           {:hostname "127.0.0.1"
            :port 7355
            :seed-nodes ["pekko://my-app@host1:7355"]}))

;; Subscribe to cluster events
(cluster/subscribe sys (fn [event]
                         (println "Event:" (:type event) (:member event))))

;; Query cluster state
(cluster/members sys)      ;; All members
(cluster/leader sys)       ;; Current leader
(cluster/self-member sys)  ;; This node's member info
(cluster/state-snapshot sys)  ;; Full state as map
```

## Cluster Sharding

Distribute actors across the cluster:

```clojure
(ns my-app.sharding
  (:require [pekko-clj.cluster.sharding :as sharding]))

(defactor order-entity
  (init [args] {:order-id (:entity-id args) :items []})

  (handle [:entity-message id msg]
    (case (first msg)
      :add-item (update state :items conj (second msg))
      :get-items (do (reply (:items state)) state))))

;; Start sharding region
(def region (sharding/start sys order-entity
              {:type-name "Order" :num-shards 100}))

;; Send messages to entities
(sharding/tell region "order-123" [:add-item {:sku "ABC"}])

;; Or use EntityRef for cleaner API
(def order (sharding/entity-ref sys "Order" "order-123"))
(sharding/tell-entity order [:add-item {:sku "XYZ"}])
```

## Cluster Singletons

Run exactly one instance of an actor across the cluster:

```clojure
(ns my-app.singleton
  (:require [pekko-clj.cluster.singleton :as singleton]))

(defactor leader
  (init [_] {:tasks []})
  (handle [:assign task] (update state :tasks conj task))
  (handle :get-tasks (reply (:tasks state))))

;; Start singleton with supervision
(def leader-ref (singleton/start sys leader
                  {:name "task-leader"
                   :role "backend"
                   :supervision {:strategy :restart-with-backoff
                                 :min-backoff-ms 1000
                                 :max-backoff-ms 30000}}))

;; Access via proxy from any node
(def proxy (singleton/proxy sys
             {:singleton-manager-path "/user/task-leader"}))

(core/! proxy [:assign {:id 1 :name "Process data"}])
```

## Routers

Distribute messages across actor pools:

```clojure
(ns my-app.routing
  (:require [pekko-clj.routing :as routing]))

(defactor worker
  (handle msg
    (println "Processing:" msg)
    state))

;; Pool router - creates and manages workers
(def pool (routing/spawn-pool sys worker 10
            {:strategy :round-robin}))

;; Send work to pool
(core/! pool "task-1")
(core/! pool "task-2")

;; Available strategies
;; :round-robin      - Sequential rotation
;; :random           - Random selection
;; :broadcast        - Send to all
;; :smallest-mailbox - Least loaded worker
;; :balancing        - Shared work-stealing mailbox
;; :consistent-hash  - Hash-based routing
```

### Cluster-Aware Routers

```clojure
;; Deploy workers across cluster nodes
(def cluster-pool (routing/spawn-cluster-pool sys worker 5
                    {:strategy :round-robin
                     :max-instances-per-node 2
                     :allow-local-routees true}))
```

## Design Principles

1. **Implicit `state` binding** - No `@this` everywhere
2. **Functions for context** - `self`, `sender`, `parent` not Java methods
3. **Polymorphic `spawn`** - Works with system or actor context
4. **`reply` returns nil** - Doesn't affect state return value
5. **`become` for behaviors** - Clean behavior switching
6. **Pattern matching** - `core.match` for message dispatch

## Project Structure

```
src/main/clj/pekko_clj/
├── core.clj           # Core actor API, defactor macro
├── persistence.clj    # Event sourcing, defactor-persistent
├── stream.clj         # Reactive streams
├── cluster.clj        # Cluster membership & events
├── routing.clj        # Router pools & groups
├── supervision.clj    # Supervision strategies
└── cluster/
    ├── sharding.clj   # Cluster sharding
    └── singleton.clj  # Cluster singletons

src/main/java/pekko_clj/actor/
├── CljActor.java           # Core actor implementation
└── CljPersistentActor.java # Persistent actor implementation
```

## Running Tests

```bash
lein test
```

## License

Copyright 2025

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.

## References

- [Apache Pekko Documentation](https://pekko.apache.org/docs/pekko/current/)
- [Pekko Cluster](https://pekko.apache.org/docs/pekko/current/typed/cluster.html)
- [Pekko Persistence](https://pekko.apache.org/docs/pekko/current/typed/persistence.html)
- [Pekko Streams](https://pekko.apache.org/docs/pekko/current/stream/index.html)
