# Cluster Singleton Module Parity Spec

## Current Implementation Status

**File:** `src/main/clj/pekko_clj/cluster/singleton.clj`

### Implemented Features

| Function | Pekko API | Status |
|----------|-----------|--------|
| `start` | `ClusterSingletonManager.props` | ✅ Complete |
| `proxy` | `ClusterSingletonProxy.props` | ✅ Complete |
| `start-with-proxy` | Convenience wrapper | ✅ Complete |
| `singleton-running-here?` | State query | ✅ Complete |

### Implemented Options

| Option | Pekko API | Status |
|--------|-----------|--------|
| `:name` | Manager actor name | ✅ |
| `:role` | `withRole` | ✅ |
| `:args` | Actor init args | ✅ |
| `:termination-message` | Stop message | ✅ |
| `:hand-over-retry-interval` | `withHandOverRetryInterval` | ✅ |
| `:buffer-size` | `withBufferSize` (proxy) | ✅ |
| `:identification-interval-ms` | `withSingletonIdentificationInterval` (proxy) | ✅ |
| `:supervision` | `BackoffSupervisor` wrapper | ✅ |

### Supervision Options

| Option | Description | Status |
|--------|-------------|--------|
| `:strategy :restart-with-backoff` | Backoff on failure | ✅ |
| `:strategy :restart-with-stop` | Backoff on stop | ✅ |
| `:min-backoff-ms` | Min backoff delay | ✅ |
| `:max-backoff-ms` | Max backoff delay | ✅ |
| `:random-factor` | Backoff randomization | ✅ |

---

## Recently Implemented Features

### 1. Supervision Strategy ✅ Implemented

**Pekko API:** `BackoffSupervisor.props`

**Purpose:** Configure restart behavior for singleton actors with backoff.

**Usage:**
```clojure
(singleton/start sys leader-actor
  {:name "cluster-leader"
   :supervision {:strategy :restart-with-backoff
                 :min-backoff-ms 1000
                 :max-backoff-ms 30000
                 :random-factor 0.2}})
```

**Supported Strategies:**
- `:restart-with-backoff` - Restart on failure with exponential backoff
- `:restart-with-stop` - Restart on stop with exponential backoff

**Hand-over under supervision (B22):** with `:restart-with-stop`, the resolved
`:termination-message` is wired as the supervisor's `withFinalStopMessage` so the
supervisor stops itself once the singleton stops in response, rather than
restarting it forever and stalling hand-over. `:restart-with-backoff` (onFailure)
hands over on a clean self-stop without extra wiring; the default PoisonPill stops
the supervisor directly.

---

### 2. Singleton Identification Interval ✅ Implemented

**Pekko API:** `withSingletonIdentificationInterval`

**Purpose:** How often proxy sends identify messages to find singleton.

**Usage:**
```clojure
(singleton/proxy sys
  {:singleton-manager-path "/user/cluster-leader"
   :identification-interval-ms 500})  ;; Faster identification
```

---

### 3. Singleton State Query ✅ Implemented

**Pekko API:** Custom implementation

**Purpose:** Query if singleton is running on this node.

**Usage:**
```clojure
(when (singleton/singleton-running-here? sys "/user/cluster-leader")
  (println "I am the leader!"))
```

---

## Remaining Features (Low Priority)

### 1. Lease Integration (Low Priority)

**Pekko API:** `withLeaseSettings`

**Purpose:** Additional split-brain safety using a distributed lease.

**Implementation Notes:**
- Requires external lease implementation (e.g., Kubernetes lease)
- Configure via `pekko.cluster.singleton.use-lease`
- Add `pekko-coordination` dependency
- More complex - typically config-based

---

## Implementation Status Summary

| Feature | Status | Notes |
|---------|--------|-------|
| Supervision (backoff) | ✅ Implemented | `:restart-with-backoff`, `:restart-with-stop` |
| Identification interval | ✅ Implemented | `:identification-interval-ms` |
| Singleton state query | ✅ Implemented | `singleton-running-here?` |
| Lease integration | ❌ Not implemented | Complex, requires external dependency |

---

## Current Usage Pattern

```clojure
;; Basic singleton
(def leader (singleton/start sys leader-actor
              {:name "cluster-leader"
               :role "backend"}))

;; Access via proxy from any node
(def leader-proxy (singleton/proxy sys
                    {:singleton-manager-path "/user/cluster-leader"
                     :role "backend"}))

(core/! leader-proxy [:assign-work task])

;; Combined start + proxy
(let [{:keys [manager proxy]} (singleton/start-with-proxy sys leader-actor
                                {:name "cluster-leader"})]
  (core/! proxy [:do-work]))
```

## Enhanced Pattern with Supervision

```clojure
;; Singleton with supervision
(def leader (singleton/start sys leader-actor
              {:name "cluster-leader"
               :role "backend"
               :supervision {:strategy :restart-with-backoff
                             :min-backoff-ms 1000
                             :max-backoff-ms 30000
                             :random-factor 0.2}}))

;; Proxy with custom settings
(def leader-proxy (singleton/proxy sys
                    {:singleton-manager-path "/user/cluster-leader"
                     :role "backend"
                     :buffer-size 2000
                     :identification-interval-ms 500}))

;; Check if singleton is running locally
(when (singleton/singleton-running-here? sys "/user/cluster-leader")
  (log/info "This node is the singleton host"))
```

---

## Test Coverage ✅ Complete

Tests in `test/clj/pekko_clj/cluster/singleton_test.clj`:

- `singleton-start-test` - Basic singleton startup
- `singleton-proxy-test` - Proxy creation
- `singleton-start-with-proxy-test` - Combined start + proxy
- `singleton-with-role-test` - Role-constrained singleton
- `singleton-with-hand-over-settings-test` - Hand-over configuration
- `proxy-with-custom-settings-test` - Proxy customization
- `singleton-with-backoff-supervision-test` - Supervision with backoff
- `singleton-restart-with-stop-hands-over-with-custom-message` - B22: `:restart-with-stop` hand-over
- `singleton-restart-with-backoff-hands-over-with-custom-message` - B22: `:restart-with-backoff` hand-over
- `singleton-supervision-default-poison-pill-hands-over` - B22: default PoisonPill hand-over under supervision
- `singleton-running-here-test` - State query function

---

## Configuration Reference

```hocon
pekko.cluster.singleton {
  # Actor name of the singleton
  singleton-name = "singleton"

  # Role for singleton placement
  role = ""

  # Hand-over retry interval
  hand-over-retry-interval = 1s

  # Lease settings for split-brain safety
  use-lease = ""
  lease-retry-interval = 5s
}

pekko.cluster.singleton-proxy {
  # Actor name of the singleton to proxy
  singleton-name = "singleton"

  # Role where singleton runs
  role = ""

  # Interval for singleton identification
  singleton-identification-interval = 1s

  # Buffer size during hand-over
  buffer-size = 1000
}
```

---

## References

- [Pekko Cluster Singleton](https://pekko.apache.org/docs/pekko/current/typed/cluster-singleton.html)
- [Classic Cluster Singleton](https://pekko.apache.org/docs/pekko/current/cluster-singleton.html)
- [BackoffSupervisor](https://pekko.apache.org/docs/pekko/current/typed/fault-tolerance.html)
