# Cluster Module Parity Spec

## Current Implementation Status

**File:** `src/main/clj/pekko_clj/cluster.clj`

### Implemented Features

| Function | Pekko API | Status |
|----------|-----------|--------|
| `cluster` | `Cluster.get(system)` | ✅ Complete |
| `create-system` | ActorSystem with cluster config | ✅ Complete |
| `join` | `Join(address)` | ✅ Complete |
| `leave` | `Leave(address)` | ✅ Complete |
| `down` | `Down(address)` | ✅ Complete |
| `self-member` | `selfMember` | ✅ Complete |
| `self-address` | `selfAddress` | ✅ Complete |
| `members` | `state.getMembers` | ✅ Complete |
| `leader` | `state.getLeader` | ✅ Complete |
| `is-leader?` | Leader comparison | ✅ Complete |
| `role-leader` | `state.roleLeader` | ✅ Complete |
| `unreachable-members` | `state.getUnreachable` | ✅ Complete |
| `has-role?` | `selfMember.hasRole` | ✅ Complete |
| `subscribe` | `cluster.subscribe` | ✅ Complete |
| `unsubscribe` | `cluster.unsubscribe` | ✅ Complete |
| `register-on-member-up` | `registerOnMemberUp` | ✅ Complete |
| `register-on-member-removed` | `registerOnMemberRemoved` | ✅ Complete |

---

## Recently Implemented Features

### 1. `join-seed-nodes` ✅ Implemented

**Pekko API:** `JoinSeedNodes(seedNodes)`

**Purpose:** Programmatically join cluster using a list of seed nodes, useful for dynamic discovery.

**Signature:**
```clojure
(defn join-seed-nodes
  "Join the cluster using a list of seed node addresses.

   Arguments:
   - system: ActorSystem
   - seed-nodes: Collection of address strings

   Example:
     (join-seed-nodes sys [\"pekko://app@host1:7355\"
                          \"pekko://app@host2:7355\"])"
  [system seed-nodes]
  ...)
```

---

### 2. `prepare-for-shutdown` ✅ Implemented

**Pekko API:** `prepareForFullClusterShutdown()`

**Purpose:** Coordinated cluster shutdown, marks all nodes as preparing to shutdown.

**Signature:**
```clojure
(defn prepare-for-shutdown
  "Prepare the cluster for a full coordinated shutdown.
   All nodes will be marked as PreparingForShutdown."
  [system]
  ...)
```

---

### 3. `is-terminated?` ✅ Implemented

**Pekko API:** `isTerminated`

**Purpose:** Check if the cluster extension has been terminated.

**Signature:**
```clojure
(defn is-terminated?
  "Check if the cluster has been terminated."
  [system]
  (.isTerminated (cluster system)))
```

---

### 4. `members-by-age` ✅ Implemented

**Purpose:** Get members sorted by age (oldest first), useful for singleton-like patterns.

**Signature:**
```clojure
(defn members-by-age
  "Get cluster members sorted by age (oldest first).
   Returns a sequence of member maps."
  [system]
  ...)
```

**Implementation Notes:**
- Sorts members by `upNumber` (join order) since `membersByAge` is not available in the classic API
- Lower upNumber = older member

---

### 5. `state-snapshot` ✅ Implemented

**Purpose:** Get a complete snapshot of the current cluster state.

**Signature:**
```clojure
(defn state-snapshot
  "Get the current cluster state as a map.

   Returns:
   - :members - All cluster members
   - :unreachable - Unreachable members
   - :leader - Current leader address
   - :seen-by - Nodes that have seen this state"
  [system]
  ...)
```

---

### 6. Multi-DC Support (Future)

**Pekko API:** `selfMember.dataCenter`, `state.allDataCenters`

**Purpose:** Support for multi-datacenter clusters.

**Functions to add:**
```clojure
(defn self-data-center [system])
(defn all-data-centers [system])
(defn members-in-dc [system data-center])
```

**Implementation Notes:**
- Requires `pekko-cluster` configuration with `multi-data-center` settings
- Access via `selfMember.dataCenter` and `state.getAllDataCenters`

---

## Event Types Currently Supported

| Event | Keyword | Status |
|-------|---------|--------|
| MemberUp | `:member-up` | ✅ |
| MemberJoined | `:member-joined` | ✅ |
| MemberLeft | `:member-left` | ✅ |
| MemberExited | `:member-exited` | ✅ |
| MemberRemoved | `:member-removed` | ✅ |
| MemberDowned | `:member-downed` | ✅ |
| MemberWeaklyUp | `:member-weakly-up` | ✅ |
| UnreachableMember | `:unreachable` | ✅ |
| ReachableMember | `:reachable` | ✅ |
| LeaderChanged | `:leader-changed` | ✅ |
| RoleLeaderChanged | `:role-leader-changed` | ✅ |
| ClusterShuttingDown | `:cluster-shutting-down` | ✅ |
| MemberPreparingForShutdown | `:member-preparing-for-shutdown` | ✅ |

---

## Test Coverage ✅ Complete

All new features have been tested in `test/clj/pekko_clj/cluster_test.clj`:

- `join-seed-nodes-test` - Tests joining cluster with seed nodes
- `prepare-for-shutdown-test` - Tests coordinated cluster shutdown and `:member-preparing-for-shutdown` event
- `members-by-age-test` - Tests members sorted by age (upNumber)
- `state-snapshot-test` - Tests cluster state snapshot
- `is-terminated-test` - Tests cluster termination status check

---

## References

- [Pekko Cluster Typed Documentation](https://pekko.apache.org/docs/pekko/current/typed/cluster.html)
- [Pekko Cluster Classic Documentation](https://pekko.apache.org/docs/pekko/current/cluster-usage.html)
- [Multi-DC Cluster](https://pekko.apache.org/docs/pekko/1.0/typed/cluster-dc.html)
