# Routing Module Parity Spec

## Current Implementation Status

**File:** `src/main/clj/pekko_clj/routing.clj`

### Implemented Features

| Function | Pekko API | Status |
|----------|-----------|--------|
| `spawn-pool` | `RoundRobinPool`, etc. | ✅ Complete |
| `spawn-group` | `RoundRobinGroup`, etc. | ✅ Complete |
| `broadcast` | `Broadcast(msg)` | ✅ Complete |
| `get-routees` | `GetRoutees` | ✅ Complete |

### Implemented Strategies

| Strategy | Pool | Group | Status |
|----------|------|-------|--------|
| `:round-robin` | `RoundRobinPool` | `RoundRobinGroup` | ✅ |
| `:random` | `RandomPool` | `RandomGroup` | ✅ |
| `:broadcast` | `BroadcastPool` | `BroadcastGroup` | ✅ |
| `:smallest-mailbox` | `SmallestMailboxPool` | N/A | ✅ |

---

## Recently Implemented Features

### 1. `BalancingPool` ✅ Implemented

**Pekko API:** `BalancingPool`

**Purpose:** All routees share a single mailbox, work-stealing pattern for load balancing.

**Signature:**
```clojure
;; Add to strategy->pool
:balancing (BalancingPool. size)
```

**Implementation Notes:**
- Import `org.apache.pekko.routing.BalancingPool`
- Note: Incompatible with `Broadcast` messages
- Add `:balancing` to strategy options

**Usage:**
```clojure
(spawn-pool sys worker-actor 5 {:strategy :balancing})
```

---

### 2. `ConsistentHashingPool/Group` ✅ Implemented

**Pekko API:** `ConsistentHashingPool`, `ConsistentHashingGroup`

**Purpose:** Route messages to the same routee based on consistent hashing of a key, essential for stateful routing.

**Signature:**
```clojure
(defn spawn-consistent-hash-pool
  "Create a pool router with consistent hashing.

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
  [system actor-def size opts]
  ...)

(defn spawn-consistent-hash-group
  "Create a group router with consistent hashing."
  [system paths opts]
  ...)
```

**Implementation Notes:**
- Import `org.apache.pekko.routing.ConsistentHashingPool`
- Import `org.apache.pekko.routing.ConsistentHashingGroup`
- Import `org.apache.pekko.routing.ConsistentHashingRouter$ConsistentHashMapping`
- Create hash mapping from Clojure function:
  ```clojure
  (defn- make-hash-mapping [hash-fn]
    (reify ConsistentHashingRouter$ConsistentHashMapping
      (hashKey [_ msg]
        (hash-fn msg))))
  ```
- Use `.withHashMapping(mapping)` on pool/group

---

### 3. `ScatterGatherFirstCompletedPool` ✅ Implemented

**Pekko API:** `ScatterGatherFirstCompletedPool`

**Purpose:** Send message to all routees, return first response within timeout.

**Signature:**
```clojure
(defn spawn-scatter-gather-pool
  "Create a scatter-gather pool that returns the first response.

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
  [system actor-def size opts]
  ...)
```

**Implementation Notes:**
- Import `org.apache.pekko.routing.ScatterGatherFirstCompletedPool`
- Requires `scala.concurrent.duration.FiniteDuration` for timeout
- Constructor: `ScatterGatherFirstCompletedPool(size, within)`

---

### 4. `TailChoppingPool` ✅ Implemented

**Pekko API:** `TailChoppingPool`

**Purpose:** Send to random routee, if no response within interval send to another, return first response.

**Signature:**
```clojure
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

   Example:
     (spawn-tail-chopping-pool sys worker-actor 3
       {:timeout-ms 5000
        :interval-ms 100})"
  [system actor-def size opts]
  ...)
```

**Implementation Notes:**
- Import `org.apache.pekko.routing.TailChoppingPool`
- Constructor: `TailChoppingPool(size, within, interval)`

---

### 5. Resizers ✅ Implemented

**Pekko API:** `DefaultResizer`, `OptimalSizeExploringResizer`

**Purpose:** Dynamically adjust pool size based on load.

**Signature:**
```clojure
(defn spawn-pool-with-resizer
  "Create a pool router with dynamic resizing.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition
   - opts: Options map
     - :strategy - Routing strategy (default: :round-robin)
     - :min-size - Minimum pool size (default: 1)
     - :max-size - Maximum pool size (default: 10)
     - :pressure-threshold - mailbox-depth threshold (non-negative int, NOT a
       percentage) used to decide whether a routee counts as busy (default: 1)
     - :rampup-rate - Rate to add routees (default: 0.2)
     - :backoff-threshold - capacity fraction below which to scale down (default: 0.3)
     - :backoff-rate - Rate to remove routees (default: 0.1)
     - :messages-per-resize - Messages between resize checks (default: 10)

   Example:
     (spawn-pool-with-resizer sys worker-actor
       {:min-size 2
        :max-size 10
        :pressure-threshold 1})"
  [system actor-def opts]
  ...)
```

**Implementation Notes:**
- Import `org.apache.pekko.routing.DefaultResizer`
- Create resizer with parameters
- Use `.withResizer(resizer)` on pool

---

### 6. Cluster-Aware Routers ✅ Implemented

**Pekko API:** `ClusterRouterPool`, `ClusterRouterGroup`

**Purpose:** Route messages across cluster nodes.

**Signature:**
```clojure
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
     - :role - Only deploy to nodes with this role
     - :allow-local - Allow routees on local node (default: true)

   Example:
     (spawn-cluster-pool sys worker-actor
       {:total-instances 10
        :max-per-node 3
        :role \"compute\"})"
  [system actor-def opts]
  ...)

(defn spawn-cluster-group
  "Create a cluster-aware group router.

   Routes to actors at specified paths across cluster nodes.

   Arguments:
   - system: ActorSystem
   - paths: Collection of actor paths
   - opts: Options map
     - :strategy - Routing strategy (default: :round-robin)
     - :role - Only route to nodes with this role
     - :allow-local - Allow routing to local node (default: true)

   Example:
     (spawn-cluster-group sys [\"/user/worker\"]
       {:role \"compute\"})"
  [system paths opts]
  ...)
```

**Implementation Notes:**
- Import `org.apache.pekko.cluster.routing.ClusterRouterPool`
- Import `org.apache.pekko.cluster.routing.ClusterRouterPoolSettings`
- Import `org.apache.pekko.cluster.routing.ClusterRouterGroup`
- Import `org.apache.pekko.cluster.routing.ClusterRouterGroupSettings`
- Requires `pekko-cluster` dependency

---

### 7. `prefer-local-routees` Option — N/A (Pekko Typed only)

**Status (N18, 2026-07-23):** not applicable to this library. `preferLocalRoutees`
exists **only** in `pekko-actor-typed` (the Typed `GroupRouter.withPreferLocalRoutees`);
Pekko's *classic* routing API — the API this library wraps — has no
`withPreferLocalRoutees` on any pool, group, or cluster-router settings class
(grepped the whole `pekko-actor` / `pekko-cluster` surface at 1.6.0). The classic
analogue already exposed is `:allow-local` (`allowLocalRoutees`) on the cluster
routers. Resolving the spec's long-standing ❌ as "does not exist in classic
routing" rather than shipping a wrapper for a method that isn't there.

---

### 9. Scatter-gather / tail-chopping **groups** ✅ Implemented (N18)

**Pekko API:** `ScatterGatherFirstCompletedGroup`, `TailChoppingGroup`

**Purpose:** the group (route-to-existing-actors) counterparts of the
already-implemented scatter-gather and tail-chopping *pools*.

**Signature:**
```clojure
(spawn-scatter-gather-group sys ["/user/w1" "/user/w2"] {:timeout-ms 5000})
(spawn-tail-chopping-group  sys ["/user/w1" "/user/w2"] {:timeout-ms 5000 :interval-ms 100})
```

**Implementation Notes:**
- Use the Java-friendly constructors — `(java.lang.Iterable<String>, java.time.Duration)`
  and `(java.lang.Iterable<String>, java.time.Duration, java.time.Duration)` — so a
  plain `ArrayList` of paths and `Duration/ofMillis` work without touching Scala types.

---

### 10. Pool `:supervisor-strategy` / `:dispatcher` ✅ Implemented (N18)

**Pekko API:** `Pool.withSupervisorStrategy(SupervisorStrategy)`, `withDispatcher(String)`

**Purpose:** pools supervise their routees; `:supervisor-strategy` lets a routee
failure be resumed/restarted/stopped instead of the default escalate.
`:dispatcher` runs the routees on a named dispatcher.

**Signature:**
```clojure
(spawn-pool sys worker 3 {:supervisor-strategy (supervision/one-for-one supervision/resume-decider)
                          :dispatcher "my-dispatcher"})
```

**Implementation Notes:**
- These withers are declared on each concrete pool class, not the `Pool` interface
  (like `withResizer`), so a private `configure-pool` macro applies them on the
  concrete constructor expression to stay reflection-free. Available on every pool
  spawner (`spawn-pool`, consistent-hash, scatter-gather, tail-chopping, resizer).

---

### 8. Dynamic Routee Management ✅ Implemented

**Pekko API:** `AddRoutee`, `RemoveRoutee`, `AdjustPoolSize`

**Purpose:** Dynamically add/remove routees at runtime.

**Signature:**
```clojure
(defn add-routee
  "Add a routee to a router."
  [router routee-ref]
  (core/! router (org.apache.pekko.routing.AddRoutee.
                   (org.apache.pekko.routing.ActorRefRoutee. routee-ref))))

(defn remove-routee
  "Remove a routee from a router."
  [router routee-ref]
  (core/! router (org.apache.pekko.routing.RemoveRoutee.
                   (org.apache.pekko.routing.ActorRefRoutee. routee-ref))))

(defn adjust-pool-size
  "Adjust the pool size by delta (+/-)."
  [router delta]
  (core/! router (org.apache.pekko.routing.AdjustPoolSize. delta)))
```

---

## Implementation Status

| Feature | Status | Notes |
|---------|--------|-------|
| ConsistentHashingPool/Group | ✅ Implemented | `spawn-consistent-hash-pool`, `spawn-consistent-hash-group` |
| Cluster-aware routers | ✅ Implemented | `spawn-cluster-pool`, `spawn-cluster-group` |
| BalancingPool | ✅ Implemented | `:balancing` strategy in `spawn-pool` |
| Resizers | ✅ Implemented | `spawn-pool-with-resizer` |
| prefer-local-routees | ⛔ N/A | Pekko Typed only; no classic-routing equivalent (N18) |
| ScatterGatherPool/Group | ✅ Implemented | `spawn-scatter-gather-pool`, `spawn-scatter-gather-group` (N18) |
| TailChoppingPool/Group | ✅ Implemented | `spawn-tail-chopping-pool`, `spawn-tail-chopping-group` (N18) |
| Pool supervisor-strategy / dispatcher | ✅ Implemented | `:supervisor-strategy`, `:dispatcher` on pool spawners (N18) |
| Dynamic routee mgmt | ✅ Implemented | `add-routee`, `remove-routee`, `adjust-pool-size` |

---

## Test Coverage ✅ Complete

Tests in `test/clj/pekko_clj/routing_test.clj`:

- `balancing-pool-processes-messages` - Work-stealing pool
- `consistent-hashing-pool-routes-same-key-to-same-routee` - Consistent hash routing
- `consistent-hashing-group-routes-same-key-to-same-routee` - Consistent hash group
- `scatter-gather-pool-returns-first-response` - First responder wins
- `tail-chopping-pool-returns-response` - Latency reduction
- `pool-with-resizer-starts` - Auto-scaling pool
- `adjust-pool-size-changes-routees` - Dynamic routee management
- `scatter-gather-group-returns-first-response` / `-requires-timeout` - Group form (N18)
- `tail-chopping-group-returns-response` / `-requires-timeout-and-interval` - Group form (N18)
- `pool-supervisor-strategy-resumes-routee` - Routee failure resumed, not escalated (N18)
- `pool-dispatcher-option-routes` - Routees on a named dispatcher (N18)

---

## References

- [Pekko Classic Routing](https://pekko.apache.org/docs/pekko/current/routing.html)
- [Pekko Typed Routers](https://pekko.apache.org/docs/pekko/current/typed/routers.html)
- [Cluster Aware Routers](https://pekko.apache.org/docs/pekko/current/cluster-routing.html)
