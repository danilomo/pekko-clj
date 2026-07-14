# pekko-clj Roadmap & Epic Tracker

This is the **living tracker** for the "Parity, Hardening & Ergonomics" epic. It is designed
so that **isolated sessions can pick up one story at a time**. It is the source of truth for
what's done and what's next — keep it accurate.

## Goal

`pekko-clj` is an ergonomic Clojure wrapper over Apache Pekko whose aim is to make the actor
model feel as native to Clojure as GenServer feels in Elixir. This epic covers three classes
of work found in an audit against the upstream Pekko feature surface
(<https://pekko.apache.org/docs/pekko/current/>):

1. **Correctness bugs** — confirmed, high-impact defects.
2. **Hardening / DX** — test flakiness, reflection, doc drift, missing conveniences.
3. **Parity gaps** — Pekko capabilities not yet wrapped.

Scope decisions: **comprehensive** breadth, **classic (untyped) actors only** (typed deferred;
narrow shims only where a module is typed-only), and a Pekko **version bump** is in scope.
Everything is classic Pekko bridged via the Java classes in `src/main/java/pekko_clj/actor/`.

## How to work this epic (every session read this)

1. Pick the next story with status `TODO` whose **dependencies are all `DONE`** — respect the
   milestone order (F → B → H → N); within a milestone prefer lower-risk stories first.
2. Set it to `PROGRESS` here.
3. Implement it; add/expand tests; run `lein test` (targeted namespace first, then full).
4. Set it to `DONE` with a one-line note, tick the parity checklist below, and update
   `docs/specs/*` where a matching parity spec exists.
5. Never leave the build red between stories.

**Status legend:** `TODO` → `PROGRESS` → `DONE` (use `BLOCKED — <reason>` if a dep slips).

---

## Story index

| ID | Title | Milestone | Status | Deps | Risk |
|----|-------|-----------|--------|------|------|
| F0 | ROADMAP tracker + doc-drift reconcile | Foundation | DONE | — | trivial |
| F1 | Bump Pekko toward latest 1.x | Foundation | DONE | F0 | medium |
| B1 | Fix vector-state landmine in `CljActor` | Bugs | DONE | F0 | medium |
| B2 | Fix ask ergonomics & error propagation | Bugs | DONE | F0 | medium |
| B3 | `defactor` unhandled-message parity | Bugs | DONE | F0 | low |
| B4 | Fix `persistence/reply` + macro double-splice | Bugs | DONE | F0 | low |
| B5 | Fix `cluster/join` + `clojure.string` requires + `create-system` | Bugs | DONE | F0 | low |
| B6 | Fix `singleton-running-here?` | Bugs | DONE | F0 | low |
| B7 | Fix `source-actor-ref` + deprecated stream factories + dead imports | Bugs | DONE | F0 | low |
| B8 | Fix sharding entity-message envelope mismatch | Bugs | DONE | F0 | medium |
| B9 | Fix HTTP per-request materializer leaks + response stubs | Bugs | DONE | F0 | low |
| B10 | Stop `CljActor` swallowing `Throwable` | Bugs | DONE | B1, B3 | medium |
| H1 | TestKit companion namespace + dep | Hardening | DONE | F1 | low |
| H2 | De-flake tests + shared cluster helpers | Hardening | DONE | H1 | low |
| H3 | `*warn-on-reflection*` cleanup + type hints | Hardening | DONE | F1 | low |
| H4 | Restore singleton message-passing test | Hardening | DONE | B6, H1, H2 | low |
| H5 | Core DX polish (`context`, `stop`, `actor-selection`, …) | Hardening | DONE | B1, B2, B3 | low |
| H6 | Macro hygiene & docs | Hardening | DONE | B1, B4 | low |
| N1 | Streams depth (mat values, KillSwitch, supervision, actor interop) | New | TODO | B7, F1 | medium |
| N2 | Persistence query + event tagging + snapshot retention | New | TODO | B4, N1 | medium |
| N3 | Distributed Pub-Sub / Topic | New | TODO | B5 | low |
| N4 | Clojure-data serializer (Transit) + bindings helper | New | TODO | B5 | medium |
| N5 | Sharding: passivation, remember-entities, daemon-process | New | TODO | B8 | medium |
| N6 | Split-Brain-Resolver + CoordinatedShutdown helpers | New | TODO | B5 | low |
| N7 | HTTP routing-DSL completion + marshalling + websockets | New | TODO | B9 | medium |
| N8 | Distributed Data (selective CRDTs) | New | TODO | B5, N3 | medium |
| N9 | Classic actor extras (ReceiveTimeout, EventStream, …) | New | TODO | B1, B3, H5 | low |

**Definition of done for the epic:** all B + H stories `DONE`; N1–N7 `DONE` (N8/N9 optional);
this file and `docs/specs/*` reflect reality; version pins consistent across
`README.md`/`CLAUDE.md`/`docs/specs/README.md`; `lein test` green with no reflection warnings
on core hot paths.

---

## Milestone F — Foundation

### F0 · ROADMAP tracker + doc-drift reconcile — `DONE`
**Deps:** none.
- Created this file.
- Fixed `CLAUDE.md`'s `test/resources/application.conf` references (file does not exist;
  actual configs are `cluster-test.conf` / `persistence-test.conf`) — updated the Debug
  Logging note and Key Files table (which now also points here).
- Reconciled version pins: `docs/specs/README.md` dependency block corrected from `1.1.0`
  to actual `1.1.3` core / `1.1.0` http, and given a pointer to this roadmap.
- Filled in `project.clj` `:description`/`:url` (were `FIXME`).

### F1 · Bump Apache Pekko toward latest 1.x — `DONE`
**Deps:** F0.
- Bump `pekko-actor/stream/persistence/cluster*/http` in `project.clj` from 1.1.3 / http 1.1.0
  toward the current 1.x line (Scala 3 `_3` artifacts; keep the pekko-core modules aligned;
  pekko-http versions independently). Run the full suite; record deprecations here. Do not
  chase deprecation fixes beyond what breaks compilation (those live in B7/B10).
- **Verify:** `lein test` green; record exact versions in `README.md`, `CLAUDE.md`,
  `docs/specs/README.md`.
- **Done (2026-07-12):** pekko-core `1.1.3 → 1.6.0` (actor/stream/persistence/cluster/
  cluster-sharding/cluster-tools; transitives remote/coordination/distributed-data/pki/
  protobuf all resolve `1.6.0`), pekko-http `1.1.0 → 1.3.0`; scala3-library pulled to `3.3.7`.
  Version pins updated in `project.clj`, `README.md`, `CLAUDE.md`, `docs/specs/README.md`.
  Compilation clean (no removed APIs → **no source changes**); `lein test` green both before
  and after (273 tests / 491 assertions / 0 failures / 0 errors).
- **Deprecations observed (deferred, not compile-breaking):** runtime WARN —
  `pekko.cluster.sharding.passivate-idle-entity-after` setting + methods are deprecated in
  favour of `pekko.cluster.sharding.passivation.default-idle-strategy.idle-entity.timeout`
  (the new automatic-passivation strategy config). Owned by **N5** (sharding passivation);
  the `sharding/passivate` idle-timeout path should migrate to the passivation-strategy config
  there. No `javac` deprecation notes and no other Clojure/Java compile warnings surfaced.

---

## Milestone B — Correctness bug fixes (highest priority)

Do B1–B4 before feature work touching core/persistence. Locations below are confirmed.

### B1 · Fix the vector-state landmine in `CljActor` — `DONE`
**Deps:** F0.
`CljActor.handleState` (`CljActor.java:124-136`) treats **any** returned `PersistentVector` as
a `[new-fn new-state]` become-pair (`handleSeq` casts `seq.first()` to `IFn`). A handler whose
state legitimately is a vector throws `ClassCastException` or silently swaps behavior.
**Fix:** make the become channel `BecomeResult`-only (already handled at `CljActor.java:117`);
delete the `PersistentVector` branch + `handleSeq` so a returned vector is just state. Confirm
`become`/`defactor` still switch via `BecomeResult` (`core.clj:102`). **Tests:** vector-state
actor that mutates across messages; update `become_result_test.clj`.
- **Done (2026-07-13):** deleted the `PersistentVector` branch and `handleSeq` from
  `handleState`; behavior switching now flows through `BecomeResult` only, so a handler (or
  `init`) returning a vector is stored as state. Removed the now-unused `ISeq`/`Seqable` imports
  (`PersistentVector` kept — still used for the `Terminated` → `[:terminated ref]` translation).
- **Tests (`defactor_test.clj`):** a `vector-state-actor` whose state is a vector — empty-vector
  init (previously NPE'd in `handleSeq` on an empty seq) and integer-element pushes across
  messages (previously `(IFn) 1` `ClassCastException` / silent behavior swap). `become` via
  `BecomeResult` still verified by the existing `become-switches-behavior` and
  `become_result_test.clj` (the latter unit-tests the value class and needed no change — actor
  become behavior lives in `defactor_test.clj`, which has the actor fixture). Full suite green
  (292 tests / 530 assertions / 0 failures / 0 errors). **Unblocks B10.**

### B2 · Fix ask ergonomics & error propagation — `DONE`
**Deps:** F0.
`<?>` (`core.clj:76`) returns a raw `scala.concurrent.Future` — not derefable/composable;
leaks through `sharding/ask`, `sharding/ask-entity`, `routing/get-routees`,
`sharding/shard-region-state`, `cluster-sharding-stats`. `<!` (`core.clj:84`) loses failures:
on a failed future `.get` throws inside the `FnWrapper` callback, `deliver` never runs, and
`(deref result timeout nil)` returns `nil` — identical to a timeout.
**Fix:** return `java.util.concurrent.CompletionStage` (via `FutureConverters/asJava` or a
`Patterns/ask` `CompletionStage` overload) so results `deref`/`.thenApply`; make `<!`
distinguish failure from timeout. Thread through the sharding/routing callers. Document that
`<!` blocks and must not run on a dispatcher thread. **Tests:** success, timeout, throwing
handler (failure surfaces, not `nil`) in `core_test.clj`.
- **Done (2026-07-13):** `<?>` now uses the `Patterns/ask(…, java.time.Duration)` overload and
  returns `(.toCompletableFuture …)` — a `CompletableFuture` (an `IS-A CompletionStage` and
  `Future`), so results `@`-deref and `.thenApply`-compose. The sharding/routing callers are
  pass-throughs and now return it automatically (docstrings updated; removed the `FnWrapper`
  import, no longer used). `<!` was rewritten around a private `ask-blocking`: it blocks on the
  future and **distinguishes failure from timeout** — a genuine failure (future completes
  exceptionally with anything but `AskTimeoutException`, e.g. a `Status/Failure` reply) is
  rethrown unwrapped; a timeout (`AskTimeoutException`, or the +1000ms block guard) returns nil.
  `<!` also gained an ergonomic system-less form and keeps the legacy `system`-first arities
  (leading `ActorSystem` accepted and ignored — no execution context is needed any more), so the
  ~80 existing call sites keep working. Docstring warns it blocks / must not run on a dispatcher
  thread.
- **Tests:** `core_test.clj` — `<?>` returns a `CompletionStage`/`CompletableFuture`, derefs,
  composes via `.thenApply`; `<!` surfaces a `Status/Failure` reply (throws, not nil), returns
  nil on a no-reply timeout, and the 2-arg form works. Migrated every test that consumed the old
  Scala future: the 7 `await-ask` helpers and `persistence_test` inline asks now call `<!`; the
  2 `await-result` helpers and the `cluster_test`/`routing_test` inline `Await/result`s now
  `deref` the `CompletableFuture`. Full suite green (298 tests / 541 assertions / 0 failures /
  0 errors). **Unblocks H5** (→ N9); the hard-coded 5000ms in `sharding/ask` timeout unification
  is left to H5 as noted there.
- **Milestone B remaining:** only B8 (sharding entity-message envelope mismatch) is still `TODO`.

### B3 · `defactor` unhandled-message parity — `DONE`
**Deps:** F0.
`defactor`'s `m/match` (`core.clj:166`) has no catch-all → `MatchError` crashes the actor,
unlike `defactor-persistent` (`:else`). **Fix:** append a default calling Pekko `unhandled()`
(→ dead-letters) unless the user supplied a catch-all. Expose `getContext()`/`unhandled` via a
small helper (see H5 `context`). **Tests:** unmatched message → actor survives, message hits
dead-letters (H1 testkit or `EventStream` DeadLetter probe).
- **Done (2026-07-12):** `defactor` now appends `:else (do (.unhandled this msg) nil)` to the
  `m/match` unless the user already supplied a catch-all, detected by `catch-all-pattern?`
  (`core.clj`) — a bare local symbol (e.g. `msg`/`_`) or `:else`. Returning `nil` is a
  state-preserving no-op (`CljActor.handleState(null)`), so an unmatched message no longer
  throws `MatchError` / restarts the actor. Added `public void unhandled(Object)` override on
  `CljActor` (delegates to Pekko's `super.unhandled`, publishing an `UnhandledMessage` to the
  event stream) and a `core/unhandled` helper for user-written catch-alls.
- **Tests (`defactor_test.clj`):** unmatched-message survival, state preservation across an
  unmatched message (proves no restart), `UnhandledMessage` published to the event stream
  (recipient + payload asserted), and a regression that bare-symbol catch-alls still match
  everything. Full suite green (277 tests / 498 assertions / 0 failures / 0 errors).
- **Note:** Pekko's `unhandled()` publishes an `UnhandledMessage` on the event stream (the
  canonical "unhandled" channel), not a raw `DeadLetter`; the test probes that class. No
  `docs/specs/*` checklist covers core, so nothing to tick there.

### B4 · Fix `persistence/reply` + macro double-splice — `DONE`
**Deps:** F0.
`persistence/reply` (`persistence.clj:208-212`) does `(.reply ^CljPersistentActor (resolve
'this) msg)` — `(resolve 'this)` resolves a var → `nil` → NPE. **Fix:** bind the persistent
actor in a dynamic var (mirror `*current-actor*`, `core.clj:7`) in the generated command
handler and have `reply` use it (or take the actor explicitly). Remove the **double splice**
of `~command-handler`/`~event-handler` (`persistence.clj:145-146` and `156-157`); add a
docstring to the `defactor-persistent` def. **Tests:** `p/reply`, `recovering?`,
`trigger-snapshot!` in `persistence_test.clj`.
- **Done (2026-07-12):** added `*current-persistent-actor*` dynamic var, bound it in the
  generated command handler (`build-command-handler`), and rewrote `reply` to `(.reply
  *current-persistent-actor* msg)` returning `nil` (so a command ending in `(reply …)` persists
  nothing — no trailing `nil` needed). Removed the double splice: the macro now binds every
  generated form (command/event handler, init-fn, persistence-id-fn, snapshot-every,
  on-recovery-complete) to a `let` local exactly once and references the locals from both the
  actor-def map and its `:make-props`, so each handler fn is compiled once. Added optional
  leading-docstring support to `defactor-persistent` (attaches `:doc` to the generated var only
  when present — no empty-string metadata), mirroring `defactor`.
- **Tests (`persistence_test.clj`):** `reply-helper-actor` (docstring'd) exercises `p/reply`
  (NPE regression), `p/recovering?` (false after recovery), and `p/trigger-snapshot!`
  (+ recover), plus a docstring-preserved assertion. Full suite green (281 tests / 505
  assertions / 0 failures / 0 errors). Feeds **N2** (persistence query/tagging/retention).
- **Note:** no `docs/specs/*` checklist covers persistence, so nothing to tick there.

### B5 · Fix `cluster/join` + `clojure.string` requires + `create-system` — `DONE`
**Deps:** F0.
`cluster/join` 1-arity (`cluster.clj:115-116`) calls `(.join (cluster system))` — no such
no-arg method. **Fix:** route no-address arity to `joinSeedNodes` (reuse `join-seed-nodes`,
`cluster.clj:120`) or drop it. Add missing `clojure.string` `:require` in `cluster.clj`,
`http/core.clj`, `http/routing.clj` (works only via transitive load; fails under
`:uberjar` direct-linking). Harden `create-system` (`cluster.clj:50-104`): it builds HOCON by
string concatenation and hard-codes `allow-java-serialization=on` + a fixed SBR — add an
`:extra-config`/raw-`Config` merge hook (keep java-serial default until N4). **Tests:**
join-seed path, roles/hostname escaping, `Config` passthrough.
- **Done (2026-07-12):** `join` 1-arity now reads `pekko.cluster.seed-nodes` from the system
  config and delegates to `join-seed-nodes` (a no-op when none are configured; a `declare`
  handles the forward reference). Added `[clojure.string :as str]` to `cluster.clj`,
  `http/core.clj`, `http/routing.clj` and switched the `clojure.string/*` call sites to `str/*`
  (no longer relies on transitive loading — safe under `:uberjar` direct-linking). `create-system`
  gained an `:extra-config` key (HOCON string or `Config`) merged with higher precedence over
  the generated HOCON via `.withFallback`, so callers can override the default SBR /
  serialization without giving up the map form; the java-serialization default is kept until N4.
- **Tests (`cluster_test.clj`):** `Config` passthrough, roles + hostname applied, `:extra-config`
  overrides the generated `provider` and adds a key, `join` no-op with no seeds (regression for
  the missing method), and `join` routing to `joinSeedNodes` with configured seeds. Full suite
  green (286 tests / 513 assertions / 0 failures / 0 errors).
- **Note:** B5 fixes bugs / adds a config hook rather than a new parity feature, so no
  `cluster-parity-spec.md` checklist item changes (SBR/coordinated-shutdown parity stays with
  N6). Unblocks **N3**, **N4**, **N6**.

### B6 · Fix `singleton-running-here?` — `DONE`
**Deps:** F0.
`singleton.clj:215` uses `(.address (.provider (.dispatcher system)))` — `dispatcher` returns
an `ExecutionContextExecutor` with no `.provider`; the call throws and is swallowed → always
`false`. **Fix:** cast to `ExtendedActorSystem`, use `.provider().getDefaultAddress()` (or
`Cluster.get(system).selfAddress()`), compare to the resolved ref's address. Unblocks H4.
- **Done (2026-07-12):** replaced the throwing `local-addr` with
  `(.getDefaultAddress (.provider ^ExtendedActorSystem system))`. Also fixed a second latent
  bug: the host guard was `(nil? (.host actor-addr))`, but `Address.host()` returns a
  `scala.Option` (never nil) so it never fired — changed to `(.isEmpty (.host actor-addr))`,
  which is what actually detects a locally-resolved (host-less) ref. `resolveOne` on a local
  path only resolves node-local actors, so a successful resolve ⇒ host-less ⇒ running here.
- **Tests (`singleton_test.clj`):** completed the positive branch of `singleton-running-here-test`
  — after `singleton/start` in a single-node cluster, `singleton-running-here?` now polls to
  `true` (would time out under the old always-false behaviour); kept the negative branches
  (nonexistent + other manager path → false). Added a `wait-until` polling helper. Full suite
  green (286 tests / 515 assertions / 0 failures / 0 errors).
- **Note:** bug fix, not a new parity feature, so no `singleton-parity-spec.md` checklist change
  (lease integration stays in backlog). Unblocks **H4** (restore singleton message-passing test;
  H4 also needs H1, H2).

### B7 · Fix `source-actor-ref` + deprecated stream factories + dead imports — `DONE`
**Deps:** F0.
`source-actor-ref` (`stream.clj:325-346`) binds the `preMaterialize` `Pair` backwards
(materialized value is `.first`), returning `[actor-ref source]` while promising
`[source actor-ref]`; `source-queue` (`stream.clj:~620`) reads the pair the other way — unify.
Un-skip the skipped `source-actor-ref` test. Migrate deprecated `Source/actorRef` 2-arg
(`:342`), `Sink/actorRef` 2-arg (`:279`), `OverflowStrategy/dropNew` (`:201,203,339,616`) to
current overloads. Remove unused imports (`GraphDSL*`, `ClosedShape`, `FlowShape`, `Logging*`,
`Pair`, `NotUsed`, `Done`, `stream.clj:18-23`) or wire them in N1.
- **Done (2026-07-12):** fixed `source-actor-ref` to read the `preMaterialize` `Pair`
  correctly (`.first` = ActorRef, `.second` = Source, same convention as `source-queue`) and
  return `[source actor-ref]` per its docstring. Wrote the previously-omitted stream test
  (`source-actor-ref-emits-and-completes`): asserts the second slot is an `ActorRef`, drives
  elements through `run-to-seq`, and completes via `Status.Success`. Removed the dead imports
  `GraphDSL`/`GraphDSL$Builder`, `ClosedShape`/`ClosedShape$`, `FlowShape`, `Logging`/
  `LoggingAdapter`, `Pair` (line 90 already uses the fully-qualified `org.apache.pekko.japi.Pair`),
  `NotUsed`, `Done` — all verified used only in docstrings; `Graph`/`SourceShape`/`SinkShape`
  kept as N1 graph-DSL placeholders.
- **Deprecation migration — NOT NEEDED (verified):** `javap -v` on `pekko-stream_3-1.6.0.jar`
  shows the javadsl `Source.actorRef(int, OverflowStrategy)`, `Sink.actorRef(ActorRef, Object)`,
  and `OverflowStrategy.dropNew()` carry **no** `Deprecated` attribute in Pekko 1.6.0 (Sink and
  OverflowStrategy have zero deprecated methods). F1's bump already made these the current,
  supported overloads, so migrating them would be gratuitous churn on working, non-deprecated
  APIs — left as-is. N1 can still adopt the matcher-based `actorRef` overloads if it wants
  explicit completion/failure control.
- Full suite green (287 tests / 517 assertions / 0 failures / 0 errors). Unblocks **N1**.

### B8 · Fix sharding entity-message envelope mismatch — `DONE`
**Deps:** F0.
The extractor (`sharding.clj:77-84`) rewrites an `EntityMessage` into `[:entity-message
entity-id message]`, but the documented entity actor (`sharding.clj:15-21`) matches the raw
pattern — nothing unwraps `[:entity-message …]`, so entities never match. `entityId` returns
`nil` for non-envelope/non-vector messages (`:72-76`), dropping them. **Fix:** deliver the
unwrapped payload to the entity; give non-envelope messages a sane entity-id path or reject.
Add an end-to-end test that drives an entity handler.
- **Done (2026-07-13):** the extractor's `entityMessage` now delivers the **unwrapped** payload
  (`(:message envelope)`) so an entity actor matches the raw message it was sent; `entityId`
  routes **only** `EntityMessage` envelopes (produced by `tell`/`ask`/`entity-ref`) and returns
  nil for anything else (dropped by Pekko — the ambiguous first-element-of-a-vector heuristic was
  removed). Added `sharding/entity-id`, which returns the current entity's id from its actor-path
  name (Pekko names each entity actor by its id); entities call it instead of relying on init
  args (which are nil for the shared entity Props). Updated the ns example + `start` docstring.
- **Tests:** rewrote the coupled test entity actors to the raw-pattern + `(entity-id)` form —
  `sharding_test.clj` (`counter-entity`) drives `[:inc]`/`[:get]`/`[:get-id]` end-to-end, and
  `cluster_test.clj` (`entity-actor`) drives `[:set-data …]`/`:get-data`/`:get-id`, with
  `sharding-entity-id-available` asserting the entity reports its own id. Full suite green (298
  tests / 541 assertions / 0 failures / 0 errors). Unblocks **N5** (passivation / remember-
  entities / daemon-process). No `sharding-parity-spec.md` checklist item changes (it fixes a
  bug rather than adding a parity feature).
- **Milestone B (correctness bugs) is COMPLETE** — B1–B10 all `DONE` (verified against the
  story index).

### B9 · Fix HTTP per-request materializer leaks + response stubs — `DONE`
**Deps:** F0.
`http/core.clj` `entity->string`/`entity->bytes` (`:156-174`) and `http/client.clj`
`response-body`/`-bytes`/`discard-body` (`:205-238`) call `Materializer/createMaterializer`
per call when handed a system — an unclosed materializer (actor leak) each time. **Fix:**
require an explicit materializer or cache one per system
(`SystemMaterializer.get(system).materializer()`). Implement the `response` headers stub
(`response.clj:138,151-153`) and `redirect` `Location` (`response.clj:200-209`). Add missing
`clojure.string` requires (overlaps B5).
- **Done (2026-07-13):** added `http.core/->materializer`, which returns the shared per-system
  materializer via `(.materializer (SystemMaterializer/get system))` instead of creating (and
  leaking) a new one each call; `entity->string`/`entity->bytes` and, via a `pekko-clj.http.core`
  require, `client/response-body`/`-bytes`/`discard-body` all route through it (removed the now
  dead `Materializer` import from `client.clj`). Implemented `response`'s 3-arity headers: a map
  of keyword/string names → values becomes `RawHeader`s applied with `.addHeaders`. Implemented
  `redirect` to attach a real `Location` header (`Location/create`). The missing `clojure.string`
  requires were already covered by B5 (verified `client.clj`/`response.clj` don't use it).
- **Tests (`response_test.clj`):** headers applied + read back (keyword and string names), empty
  headers no-op, and `redirect` sets 302/301 with the correct `Location` value. All HTTP suites
  green; full suite 290 tests / 528 assertions / 0 failures / 0 errors. Feeds **N7** (routing
  DSL / marshalling / rejection+exception handlers).

### B10 · Stop `CljActor` swallowing `Throwable` — `DONE`
**Deps:** B1, B3.
`CljActor.onReceive` (`CljActor.java:97-105`) catches all `Throwable` when an `on-error`
handler is set — so supervision deciders never fire for the actor's own failures, and
`Error`/`InterruptedException` are swallowed. **Fix:** narrow the catch (rethrow
`Error`/`InterruptedException`); define `on-error` vs supervision contract explicitly and
document it in the supervision docs and `defactor` docstring. **Tests:** `on-error` recovery;
a supervised throwing handler that is `:restart`ed. Extend `supervision_test.clj` /
`error_handling_test.clj`.
- **Done (2026-07-13):** narrowed the `catch (Throwable)` in `onReceive` — `Error` and
  `InterruptedException` are now rethrown (never routed to `on-error`); only other Throwables
  (recoverable Exceptions) go to the `on-error` handler, else propagate to supervision.
  Documented the contract inline in `onReceive`, in the `pekko-clj.supervision` ns docstring,
  and in a new `defactor` macro docstring (clauses + on-error/supervision behavior).
- **Tests (`error_handling_test.clj`):** `on-error-does-not-swallow-errors` (a thrown
  `AssertionError` reaches the parent's supervision decider and `on-error` is NOT called) and
  `on-error-intercepts-exceptions-before-supervision` (a `RuntimeException` is handled by
  `on-error` in place; the supervision decider is not invoked and the child keeps running).
  Existing `on-error` recovery and `:restart` supervision coverage
  (`child-actor-restarts-on-exception`, `supervision-with-defactor`) still green. Full suite
  294 tests / 535 assertions / 0 failures / 0 errors.
- **Milestone B remaining (at time of B10):** B2 and B8 — B2 since completed; only B8 remains.

---

## Milestone H — Hardening: tests, flakiness, DX

### H1 · TestKit companion namespace + dependency — `DONE`
**Deps:** F1. **Do before H2/H4.**
Add `pekko-testkit_3` (+ `pekko-stream-testkit_3`). Grow `pekko-clj.test` (`test.clj`, ~24
LOC) into a companion: `TestProbe` wrappers (`expect-msg`, `expect-msg-type`,
`expect-no-message`, `receive-n`, `await-assert`, `within`, `fish-for-message`),
`TestActorRef` (sync), streams `TestSource.probe`/`TestSink.probe`. User-facing feature too.
- **Done (2026-07-13):** added `pekko-testkit_3`/`pekko-stream-testkit_3` (1.6.0) to
  `project.clj` main deps (+ README module table). Replaced the scratch `test.clj` demo with a
  real companion built on `javadsl.TestKit` (Java `Duration`/`Supplier`/`Class` — far cleaner
  than the Scala `TestProbe`): `probe`/`probe-ref`/`send-to`/`last-sender`, `expect-msg`,
  `expect-msg-type`, `expect-no-message`, `receive-n`, `await-assert`, `within`,
  `fish-for-message`, `watch`/`unwatch`/`expect-terminated`, `shutdown`; `test-actor-ref` (sync
  CallingThreadDispatcher) + `underlying`; `test-source`/`test-sink` stream probes; `seconds`/
  `millis` duration helpers.
- **Tests (`testkit_test.clj`):** 11 tests exercising every wrapper end-to-end — probe
  expectations, `await-assert`/`within`/`fish-for-message`, `expect-terminated`, a synchronous
  `test-actor-ref` (state visible immediately after `!`), and driving a TestSource→TestSink
  probe pair. Full suite green (309 tests / 552 assertions / 0 failures / 0 errors).
- **Unblocks H2** (de-flake with `await-assert`/probes + shared helpers) and **H4** (restore the
  singleton message-passing test).

### H2 · De-flake tests + shared cluster helpers — `DONE`
**Deps:** H1.
Replace `Thread/sleep` synchronization (`routing_test`, `cluster_test` sharding,
`singleton_test`, `persistence_test`, `timer_test`, `stash_test`, `deathwatch_test`, stream
`fan-out`) with `await-assert`/probes. Deduplicate the three `wait-for-cluster-up`/
`create-*-system` helpers into one shared test-support namespace.
- **Done (2026-07-14):** new `pekko-clj.test-support` namespace holds the single
  `create-cluster-system` (loads `cluster-test.conf`), `wait-for-cluster-up`, `terminate-system`
  (deduped from `cluster_test`/`singleton_test`/`sharding_test` — the three local copies +
  `singleton`'s `wait-until` removed), plus `poll-until` and an `eventually` macro (poll a body
  until truthy, exceptions count as "not yet"; the bounded-poll replacement for fixed sleeps).
- **De-flaked** all eight named suites: converted ~55 synchronization `Thread/sleep`s to
  `eventually`/`poll-until`, and removed many outright where a blocking `<!`/`await-ask` already
  serialises behind prior sends (same mailbox, FIFO) — e.g. `stash_test` now has **zero** sleeps.
  Left legitimately-timed sleeps: in-stream/worker work-delays (`stream_test`, `routing_test`,
  `testkit_test`), post-cancel stability windows (`timer_test`) and the death-watch absence check
  (`deathwatch_test`), plus the internal poll loops in `test-support`.
- Not in scope / untouched: `error_handling_test`, `supervision_test` (not named), and the
  `#_`-disabled singleton message-passing test (H4). Full suite green and faster (309 tests /
  558 assertions / 0 failures / 0 errors). **Unblocks H4.**

### H3 · `*warn-on-reflection*` cleanup + type hints — `DONE`
**Deps:** F1.
No namespace sets `*warn-on-reflection*`; interop reflects on every call through
`*current-actor*` (`core.clj:15-281`) and on the untyped `src` in every stream op. Enable the
flag (dev profile or per-ns), add type hints — real per-message latency wins.
- **Done (2026-07-14):** `core.clj` (the actual per-message hot path) is now **reflection-free**.
  Tagged the `*current-actor*` dynamic var `^CljActor` (fixes ~20 per-message call sites in one
  stroke — `self`/`sender`/`reply`/`!`/timers/stash/watch/…), and hinted the remaining
  `target`/`system`/`src`/context/`CompletableFuture` sites (`!`, `spawn`, `<?>`, `ask-blocking`,
  `new-actor`). `set! *warn-on-reflection* true` per-ns at the top, reset to `false` at the
  bottom so it doesn't leak into namespaces compiled later. Verified `lein check` reports **0**
  reflection warnings for `core.clj`.
- **Streams:** the transformation ops (`smap`/`sfilter`/…) are genuinely polymorphic over
  `Source` **and** `SubSource` (`group-by` returns a `SubSource` and the tests transform the
  sub-streams), and Pekko exposes no common supertype with `.map`/`.filter`. A blanket `^Source`
  hint therefore breaks sub-stream usage (it did — two `group-by` tests). Since the stream
  reflection is **construction-time only** (element processing runs in materialized Java, not
  Clojure), the hints were reverted; a protocol/multimethod split could revisit this in N1.
- **Bug surfaced + fixed:** enabling the flag revealed `delay-each` called `Source.delay(Duration)`,
  which has **no matching method** (only `delay(Duration, DelayOverflowStrategy)`) — it would have
  thrown at runtime. Fixed to pass `DelayOverflowStrategy/backpressure`; added a regression test.
- Full suite green (311 tests / 562 assertions / 0 failures / 0 errors), no reflection warnings on
  the core per-message path (epic DoD).

### H4 · Restore singleton message-passing test — `DONE`
**Deps:** B6, H1, H2.
Un-comment/implement `singleton-message-passing-test` (`singleton_test.clj:~120`, `#_`);
complete the positive branch of `singleton-running-here-test`.
- **Done (2026-07-14):** restored `singleton-message-passing-test` with `eventually`-polling
  (spawn singleton via `start-with-proxy`, then poll `:get`/`:set` through the proxy). The
  positive branch of `singleton-running-here-test` was already completed under B6.
- **Real bug surfaced + fixed:** the test's original "timing issue" was a misdiagnosis — the
  `singleton/proxy` fn appended `/singleton` to the manager path before passing it to
  `ClusterSingletonProxy/props`, but that API wants the **manager** path and locates the
  singleton child itself via `settings.singletonName` ("singleton"). The double suffix made the
  proxy search `/user/<mgr>/singleton/singleton`, so it never routed a single message. No test
  caught it because the message-passing test was disabled and the other proxy tests only assert
  `(some? proxy)`. Fixed `proxy` to pass the manager path directly. Full suite green (310 tests
  / 561 assertions / 0 failures / 0 errors).

### H5 · Core DX polish — `DONE`
**Deps:** B1, B2, B3.
Add `context` accessor (referenced by `sharding/passivate` `sharding.clj:313` but undefined).
Add `stop`/`poison-pill`/`graceful-stop`, `actor-selection` + `identify`, `shutdown-system`
(terminate + await), an `actor-system` arity taking a `Config`. Unify timeout conventions
(`*timeout*` 30000 vs. hard-coded 5000 in `sharding/ask`); normalize nil-vs-throw across
`<!`/`await-completion`/`await-response`. Document the stream `smap`/`sfilter`-vs-shadowing
naming convention.
- **Done (2026-07-14):** added to `pekko-clj.core` (all type-hinted; core.clj stays
  reflection-free): `context` (current `ActorContext`), `stop` (via context), `poison-pill`,
  `graceful-stop` (→ `CompletableFuture`), `actor-selection` + `identify` (`resolveOne` →
  `CompletableFuture`), `shutdown-system` (terminate + block on `getWhenTerminated`), and an
  `actor-system` 3-arity taking a `com.typesafe.config.Config`. Unified the sharding timeouts —
  `ask`/`ask-entity`/`cluster-sharding-stats` now default to `core/*timeout*` instead of a
  hard-coded 5000. Normalized `stream/await-completion` and `http/await-response` to the `<!`
  convention (rethrow the **unwrapped** failure; **nil** on the block timeout) and gave each a
  default-timeout arity. Documented the `smap`/`sfilter` (renamed) vs shadowing (`take`/`drop`/…)
  naming convention in the stream ns docstring.
- **Bug surfaced + fixed:** `CljActor` read `postStop`/`postRestart`/`preRestart` from props but
  only overrode `preStart()`, so the `defactor` `on-stop` clause (and `:post-stop`) never fired.
  Added a `postStop()` override; `on-stop` now works (proved by the new stop/poison-pill tests).
- **Tests (`core_test.clj`):** `context`, `stop` (stops a child, `on-stop` fires), `poison-pill`,
  `graceful-stop`, `actor-selection`+`identify` round-trip, and `actor-system` Config arity +
  `shutdown-system`. Full suite green (319 tests / 573 assertions / 0 failures / 0 errors).
  **Unblocks N9.**

### H6 · Macro hygiene & docs — `DONE`
**Deps:** B1, B4.
Document/guard the reserved anaphors `state` (`defactor`) and `this`/`state`
(`defactor-persistent`) against user shadowing. Ensure `defactor` attaches a real docstring
(not `:doc ""`, `core.clj:161`).
- **Done (2026-07-14):** `defactor` now attaches `:doc` to the generated var **only** when a
  docstring is supplied (was `:doc ""`), matching `defactor-persistent`. Added reserved-anaphor
  guards that throw at macro-expansion: `defactor` rejects an `init`/`on-error` binding named
  `state`; `defactor-persistent` rejects a command pattern that binds `this`/`state`. Both macro
  docstrings now call out the reserved anaphors (and the `defactor` docstring's `on-stop` form
  was corrected to `(on-stop ...)` — no arg vector).
- **Tests:** updated the previously-`:doc ""` assertion to expect `nil`; added guard tests for
  both macros (unwrapping `macroexpand-1`'s `CompilerException` to check the `ExceptionInfo`).
- **Milestone H (Hardening) is COMPLETE** — H1–H6 all `DONE`.

---

## Milestone N — New modules (comprehensive parity)

New namespaces under `src/main/clj/pekko_clj/`. N1/N2 are highest leverage (gate CQRS).

### N1 · Streams depth — `TODO`
**Deps:** B7, F1. (Largest — may split N1a/N1b.)
Materialized values (`toMat`/`viaMat` + `Keep.left/right/both`; return `SourceQueue`/
`SinkQueue` + completion futures as a Clojure map, e.g. `{:done … :queue …}`). KillSwitch
(`KillSwitches/single`+`shared`). Supervision (`Supervision.Decider` via `withAttributes`;
`RestartSource/Flow/Sink.withBackoff`; `RetryFlow.withBackoff`). Actor interop
(`Flow.ask`/`askWithStatus`; finalize `Source/Sink.actorRef*` non-deprecated overloads).
Reuse the 90+ ops in `stream.clj` and `materializer` (`stream.clj:38`).

### N2 · Persistence query + event tagging + snapshot retention — `TODO`
**Deps:** B4, N1.
`withTagger`/`tagsFor` on `defactor-persistent`; snapshot retention (`snapshotEvery(n,keepN)`,
`deleteEventsOnSnapshot`) atop the existing helpers (`persistence.clj:227-256`). New
`pekko-clj.persistence.query`: `readJournalFor`, `eventsByTag`/`currentEventsByTag`,
`eventsByPersistenceId`, `persistenceIds`; map `EventEnvelope` + `Offset` to Clojure data
(returns a `Source`, composes with N1). LevelDB backs `eventsByTag` but **not**
`eventsBySlice` — document it.

### N3 · Distributed Pub-Sub / Topic — `TODO`
**Deps:** B5. (High value, small surface.)
New `pekko-clj.cluster.pubsub` over `DistributedPubSub(system).mediator`
(`pekko-cluster-tools` already on classpath): `subscribe`/`unsubscribe`/`publish`/`send`/
`send-to-all`; reuse `defactor` for subscribers.

### N4 · Clojure-data serializer (Transit) + bindings helper — `TODO`
**Deps:** B5.
`com.cognitect/transit-clj` is declared but **unused** — the intended feature. Implement a
`SerializerWithStringManifest` (Java class under `src/main/java/pekko_clj/actor/` or
`gen-class`) backed by Transit + a config helper for `serializers`/`serialization-bindings`/
`serialization-identifiers`. Wire into `create-system` (B5) and flip `allow-java-serialization`
off once bound.

### N5 · Sharding: passivation, remember-entities, daemon-process — `TODO`
**Deps:** B8.
Idle passivation + active-limit strategies (LRU/SLRU/LFU/MRU via `passivation.*`) + manual
stop-message hook (`Entity.withStopMessage`); confirm `passivate` (`sharding.clj:303`) drives
a real flow. Surface `remember-entities` (`ddata`/`eventsourced` store) and
`ShardedDaemonProcess.init`. Update `docs/specs/sharding-parity-spec.md`.

### N6 · Split-Brain-Resolver + CoordinatedShutdown helpers — `TODO`
**Deps:** B5.
SBR config helper (`keep-majority`/`static-quorum`/`keep-oldest`/`down-all`/`lease-majority`,
`stable-after`, `down-all-when-unstable`) layered on `create-system`. `CoordinatedShutdown`
wrapper (`addTask`, `run`, `addJvmShutdownHook`) extending `prepare-for-shutdown`
(`cluster.clj:358`).

### N7 · HTTP routing-DSL completion + marshalling + websockets — `TODO`
**Deps:** B9.
Finish the partial routing DSL (`http/routing.clj`): real param/header/body extraction
(`with-request-body` is stubbed), `RejectionHandler`/`ExceptionHandler`, a thin `entity`↔
EDN/JSON marshalling layer over Cheshire/Jsonista + existing `response`/`entity` builders.
Websockets (`handleWebSocketMessages` over a streams `Flow`, reuses N1).

### N8 · Distributed Data (selective CRDTs) — `TODO`
**Deps:** B5, N3. (Lower priority within N.)
New `pekko-clj.cluster.ddata` over `DistributedData(system).replicator`: common CRDTs
(`ORSet`, `LWWMap`, `PNCounter`) + four commands (`Update`/`Get`/`Subscribe`/`Delete`) with
consistency levels. Keep it opinionated — not the whole CRDT zoo.

### N9 · Classic actor extras — `TODO`
**Deps:** B1, B3, H5.
`ReceiveTimeout` (`context.setReceiveTimeout`), `EventStream`/dead-letter subscription helpers
(generalize the cluster-event subscriber `cluster.clj:302`), `UnboundedPriorityMailbox`
helper, `CircuitBreaker` wrapper.

---

## Backlog (documented, not scheduled)

- **Typed actors** (`Behaviors`) — deferred by decision; add narrow typed shims only if a
  future module is typed-only.
- **Durable state** (`DurableStateBehavior`) — needs JDBC/R2DBC; LevelDB can't back it.
- **Full GraphDSL / custom `GraphStage`**, **cluster-metrics**, **replicated event sourcing**,
  **Alpakka/Kafka connectors** (already return plain `Source`/`Sink`), **`EventsBySlice`**
  (plugin-dependent), **external/custom shard allocation**, **multi-DC**
  (`cluster-parity-spec.md` §6), **routing `prefer-local-routees`**
  (`routing-parity-spec.md`), **singleton lease integration** (`singleton-parity-spec.md`).

---

## Dependency order (summary DAG)

```
F0 ─┬─► F1 ─┬─► H1 ─► H2
    │       ├─► H3
    │       └─► N1 ─► N2
    ├─► B1 ─┬─► B10        (B10 also needs B3)
    ├─► B2  ├─► H5 ─► N9   (H5 also needs B2,B3)
    ├─► B3 ─┘
    ├─► B4 ─► (N2, H6)
    ├─► B5 ─┬─► N3 ─► N8
    │       ├─► N4
    │       └─► N6
    ├─► B6 ─► H4          (H4 also needs H1,H2)
    ├─► B7 ─► N1
    ├─► B8 ─► N5
    └─► B9 ─► N7
```

---

## Existing parity checklists (roll-up)

Detailed per-module specs live in `docs/specs/`. Summary of what remains `❌` there (folded
into the stories above):

| Module | Parity | Remaining gaps | Story |
|--------|--------|----------------|-------|
| Cluster (`cluster-parity-spec.md`) | ~95% | Multi-DC (§6) | backlog; SBR/coord-shutdown → N6 |
| Routing (`routing-parity-spec.md`) | ~95% | `prefer-local-routees` | backlog |
| Sharding (`sharding-parity-spec.md`) | ~85% | advanced passivation, external/custom allocation, remember-entities-store | N5 (+ backlog for external/custom) |
| Singleton (`singleton-parity-spec.md`) | ~95% | lease integration | backlog |
| Core / Persistence / Stream / HTTP / Serialization | — (no spec yet) | see B/N stories | this epic |

Modules without a `docs/specs/` file (core, persistence, stream, http, serialization) are
tracked entirely by the stories above; add parity specs as those stories land if useful.
