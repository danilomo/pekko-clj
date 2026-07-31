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
| `prepare-for-shutdown` | `prepareForFullClusterShutdown` | ✅ Complete |
| `split-brain-resolver-config` | SBR HOCON + `SplitBrainResolverProvider` | ✅ Complete (N6) |
| `create-system` `:split-brain-resolver` | SBR wired into system config | ✅ Complete (N6) |
| `coordinated-shutdown` / `add-shutdown-task` / `add-cancellable-shutdown-task` / `add-jvm-shutdown-hook` / `run-coordinated-shutdown` | `CoordinatedShutdown` | ✅ Complete (N6) |

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

### 6. Split Brain Resolver config helper ✅ Implemented (N6)

**Pekko API:** `pekko.cluster.split-brain-resolver.*` + `SplitBrainResolverProvider`.

**Purpose:** Configure the recommended downing provider (SBR) without hand-writing HOCON.

**Functions:**
```clojure
;; Standalone: build a Config for the SBR (pass as :extra-config, or use the
;; :split-brain-resolver key on create-system).
(split-brain-resolver-config
  {:active-strategy :static-quorum   ; :keep-majority (default) | :static-quorum
                                     ; | :keep-oldest | :down-all | :lease-majority
   :quorum-size 3
   :role "backend"
   :stable-after 15000               ; ms number or HOCON duration string
   :down-all-when-unstable true})    ; true→on | false→off | duration

;; Wired into create-system:
(create-system "app" {:port 7355
                      :split-brain-resolver {:active-strategy :keep-majority
                                             :stable-after 20000}})
```
Precedence in `create-system`: `:extra-config` > `:split-brain-resolver` > generated
defaults > reference.conf.

---

### 7. Coordinated Shutdown wrapper ✅ Implemented (N6)

**Pekko API:** `CoordinatedShutdown` (`addTask`, `addCancellableTask`, `run`,
`addJvmShutdownHook`), phase/reason constants.

**Purpose:** Register cleanup tasks that run phase-by-phase on ActorSystem shutdown, and
trigger shutdown programmatically. Complements `prepare-for-shutdown`.

**Functions:**
```clojure
(coordinated-shutdown system)                 ; the extension (any ActorSystem)
shutdown-phases                                ; keyword → phase-name map (ordered)
shutdown-reasons                               ; keyword → CoordinatedShutdown.Reason map
(add-shutdown-task system :before-actor-system-terminate "flush" (fn [] ...))
(add-cancellable-shutdown-task system phase name f)  ; → Cancellable
(add-jvm-shutdown-hook system (fn [] ...))
(run-coordinated-shutdown system)             ; → CompletableFuture<Done>
(run-coordinated-shutdown system :jvm-exit)   ; reason keyword / Reason / nil
```
A task fn returning a `CompletionStage` is awaited before its phase completes; any other
return completes the task immediately (`Done`).

---

### 8. Multi-DC Support (Future)

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

N6 (SBR + CoordinatedShutdown) is covered in `test/clj/pekko_clj/coordination_test.clj`:

- SBR config rendering per strategy, duration/on-off/role/quorum handling, unknown-strategy throw
- `create-system` merges `:split-brain-resolver` into the live config; `:extra-config` wins over it
- CoordinatedShutdown: task runs on shutdown, `CompletionStage` task awaited, cancelled task skipped,
  phase/reason maps, bad phase/reason throw

---

## References

- [Pekko Cluster Typed Documentation](https://pekko.apache.org/docs/pekko/current/typed/cluster.html)
- [Pekko Cluster Classic Documentation](https://pekko.apache.org/docs/pekko/current/cluster-usage.html)
- [Multi-DC Cluster](https://pekko.apache.org/docs/pekko/1.0/typed/cluster-dc.html)
