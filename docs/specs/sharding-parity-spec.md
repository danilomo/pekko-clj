# Cluster Sharding Module Parity Spec

## Current Implementation Status

**File:** `src/main/clj/pekko_clj/cluster/sharding.clj`

### Implemented Features

| Function | Pekko API | Status |
|----------|-----------|--------|
| `start` | `ClusterSharding.start` | ✅ Complete |
| `start-proxy` | `ClusterSharding.startProxy` | ✅ Complete |
| `tell` | Message via envelope | ✅ Complete |
| `ask` | Ask via envelope | ✅ Complete |
| `get-shard-region` | `shardRegion(typeName)` | ✅ Complete |
| `shard-region-state` | `GetShardRegionState` | ✅ Complete |
| `state->map` | `CurrentShardRegionState` → Clojure map | ✅ Complete |
| `graceful-shutdown!` | `ShardRegion.gracefulShutdownInstance` | ✅ Complete |
| `entity-message` | Envelope (plain map, `::entity-id`/`::message`) | ✅ Complete |

### Implemented Options

| Option | Pekko API | Status |
|--------|-----------|--------|
| `:type-name` | Type name | ✅ |
| `:role` | `withRole` | ✅ |
| `:num-shards` | Extractor param | ✅ |
| `:passivate-after` | `withPassivationStrategy` (idle) | ✅ |
| `:passivation` | `withPassivationStrategy` (idle / LRU / SLRU / MRU / LFU) | ✅ |
| `:remember-entities` | `withRememberEntities` | ✅ |
| `:remember-entities-store` | `remember-entities-store` config | ✅ |
| `:journal-plugin-id` / `:snapshot-plugin-id` | `withJournalPluginId` / `withSnapshotPluginId` | ✅ |
| `:stop-message` | `start(…, allocationStrategy, handOffStopMessage)` | ✅ |
| `:args` | Shared init args for classic entities | ✅ |

---

## Recently Implemented Features

### 1. `entity-ref` ✅ Implemented

**Pekko API:** `ClusterSharding.entityRefFor(typeKey, entityId)`

**Purpose:** Get a typed reference to an entity for direct messaging (more idiomatic than envelope pattern).

**Signature:**
```clojure
(defn entity-ref
  "Get a reference to a specific entity.

   Returns an EntityRef that can be used to send messages directly
   without wrapping in an envelope.

   Arguments:
   - system: ActorSystem
   - type-name: The entity type name
   - entity-id: The entity's unique identifier

   Example:
     (def order-ref (entity-ref sys \"Order\" \"order-123\"))
     (core/! order-ref [:add-item item])
     (core/<?> order-ref :get-items)"
  [system type-name entity-id]
  ...)
```

**Implementation Notes:**
- For Classic API, create wrapper around shard region that binds entity-id
- Return a proxy ActorRef or custom type that wraps tell/ask with entity-id
- Consider using `reify` to create a lightweight wrapper:
  ```clojure
  (deftype EntityRef [shard-region entity-id]
    clojure.lang.ILookup
    (valAt [_ k] (case k :entity-id entity-id :shard-region shard-region nil))

    Object
    (toString [_] (str "EntityRef(" entity-id ")")))

  (defn tell-entity [^EntityRef ref msg]
    (tell (.shard-region ref) (.entity-id ref) msg))
  ```

---

### 2. Advanced Passivation Strategies ✅ Implemented

**Pekko API:** `passivation.strategy` configuration

**Purpose:** Control entity passivation with LRU, LFU, MRU, or composite strategies.

**Signature:**
```clojure
(defn start
  "Start cluster sharding with advanced passivation.

   Additional opts:
   - :passivation-strategy - Strategy keyword
     - :idle - Passivate after idle timeout (default)
     - :lru - Least recently used
     - :lfu - Least frequently used
     - :mru - Most recently used
   - :active-entity-limit - Max active entities (for LRU/LFU/MRU)
   - :passivate-after - Idle timeout in ms (for :idle strategy)"
  [system actor-def opts]
  ...)
```

**Implemented as (N5):** `sharding/passivation-settings` builds a
`ClusterShardingSettings.PassivationStrategySettings` programmatically — no HOCON generation
needed, Pekko 1.6 exposes a builder (`withIdleEntityPassivation`, `withActiveEntityLimit`,
`withLeastRecentlyUsedReplacement` / `withMostRecentlyUsedReplacement` /
`withLeastFrequentlyUsedReplacement`, segmented LRU via `LeastRecentlyUsedSettings`).
`start` accepts it as `:passivation` (a map or a settings object):

```clojure
(sharding/start sys order-entity
  {:type-name "Order"
   :passivation {:strategy :least-recently-used   ;; or :idle / :most-recently-used
                 :active-entity-limit 10000       ;;    / :least-frequently-used / :none
                 :segmented [0.2 0.8]             ;; optional SLRU levels
                 :idle-timeout 300000}})          ;; may be combined with a limit
```

Note: Pekko disables automatic passivation entirely when `:remember-entities` is on.
`:passivate-after` remains as the idle shorthand — it previously called
`withPassivateIdleEntityAfter`, which does not exist in Pekko 1.6 (it threw at runtime).

---

### 3. `cluster-sharding-stats` ✅ Implemented

**Pekko API:** `GetClusterShardingStats`

**Purpose:** Get statistics across all shard regions in the cluster.

**Signature:**
```clojure
(defn cluster-sharding-stats
  "Get sharding statistics across all cluster nodes.

   Arguments:
   - system: ActorSystem
   - type-name: The entity type name
   - timeout-ms: Timeout for gathering stats (default: 5000)

   Returns a future of stats map with:
   - :regions - Map of region address to a per-region map of
     {:stats {shard-id count} :failed #{shard-id …}} (N19 added :failed —
     ShardRegionStats.getFailed — and nested the counts under :stats)"
  ([system type-name]
   (cluster-sharding-stats system type-name 5000))
  ([system type-name timeout-ms]
   ...))
```

**Implementation Notes:**
- Import `org.apache.pekko.cluster.sharding.ShardRegion$GetClusterShardingStats`
- Send to shard region with timeout
- Parse `ClusterShardingStats` response

---

### 4. `passivate-entity` ✅ Implemented

**Pekko API:** `ClusterSharding.Passivate(stopMessage)`

**Purpose:** Explicitly passivate an entity from within the entity actor.

**Signature:**
```clojure
(defn passivate
  "Request passivation for the current entity.

   Call this from within an entity actor to request graceful shutdown.
   The entity will receive the stop-message before being stopped.

   Arguments:
   - context: The actor context (available in defactor handlers)
   - stop-message: Message to send before stopping

   Example:
     (core/defactor my-entity
       (handle :cleanup
         (sharding/passivate context :final-stop)
         state)
       (handle :final-stop
         (save-state! state)
         :stop))"
  [context stop-message]
  ...)
```

**Implementation Notes:**
- Entity needs access to shard ref (parent actor)
- Send `ShardRegion.Passivate(stopMessage)` to parent
- May need to expose shard ref in entity context

---

### 5. Custom Shard Allocation Strategy (Low Priority)

**Pekko API:** `ShardAllocationStrategy`

**Purpose:** Custom logic for allocating shards to nodes.

**Signature:**
```clojure
(defn start-with-allocation-strategy
  "Start sharding with a custom allocation strategy.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition
   - opts: Standard sharding options
   - allocation-fn: Function (shard-id, current-allocations) -> node-address

   Example:
     (start-with-allocation-strategy sys order-actor
       {:type-name \"Order\"}
       (fn [shard-id allocations]
         ;; Custom allocation logic
         ...))"
  [system actor-def opts allocation-fn]
  ...)
```

**Implementation Notes:**
- Implement `ShardAllocationStrategy` interface
- Override `allocateShard` and `rebalance` methods
- Complex - may be better to document config-based approach

---

### 6. External Shard Allocation (Low Priority)

**Pekko API:** `ExternalShardAllocationStrategy`

**Purpose:** Externally control shard allocation, useful for Kafka partition co-location.

**Signature:**
```clojure
(defn start-with-external-allocation
  "Start sharding with external allocation control.

   Useful for co-locating shards with Kafka partitions.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition
   - opts: Standard sharding options plus:
     - :allocation-client - Client for external allocation updates"
  [system actor-def opts]
  ...)

(defn update-shard-allocation
  "Update external shard allocation.

   Arguments:
   - client: The external allocation client
   - shard-id: The shard to allocate
   - target-address: Target node address"
  [client shard-id target-address]
  ...)
```

**Implementation Notes:**
- Import `org.apache.pekko.cluster.sharding.external.ExternalShardAllocationStrategy`
- Import `org.apache.pekko.cluster.sharding.external.ExternalShardAllocation`
- Create client via `ExternalShardAllocation.get(system).clientFor(typeName)`

---

### 7. Health Checks ✅ Implemented

**Pekko API:** Built-in readiness checks

**Purpose:** Check if shard regions are registered and ready.

**Signature:**
```clojure
(defn shard-region-ready?
  "Check if a shard region is registered and ready.

   Arguments:
   - system: ActorSystem
   - type-name: The entity type name

   Returns true if the region is ready to accept messages."
  [system type-name]
  ...)
```

**Implementation Notes:**
- Check if `shardRegion(typeName)` returns without exception
- Or use Pekko Management health check endpoints

---

### 8. Remember Entities Store Mode ✅ Implemented

**Pekko API:** `remember-entities-store`

**Purpose:** Configure how remembered entity IDs are stored (ddata vs eventsourced).

**Implemented as (N5):** `start`'s `:remember-entities-store` (`:ddata` / `:eventsourced`),
plus `:journal-plugin-id` / `:snapshot-plugin-id` for the eventsourced store. There is no
`with…` setter for the store mode, so `sharding/sharding-settings` builds the settings from
the system's own `pekko.cluster.sharding` config section with the key overridden.

Note: with the ddata store, remembered entities are written through the *durable* (LMDB)
replicator by default, which needs `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED` — or
`pekko.cluster.sharding.distributed-data.durable.keys = []` to keep them in memory (what
`test/resources/cluster-test.conf` does).

---

### 9. Sharded Daemon Process ✅ Implemented

**Pekko API:** `ShardedDaemonProcess.init` (typed only)

**Purpose:** Keep exactly `n` always-on workers running across the cluster — queue
consumers, projections, periodic jobs — rebalanced automatically, not addressed by id.

**Implemented as (N5):** `pekko-clj.cluster.daemon/start`. `ShardedDaemonProcess` has no
classic API, so each worker runs inside a narrow typed wrapper
(`pekko_clj.actor.CljDaemonProcess`) that spawns the classic `defactor` actor as its child,
forwards messages to it, and stops when it stops. Adds the `pekko-cluster-sharding-typed_3`
dependency; nothing user-facing becomes typed.

```clojure
(daemon/start sys "partition-workers" 4 partition-worker
  {:keep-alive-interval 5000 :role "workers" :stop-message :stop})
```

---

## Implementation Status

| Feature | Status | Notes |
|---------|--------|-------|
| entity-ref | ✅ Implemented | `entity-ref`, `tell-entity`, `ask-entity` |
| cluster-sharding-stats | ✅ Implemented | `cluster-sharding-stats`, `stats->map` |
| passivate-entity | ✅ Implemented | `passivate` (0- or 1-arg context) |
| Health checks | ✅ Implemented | `shard-region-registered?` |
| Advanced passivation | ✅ Implemented | `passivation-settings`, `start` `:passivation` |
| Remember-entities store | ✅ Implemented | `:remember-entities-store`, `sharding-settings` |
| Hand-off stop message | ✅ Implemented | `start` `:stop-message` |
| Persistent entities | ✅ Implemented | `start` takes a `defactor-persistent` def; id/init get the entity id |
| Region graceful shutdown | ✅ Implemented | `graceful-shutdown!` |
| Sharded daemon process | ✅ Implemented | `pekko-clj.cluster.daemon/start` (typed shim) |
| External allocation | ❌ Not implemented | Kafka co-location use case |
| Custom allocation | ❌ Not implemented | Advanced use case |

---

## Current Message Flow

```
User Code                    Shard Region              Entity Actor
    |                             |                         |
    |-- tell(region, id, msg) --> |                         |
    |   (entity-message envelope) |                         |
    |                             |-- route to shard -----> |
    |                             |                         |
    |                             |-- msg (unwrapped) ----->|
    |                             |   entity reads its own  |
    |                             |   id via (entity-id)    |
```

## Proposed EntityRef Flow

```
User Code                    EntityRef              Shard Region        Entity
    |                           |                       |                 |
    |-- (tell-entity ref msg)-->|                       |                 |
    |                           |-- entity-message ---->|                 |
    |                           |   (wraps internally)  |-- route ------->|
    |                           |                       |                 |
```

---

## Test Coverage ✅ Complete

Tests in `test/clj/pekko_clj/cluster/sharding_test.clj`:

- `sharding-start-test` - Basic sharding region startup
- `sharding-tell-ask-test` - Message sending via tell/ask
- `entity-ref-creation-test` - EntityRef creation
- `entity-ref-tell-ask-test` - EntityRef messaging
- `entity-ref-multiple-entities-test` - Multiple independent entities
- `get-shard-region-test` - Get existing shard region
- `shard-region-registered-test` - Health check for region registration
- `cluster-sharding-stats-test` - Cluster-wide statistics
- `passivation-settings-*-test` - Idle / LRU / SLRU / MRU / LFU / disabled strategy settings
- `sharding-settings-from-opts-test` - Settings built from `start` opts (passivation,
  role, remember-entities + store mode, persistence plugins)
- `idle-passivation-stops-entity-test` - An idle entity is passivated and recreated
- `manual-passivate-recreates-entity-test` - `passivate` + stop-message round trip
- `start-with-stop-message-and-remember-entities-test` - Hand-off stop message overload

Daemon process tests in `test/clj/pekko_clj/cluster/daemon_test.clj`:

- `daemon-settings-test` - Keep-alive interval and role settings
- `daemon-process-starts-all-instances-test` - All `n` workers start with their index
- `daemon-process-with-stop-message-test` - Stop message passes through the typed wrapper

---

## References

- [Pekko Cluster Sharding](https://pekko.apache.org/docs/pekko/current/typed/cluster-sharding.html)
- [Classic Cluster Sharding](https://pekko.apache.org/docs/pekko/current/cluster-sharding.html)
- [Sharding Configuration](https://pekko.apache.org/docs/pekko/current/general/configuration-reference.html#pekko-cluster-sharding)
