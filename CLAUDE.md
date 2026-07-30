# CLAUDE.md - Project Guide for AI Assistants

This file provides context for AI assistants (like Claude) working on the pekko-clj codebase.

## Project Overview

**pekko-clj** is an ergonomic Clojure wrapper for Apache Pekko (the open-source Akka fork). It provides idiomatic Clojure APIs for:

- Actor-based concurrency with `defactor` macro
- Event sourcing with `defactor-persistent`
- Reactive streams with functional composition
- Cluster membership, sharding, and singletons
- Router pools and groups

The goal is to make actors feel native to Clojure, similar to how GenServer feels native to Elixir.

## Architecture

### Core Components

```
src/main/clj/pekko_clj/
├── core.clj           # defactor macro, message passing (!, <?>, <!)
├── persistence.clj    # defactor-persistent, event sourcing
├── stream.clj         # Reactive streams API
├── cluster.clj        # Cluster membership, events, state
├── routing.clj        # Pool/group routers, strategies
├── supervision.clj    # Supervision strategies
└── cluster/
    ├── sharding.clj   # Cluster sharding, EntityRef
    └── singleton.clj  # Cluster singletons with supervision

src/main/java/pekko_clj/actor/
├── CljActor.java           # Core actor implementation
├── CljPersistentActor.java # Persistent actor implementation
├── BecomeResult.java       # Behavior switching support
└── FnWrapper.java          # Clojure function wrapper for Scala
```

### Key Design Patterns

1. **Implicit State Binding**: The `state` symbol is automatically bound in handler bodies
2. **Polymorphic Functions**: `spawn` works with both ActorSystem (top-level) and actor context (child)
3. **Pattern Matching**: `core.match` is used for message dispatch in `defactor`
4. **Scala/Java Interop**: Java classes bridge Clojure functions to Pekko's Scala APIs

## Build & Test

```bash
# Run all tests
lein test

# Run specific test namespace
lein test pekko-clj.core-test

# Run single test
lein test :only pekko-clj.core-test/counter-test

# Start REPL
lein repl
```

### Linting

```bash
# clj-kondo + cljfmt check — both must report zero findings (CI runs this)
lein lint

# Apply the formatting cljfmt can fix automatically
lein lint-fix
```

The tree is currently at **zero clj-kondo warnings and zero cljfmt diffs**; keep it there.

clj-kondo cannot expand `defactor` / `defactor-persistent`, so hooks teach it to read
them. Those hooks live in `resources/clj-kondo.exports/pekko-clj/pekko-clj/` (not in
`.clj-kondo/`) so they ship in the jar and downstream projects using `defactor` lint
cleanly too; `.clj-kondo/config.edn` consumes that same export via `:config-paths`.
**If you add or rename a clause in either macro, update the hook alongside it** —
otherwise every use of the new clause reports as an unresolved symbol. The same
directory holds `hooks/pekko_clj/routing.clj`, which scopes the `[id]` binding
vector of the HTTP route macros (`GET`/`POST`/`PUT`/`DELETE`/`PATCH`) over their
bodies.

Likewise, the `defactor` clauses and the routing DSL are body forms, not function
calls; `:cljfmt {:extra-indents ...}` in `project.clj` encodes that. New DSL forms
need an entry there or cljfmt will re-align their bodies as arguments.

### Reflection

`src/` compiles with **zero reflection warnings**, enforced in CI by grepping
`lein check`. `:global-vars {*warn-on-reflection* true}` in `project.clj` turns the
warnings on (the `:dev`/`:test` profiles switch them back off — test code does ad-hoc
interop where reflection is irrelevant and would bury the run in noise).

This is not only about speed. A reflective call resolves against the *runtime* class,
so a call that matches no declared Java signature still compiles and only blows up
when that line is first executed. Hinting the interop turned four such latent bugs
into compile errors:

| Broken call | Why it never worked |
|---|---|
| `bind-server` with a function handler | reified `java.util.function.Function`; `ServerBuilder.bind` wants Pekko's `japi.function.Function` |
| `(routing/complete status content-type body)` | `AllDirectives` has no `(StatusCode, ContentType, String)` overload |
| `routing/extract-strict-entity` | `toStrictEntity` takes a `java.time.Duration`, not a bare `long` |
| `spawn-pool-with-resizer` with `:balancing` | `BalancingPool` has no `withResizer` — its routees share one mailbox |

So: when adding interop, check the actual Java signature (`.getMethods`) rather than
assuming — and never silence a reflection warning without reading the signature first.

`pekko-clj.stream` is the one place reflection survives by design. javadsl `Source`
and `Flow` declare the same operators but share no supertype that declares them, so
the private `op` macro tests both concrete types and falls back to an explicit
`Reflector` call for the `SubSource`/`SubFlow` that `group-by` returns. `op` takes a
bare Java method name, which is why `.clj-kondo/config.edn` excludes it from
`:unresolved-symbol`.

### JVM Requirements

The project requires `--add-opens=java.base/java.nio=ALL-UNNAMED` for LevelDB (persistence tests). This is configured in `project.clj`.

## Code Conventions

### Namespaces

- Production code: `src/main/clj/pekko_clj/`
- Tests: `test/clj/pekko_clj/`
- Java sources: `src/main/java/pekko_clj/actor/`

### Naming

- Clojure functions: `kebab-case` (e.g., `spawn-pool`, `entity-ref`)
- Java classes: `PascalCase` (e.g., `CljActor`, `BecomeResult`)
- Test namespaces: `*-test` suffix (e.g., `pekko-clj.core-test`)

### Function Patterns

```clojure
;; Public API functions typically follow this pattern:
(defn function-name
  "Docstring with description.

   Arguments:
   - arg1: Description
   - arg2: Description

   Returns: Description

   Example:
     (function-name arg1 arg2)"
  [arg1 arg2]
  ...)

;; Private helpers use defn-
(defn- helper-fn [x] ...)
```

### Import Style

```clojure
(:import [org.apache.pekko.actor ActorSystem ActorRef]
         [org.apache.pekko.cluster Cluster Member]
         [pekko_clj.actor CljActor])
```

## Testing Patterns

### Actor System Lifecycle

Tests should create and terminate actor systems properly:

```clojure
(deftest my-test
  (let [sys (actor-system "test-system")]
    (try
      ;; Test code here
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) timeout-duration)))))
```

### Cluster Tests

Cluster tests require special setup:

```clojure
(defn create-cluster-system [name]
  (cluster/create-system name
    {:hostname "127.0.0.1"
     :port 0}))  ;; Port 0 = random available port

(defn wait-for-cluster-up [sys]
  (let [c (cluster/cluster sys)]
    (.join c (.selfAddress c))
    (loop [attempts 50]
      (when (pos? attempts)
        (let [status (str (.status (cluster/self-member sys)))]
          (if (= "Up" status)
            true
            (do (Thread/sleep 100)
                (recur (dec attempts)))))))))
```

### Async Testing

Use `Await/result` for Scala futures:

```clojure
(def timeout-duration (Duration/create 10 "seconds"))

(defn await-result [future]
  (Await/result future timeout-duration))

;; Usage
(let [result (await-result (core/<?> actor :get 5000))]
  (is (= expected result)))
```

## Common Tasks

### Adding a New Feature to a Module

1. Add the function to the appropriate namespace
2. Update imports if needed (Scala/Java classes)
3. Add docstring with arguments, return value, and example
4. Write tests in corresponding `*_test.clj` file
5. Update spec documentation in `docs/specs/` if applicable

### Working with Scala Duration

Pekko uses both `java.time.Duration` and `scala.concurrent.duration.FiniteDuration`:

```clojure
;; For BackoffOpts (uses Java Duration)
(java.time.Duration/ofMillis 1000)

;; For ClusterSingletonManagerSettings (uses Scala FiniteDuration)
(FiniteDuration/apply 1000 TimeUnit/MILLISECONDS)
```

### Adding Cluster Features

When implementing cluster features:

1. Check which Pekko class provides the functionality
2. Look at existing patterns in `cluster.clj`, `sharding.clj`, `singleton.clj`
3. Create cluster-enabled test system with `cluster/create-system`
4. Wait for cluster to be "Up" before testing
5. Use longer timeouts for cluster operations (10-30 seconds)

## API Parity Tracking

Feature parity with Pekko APIs is tracked in `docs/specs/`:

- `cluster-parity-spec.md` - Cluster membership features
- `routing-parity-spec.md` - Router strategies
- `sharding-parity-spec.md` - Cluster sharding
- `singleton-parity-spec.md` - Cluster singletons

When implementing features, update the corresponding spec to mark completion.

## Troubleshooting

### Common Issues

1. **ClassCastException with Duration**: Check if the API expects Java or Scala Duration
2. **Ask timeout**: Increase timeout, ensure actor is replying, check proxy paths
3. **Cluster not forming**: Verify seed nodes, check firewall, wait longer for convergence
4. **Singleton proxy not finding singleton**: Singleton identification takes time, increase wait

### Debug Logging

Test configuration lives in `test/resources/` as explicitly-loaded HOCON files
(`cluster-test.conf`, `persistence-test.conf`) — there is no `application.conf`. To enable
Pekko debug logging, add the block below to the relevant test conf (or create
`test/resources/application.conf`, which Pekko loads as the default fallback):

```hocon
pekko {
  loglevel = "DEBUG"
  actor.debug {
    receive = on
    lifecycle = on
  }
}
```

## Key Files Reference

| File | Purpose |
|------|---------|
| `project.clj` | Dependencies, build config |
| `src/main/clj/pekko_clj/core.clj` | Core API, `defactor` macro |
| `src/main/java/pekko_clj/actor/CljActor.java` | Java actor implementation |
| `test/resources/cluster-test.conf` | Cluster test Pekko configuration |
| `test/resources/persistence-test.conf` | Persistence test Pekko configuration |
| `docs/ROADMAP.md` | Epic 1 tracker (complete — parity/hardening/bug work) |
| `docs/ROADMAP-2.md` | Epic 2 tracker (complete) |
| `docs/ROADMAP-3.md` | Epic 3 tracker (complete) |
| `docs/ROADMAP-4.md` | Epic 4 tracker (ACTIVE — final review audit, serious bugs only) |
| `docs/specs/README.md` | Feature parity overview |

## Version Information

- Clojure: 1.11.1
- Apache Pekko: 1.6.0
- Apache Pekko HTTP: 1.4.0
- Scala: 3.x (binary compatibility via `_3` suffix)
