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
| `entity-message` | Envelope record | ✅ Complete |

### Implemented Options

| Option | Pekko API | Status |
|--------|-----------|--------|
| `:type-name` | Type name | ✅ |
| `:role` | `withRole` | ✅ |
| `:num-shards` | Extractor param | ✅ |
| `:passivate-after` | `withPassivateIdleEntityAfter` | ✅ |
| `:remember-entities` | `withRememberEntities` | ✅ |

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

### 2. Advanced Passivation Strategies (Medium Priority)

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

**Implementation Notes:**
- Requires configuration-based setup, not programmatic
- Generate HOCON config string for passivation settings:
  ```hocon
  pekko.cluster.sharding.passivation {
    strategy = "default-strategy"
    default-strategy {
      active-entity-limit = 100000
      replacement.policy = "least-recently-used"
    }
  }
  ```
- Pass config to `ClusterShardingSettings.create(system, config)`

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
   - :regions - Map of region address to shard stats"
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

### 8. Remember Entities Store Mode (Low Priority)

**Pekko API:** `remember-entities-store`

**Purpose:** Configure how remembered entity IDs are stored (ddata vs eventsourced).

**Signature:**
```clojure
;; Add to start opts
:remember-entities-store - Storage mode (:ddata or :eventsourced)
```

**Implementation Notes:**
- Configuration-based: `pekko.cluster.sharding.remember-entities-store`
- `:ddata` - Use Distributed Data (default)
- `:eventsourced` - Use Event Sourcing with persistence

---

## Implementation Status

| Feature | Status | Notes |
|---------|--------|-------|
| entity-ref | ✅ Implemented | `entity-ref`, `tell-entity`, `ask-entity` |
| cluster-sharding-stats | ✅ Implemented | `cluster-sharding-stats`, `stats->map` |
| passivate-entity | ✅ Implemented | `passivate` function |
| Health checks | ✅ Implemented | `shard-region-registered?` |
| Advanced passivation | ❌ Not implemented | Configuration-based |
| External allocation | ❌ Not implemented | Kafka co-location use case |
| Custom allocation | ❌ Not implemented | Advanced use case |

---

## Current Message Flow

```
User Code                    Shard Region              Entity Actor
    |                             |                         |
    |-- tell(region, id, msg) --> |                         |
    |   (EntityMessage envelope)  |                         |
    |                             |-- route to shard -----> |
    |                             |                         |
    |                             |<-- [:entity-message     |
    |                             |     id, msg] ---------->|
    |                             |                         |
```

## Proposed EntityRef Flow

```
User Code                    EntityRef              Shard Region        Entity
    |                           |                       |                 |
    |-- (tell-entity ref msg)-->|                       |                 |
    |                           |-- EntityMessage ----->|                 |
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

---

## References

- [Pekko Cluster Sharding](https://pekko.apache.org/docs/pekko/current/typed/cluster-sharding.html)
- [Classic Cluster Sharding](https://pekko.apache.org/docs/pekko/current/cluster-sharding.html)
- [Sharding Configuration](https://pekko.apache.org/docs/pekko/current/general/configuration-reference.html#pekko-cluster-sharding)
