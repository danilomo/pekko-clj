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

### 7. `prefer-local-routees` Option (Medium Priority)

**Pekko API:** `withPreferLocalRoutees(true)`

**Purpose:** Prefer local routees over remote ones.

**Signature:**
```clojure
;; Add to spawn-pool and spawn-group opts
:prefer-local - Prefer local routees (default: false)
```

**Implementation Notes:**
- Add option parsing in `spawn-pool` and `spawn-group`
- Call `.withPreferLocalRoutees(true)` on pool/group when enabled

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
| prefer-local-routees | ❌ Not implemented | Low priority optimization |
| ScatterGatherPool | ✅ Implemented | `spawn-scatter-gather-pool` |
| TailChoppingPool | ✅ Implemented | `spawn-tail-chopping-pool` |
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

---

## References

- [Pekko Classic Routing](https://pekko.apache.org/docs/pekko/current/routing.html)
- [Pekko Typed Routers](https://pekko.apache.org/docs/pekko/current/typed/routers.html)
- [Cluster Aware Routers](https://pekko.apache.org/docs/pekko/current/cluster-routing.html)
