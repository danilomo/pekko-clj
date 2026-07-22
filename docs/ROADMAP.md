# pekko-clj Roadmap & Epic Tracker

> **This epic is COMPLETE.** The follow-up epic — bugs and gaps found in the
> 2026-07-22 full-codebase audit — lives in [`ROADMAP-2.md`](ROADMAP-2.md); pick up
> new work there.

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
| N1 | Streams depth (mat values, KillSwitch, supervision, actor interop) | New | DONE | B7, F1 | medium |
| N2 | Persistence query + event tagging + snapshot retention | New | DONE | B4, N1 | medium |
| N3 | Distributed Pub-Sub / Topic | New | DONE | B5 | low |
| N4 | Clojure-data serializer (Transit) + bindings helper | New | DONE | B5 | medium |
| N5 | Sharding: passivation, remember-entities, daemon-process | New | DONE | B8 | medium |
| N6 | Split-Brain-Resolver + CoordinatedShutdown helpers | New | DONE | B5 | low |
| N7 | HTTP routing-DSL completion + marshalling + websockets | New | DONE | B9 | medium |
| N8 | Distributed Data (selective CRDTs) | New | DONE | B5, N3 | medium |
| N9 | Classic actor extras (ReceiveTimeout, EventStream, …) | New | DONE | B1, B3, H5 | low |

**Status (2026-07-21): the epic is COMPLETE** — every story (B1–B10, H1–H6, N1–N9, F0–F1) is
`DONE`, including the optional N8/N9. `lein test`: 466 tests / 981 assertions / 0 failures /
0 errors. Remaining work lives in the Backlog section below.

**Post-epic review pass (2026-07-22)** — a review hackathon over the finished epic found and
fixed a few residual issues (`lein test` now 469 tests / 988 assertions / 0 failures / 0 errors):
- `defactor`'s `(on-restart …)` clause was parsed but never emitted, and `CljActor` read
  `:pre-restart`/`:post-restart` props it never used → the clause was a silent no-op. Wired up:
  `CljActor` overrides `preRestart`(the javadsl `Optional` overload; the `scala.Option` one is
  deprecated in 1.6)/`postRestart`; the macro emits `:post-restart`; `state`-shadow guard added.
- Persistence event-shape ambiguity removed: the old "vector-of-vectors ⇒ multiple events"
  heuristic could split a single compound event. New `persist-all` + `pekko_clj.actor.PersistAll`
  marker; `persist` now always means one event. Regression test added.
- `cluster/members-by-age` now sorts with Pekko's own age ordering (`Member.isOlderThan`) rather
  than `upNumber` alone; stash docstrings corrected (re-tell appends, not prepends); daemon
  fire-and-forget (no sender) documented.

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

### N1 · Streams depth — `DONE`
**Deps:** B7, F1. (Largest — may split N1a/N1b.)
Materialized values (`toMat`/`viaMat` + `Keep.left/right/both`; return `SourceQueue`/
`SinkQueue` + completion futures as a Clojure map, e.g. `{:done … :queue …}`). KillSwitch
(`KillSwitches/single`+`shared`). Supervision (`Supervision.Decider` via `withAttributes`;
`RestartSource/Flow/Sink.withBackoff`; `RetryFlow.withBackoff`). Actor interop
(`Flow.ask`/`askWithStatus`; finalize `Source/Sink.actorRef*` non-deprecated overloads).
Reuse the 90+ ops in `stream.clj` and `materializer` (`stream.clj:38`).
- **Done (2026-07-15):** all four areas landed in `pekko-clj.stream` (no split needed).
  **Materialized values:** `keep-mat` (:left/:right/:both/:none — named to avoid shadowing
  `clojure.core/keep`), `via-mat`, `to-mat` (→ `RunnableGraph`), `run-graph`, `run-mat`; a
  `Keep/both` `japi.Pair` is unwrapped to a Clojure vector `[left right]` rather than leaking
  the Java type. Map-returning conveniences per the story: `run-source-queue` →
  `{:queue SourceQueueWithComplete :done …}` and `run-sink-queue` → `{:queue SinkQueueWithCancel}`.
  **KillSwitch:** `kill-switch-single`, `via-kill-switch`, `run-with-kill-switch` →
  `{:kill-switch :done}`, `shared-kill-switch` + `shared-kill-switch-flow`, and polymorphic
  `shutdown`/`abort` over the `KillSwitch` interface.
  **Supervision:** `supervision-strategy` (decider fn Throwable → `:stop`/`:resume`/`:restart`,
  nil ⇒ `:stop` per Pekko's default) via `ActorAttributes/withSupervisionStrategy`,
  `with-attributes`, `with-supervision`; `restart-settings` (opts map → `RestartSettings`,
  incl. `:max-restarts`/`:max-restarts-within`/`:restart-on`), `restart-source`,
  `restart-source-on-failures`, `restart-flow`, `restart-flow-on-failures`, `restart-sink`,
  `retry-flow`. Durations accept a `java.time.Duration` or milliseconds throughout.
  **Actor interop:** `flow`/`flow-of`/`flow-from-fn` (the ns had **no** Flow constructors before,
  despite importing `Flow` — `via`/`restart-flow`/`retry-flow` all need one; the existing ops
  work on a Flow unchanged), `ask` (+ parallelism arity), `ask-with-status`,
  `source-actor-ref` 4-arity opts map (`:complete-with`/`:fail-with` → the matcher-based
  `Source/actorRef` overload), `sink-actor-ref-with-backpressure`.
- **`Flow.askWithStatus` does NOT exist in Pekko 1.6.0** (verified: zero hits for `askWithStatus`
  across the whole unpacked `pekko-stream_3-1.6.0.jar`, javadsl **and** scaladsl — it is an
  Akka-only API; `org.apache.pekko.pattern.StatusReply` itself does exist in pekko-actor). So
  `ask-with-status` asks for a `StatusReply` and unwraps it (success → value, error → stream
  failure) — same semantics, one extra `smap` stage. Documented in its docstring.
- **Deprecation check (completes B7's deferral):** re-verified via `javap -v` that
  `Source/actorRef(int, OverflowStrategy)` and `Sink/actorRef(ActorRef, Object)` carry **no**
  `Deprecated` attribute in 1.6.0 — they stay as the 3-arity/simple forms. The matcher-based
  `Source/actorRef` and `Sink/actorRefWithBackpressure` are now exposed for callers who want
  explicit completion/failure control or ack-based backpressure.
- **Cleanup:** deduped the three copy-pasted overflow-strategy `case` blocks (`buffer`,
  `source-actor-ref`, `source-queue` — each with a different default and only one supporting
  `:backpressure`) into one private `->overflow-strategy [strategy default]`, preserving each
  call site's default. Added a private `->duration` (mirrors `circuit_breaker.clj`). Fixed the
  ns docstring's example, which passed `sys` to `run-foreach` (it takes a **materializer**), and
  documented the new surface there.
- **Tests (`stream_test.clj`, +39 → 106 in the ns):** mat-value keeps (`:both` → vector, `:left`
  reaching a kill switch through `via-kill-switch`), queue map forms driven end-to-end
  (offer/complete, pull → empty `Optional`), kill-switch shutdown (asserting the infinite stream
  does *not* complete first) / abort / shared-switch stopping two streams, supervision
  `:resume`-drops-vs-`:stop`-fails and a `scan`-based test that genuinely discriminates
  `:restart` (`[0 1 3 0 3]`, state reset) from `:resume` (`[0 1 3 6]`, state kept) — both verified
  against real output, unknown-directive throw, `restart-settings` field-by-field, restart-on-
  completion vs on-failure-only, give-up-after-`:max-restarts` (exactly 3 attempts),
  `:restart-on` false ⇒ no restart, restart flow/sink, `retry-flow` retry-until-accept and
  give-up, Flow constructors + composition with existing ops, `ask` (order, parallelism,
  Duration timeout), `ask-with-status` success/error/parallelism, `source-actor-ref`
  `:complete-with`/`:fail-with` + a regression that the 3-arity `Status$Success` path is
  unchanged, and the ack-based backpressure sink. Full suite green before (357 tests / 663
  assertions) and after (**396 tests / 724 assertions / 0 failures / 0 errors**); the stream ns
  ran 3× clean to check the backoff-timed tests for flakiness.
- **Note:** no `docs/specs/*` checklist covers streams (no stream parity spec exists — see the
  roll-up table), so nothing to tick there. `stream.clj` keeps its deliberate H3 reflection
  posture: no `^Source` hints on the polymorphic ops (they must accept `Source`/`Flow`/`SubSource`,
  which share no supertype with `.map`), reflection being construction-time only. **Unblocks N2.**

### N2 · Persistence query + event tagging + snapshot retention — `DONE`
**Deps:** B4, N1.
`withTagger`/`tagsFor` on `defactor-persistent`; snapshot retention (`snapshotEvery(n,keepN)`,
`deleteEventsOnSnapshot`) atop the existing helpers (`persistence.clj:227-256`). New
`pekko-clj.persistence.query`: `readJournalFor`, `eventsByTag`/`currentEventsByTag`,
`eventsByPersistenceId`, `persistenceIds`; map `EventEnvelope` + `Offset` to Clojure data
(returns a `Source`, composes with N1). LevelDB backs `eventsByTag` but **not**
`eventsBySlice` — document it.
- **Done (2026-07-16):** added `pekko-persistence-query_3` 1.6.0 (`project.clj` + README module
  table). **Tagging:** a `(tagger [event] ...)` clause on `defactor-persistent` returns tags
  (any collection of strings/keywords) for an event; `CljPersistentActor.withTags` wraps the
  event in a `journal.Tagged` envelope at persist time (only when the tagger returns tags), and
  `applyEvent` unwraps `Tagged` so the event handler and recovery only ever see the raw event.
  **Snapshot retention:** `(snapshot-every n keep)` deletes snapshots older than the `keep` most
  recent (`applyRetention` on `SaveSnapshotSuccess`: `deleteUpTo = seqNr - keep*n`); optional
  `(delete-events-on-snapshot)` also `deleteMessages(deleteUpTo)` the subsumed events (macro
  guard: requires the 3-arg `snapshot-every`). `createReceive` now matches the journal/snapshot
  protocol replies (`SaveSnapshot*`/`Delete*`) so retention runs and infra messages never reach
  the command handler; failures are logged, not routed. **Query ns** (`pekko-clj.persistence.query`):
  `read-journal` (`getReadJournalFor`, default LevelDB), live + `current-` variants of
  `events-by-tag`/`events-by-persistence-id`/`persistence-ids`, `envelope->map`
  (`{:offset :persistence-id :sequence-nr :event :timestamp}`), `offset->clj` + `no-offset`/
  `sequence-offset` (NoOffset is a Scala case object → matched by value), each returning a
  pekko-clj.stream `Source` that composes with N1. Capability-missing journals throw a clear error.
- **Backend note (documented in the ns):** LevelDB backs `events-by-tag` but has no
  `eventsBySlice` provider (typed, slice-based) — use tags for cross-persistence-id fan-in.
- **Tests:** `persistence_test.clj` — retention recovery (keep-1 + delete-events both reconstruct
  state) and the delete-events-without-retention macro guard. New `persistence/query_test.clj`
  (12 tests) — `offset->clj`/`envelope->map` shape, `current-events-by-persistence-id`,
  `current-events-by-tag` fan-in across two actors + offset-resume, `current-persistence-ids`,
  a live `events-by-tag` picking up a new event, capability-error, and an observable
  delete-events-on-snapshot check (only seq 5 & 6 survive after 6 events). Tag queries filter by
  the test's own persistence-ids since a tag fans in across the shared journal. Full suite green
  before (396 tests / 724 assertions) and after (**408 tests / 752 assertions / 0 failures / 0
  errors**). Feeds CQRS read-models; no `docs/specs/*` covers persistence (tracked by this story).

### N3 · Distributed Pub-Sub / Topic — `DONE`
**Deps:** B5. (High value, small surface.)
New `pekko-clj.cluster.pubsub` over `DistributedPubSub(system).mediator`
(`pekko-cluster-tools` already on classpath): `subscribe`/`unsubscribe`/`publish`/`send`/
`send-to-all`; reuse `defactor` for subscribers.
- **Done (2026-07-14):** new `pekko-clj.cluster.pubsub` wraps the `DistributedPubSubMediator`.
  `mediator` returns the extension's mediator ref; topic pub-sub via `subscribe`/`unsubscribe`/
  `publish` (with grouped subscriptions + `:one-per-group` consumer-group publish); path-based
  point-to-point via `put`/`remove`/`send` (`local-affinity`) and broadcast via `send-to-all`
  (`all-but-self`). `subscribe` is polymorphic on its final arg: an `ActorRef` is subscribed
  directly (receives raw messages + a `SubscribeAck`), a **function** spawns an internal
  `defactor` `topic-subscriber` that self-subscribes and calls the handler per message (acks
  swallowed) — the "reuse `defactor` for subscribers" ask. `subscribe-ack?`/`unsubscribe-ack?`
  predicates let users match acks on their own actors. Every op takes an ActorSystem **or** a
  cached mediator `ActorRef` as its first arg (usable inside actor handlers). `send`/`remove`
  excluded from `clojure.core`.
- **Tests (`cluster/pubsub_test.clj`):** 8 tests on a single-node cluster — mediator identity,
  topic publish→subscribe delivery, fan-out to multiple subscribers, direct-`ActorRef`
  subscription, unsubscribe stops delivery, grouped + one-per-group publish, and `put`+`send` /
  `send-to-all` point-to-point routing (async registration handled with `eventually`-retry).
  Full suite green (327 tests / 595 assertions / 0 failures / 0 errors). Unblocks **N8**
  (Distributed Data also needs N3). No `docs/specs/*` checklist covers pub-sub (module has no
  spec — tracked entirely by this story).

### N4 · Clojure-data serializer (Transit) + bindings helper — `DONE`
**Deps:** B5.
`com.cognitect/transit-clj` is declared but **unused** — the intended feature. Implement a
`SerializerWithStringManifest` (Java class under `src/main/java/pekko_clj/actor/` or
`gen-class`) backed by Transit + a config helper for `serializers`/`serialization-bindings`/
`serialization-identifiers`. Wire into `create-system` (B5) and flip `allow-java-serialization`
off once bound.
- **Done (2026-07-21):** Java `CljTransitSerializer extends SerializerWithStringManifest`
  (`(ExtendedActorSystem)` constructor, so Pekko can instantiate it) is a thin bridge: it reads
  its identifier from `pekko.actor.serialization-identifiers."pekko_clj.actor.CljTransitSerializer"`
  (default 9001) and the Transit format from `pekko-clj.serialization.transit.format` (default
  json), then calls `write-bytes`/`read-bytes` in the new **`pekko-clj.serialization`** ns. The
  manifest is the constant `"clj"` — Transit is self-describing, so no class name is needed.
  `gen-class` was rejected: it needs AOT, and the suite runs without it.
- **`pekko-clj.serialization`:** `write-bytes`/`read-bytes` (arities `[obj]`, `[obj system]`,
  `[obj system format]`; `:json`/`:json-verbose`/`:msgpack`, validated) usable standalone, e.g.
  for writing Clojure data to an external store. **ActorRefs embedded anywhere in a message
  round-trip**: a write handler renders them with `path.toSerializationFormatWithAddress(provider
  .getDefaultAddress())` under the `"pekko/ref"` tag and a read handler resolves them via
  `provider.resolveActorRef` (Transit's handler lookup walks superclasses, so registering the
  abstract `ActorRef` covers `LocalActorRef`/`RepointableActorRef`). Handler maps are built once
  per system and cached in a **weak**-keyed map so terminated systems stay collectable — which
  only works because the handlers reach the system through a `WeakReference`; capturing it
  directly made the cached value pin its own key (fixed in the epic's review pass).
  Config helpers: `serialization-config` (generic `serializers`/`serialization-bindings`/
  `serialization-identifiers`/`allow-java-serialization` builder) and `transit-config` on top of
  it (`:alias`/`:identifier`/`:format`/`:bindings`/`:extra-bindings`/`:allow-java-serialization`),
  binding `IPersistentCollection` (maps/vectors/lists/sets/seqs), `Keyword`, `Symbol`, `Ratio`,
  `BigInt` by default and **turning Java serialization off** (opt back in with
  `:allow-java-serialization true`).
- **Wired into `create-system`** as `:transit-serialization` (`true` or a `transit-config` opts
  map). Precedence is now `:extra-config` > `:split-brain-resolver` > `:transit-serialization` >
  generated defaults, so it overrides the generated `allow-java-serialization = on`.
- **Tests (`serialization_test.clj`, 15):** data round trip incl. keyword/symbol/set/ratio/bigint
  identity, all three formats + msgpack≠json bytes, unknown-format throws, ActorRef round trip,
  `serialization-config`/`transit-config` config shapes (defaults, options, `:bindings` replacing
  the defaults), the real Pekko path (`findSerializerFor` picks the serializer for Clojure data
  but not for Strings, identifier + manifest, `Serialization.deserialize` round trip in json and
  msgpack, a deserialized ref still delivers messages), `create-system` wiring + `:extra-config`
  precedence, and an end-to-end actor exchange under `pekko.actor.serialize-messages = on` that
  asserts the delivered payload is `=` but **not `identical?`** to the sent one — proof it really
  went through `toBinary`/`fromBinary`. (A plain `serialize-messages` check is not enough on its
  own: Pekko *skips* the verification round trip when the chosen serializer is
  `DisabledJavaSerializer`, verified by disassembling `Dispatch.serializeAndDeserializePayload`.)
  Full suite green before (408 tests / 752 assertions) and after (**423 tests / 811 assertions /
  0 failures / 0 errors**); `lein check` reports 0 reflection warnings for the new namespace.
- **Note:** no `docs/specs/*` file covers serialization (grep confirms), so nothing to tick;
  documented in `README.md` (Features bullet + a `## Serialization` section). Records still need
  a per-type Transit handler — documented in the ns docstring and README.
- **Milestone N remaining:** N5 (sharding passivation), N7 (HTTP DSL), N8 (ddata, optional).

### N5 · Sharding: passivation, remember-entities, daemon-process — `DONE`
**Deps:** B8.
Idle passivation + active-limit strategies (LRU/SLRU/LFU/MRU via `passivation.*`) + manual
stop-message hook (`Entity.withStopMessage`); confirm `passivate` (`sharding.clj:303`) drives
a real flow. Surface `remember-entities` (`ddata`/`eventsourced` store) and
`ShardedDaemonProcess.init`. Update `docs/specs/sharding-parity-spec.md`.
- **Bug found + fixed (the F1 deprecation follow-up):** `start` called
  `ClusterShardingSettings.withPassivateIdleEntityAfter`, which **does not exist in Pekko
  1.6** (`javap` confirms; the call was reflective, so it compiled) — every `start` with
  `:passivate-after` threw at runtime. No test covered it. It now routes through the current
  passivation-strategy API.
- **Done (2026-07-21):** `passivation-settings` builds a
  `ClusterShardingSettings$PassivationStrategySettings` **programmatically** (1.6 exposes a
  builder, so no HOCON generation as the spec assumed): `:strategy` `:idle` /
  `:least-recently-used` / `:most-recently-used` / `:least-frequently-used` / `:none`, with
  `:idle-timeout`/`:idle-interval` (ms or `java.time.Duration`), `:active-entity-limit`,
  `:segmented` (SLRU: level count or proportions vector) and `:dynamic-aging` (LFU). Idle and
  limit-based passivation compose in one map. New public `sharding-settings` builds the whole
  `ClusterShardingSettings` from `start`'s opts, adding `:remember-entities-store`
  (`:ddata`/`:eventsourced` — no `with…` setter exists, so it overrides the key in the system's
  own `pekko.cluster.sharding` config section and rebuilds the settings from it),
  `:journal-plugin-id`/`:snapshot-plugin-id`, and keeping `:role`/`:remember-entities`.
  `start` gained `:stop-message` (hand-off stop for rebalance), which needs the
  `start(…, allocationStrategy, handOffStopMessage)` overload — it passes
  `defaultShardAllocationStrategy`. `passivate` gained a 1-arity using the current context
  and its docstring now says how to actually stop (`(core/stop (core/self))`).
- **Daemon process:** new `pekko-clj.cluster.daemon` (`start`, `settings`).
  `ShardedDaemonProcess` is **typed-only**, so this is the epic's sanctioned narrow typed
  shim: Java `CljDaemonProcess.wrap(Props)` returns a typed `Behavior` that spawns the classic
  `defactor` actor as its child, forwards every message (incl. the stop message) to it, and
  stops itself when the child terminates so Pekko restarts the instance. Each worker's `init`
  receives its index (0…n-1). Adds `pekko-cluster-sharding-typed_3` 1.6.0 (pulls
  actor-typed/cluster-typed/slf4j); nothing user-facing becomes typed.
- **Test-config fixes:** `cluster-test.conf` used the deprecated
  `passivate-idle-entity-after = off` (the WARN F1 recorded) → now `passivation.strategy =
  "none"`; and `distributed-data.durable.keys = []`, because remember-entities via ddata
  writes through the **durable LMDB** replicator by default, which crashed the JVM
  (`IllegalAccessError`, needs `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`).
- **Tests:** `sharding_test.clj` +7 — strategy-settings shapes for idle (timeout/interval),
  LRU + SLRU (levels and proportions), MRU, LFU dynamic-aging, disabled, unknown-strategy and
  bad-`:segmented` throws; `sharding-settings` from opts (incl. the `:passivate-after`
  regression, store mode, plugins, unknown store throw, pre-built settings object); and three
  end-to-end runs — idle passivation stops an entity and the next message recreates it with
  fresh state, manual `passivate` → stop-message → recreate, and a region started with
  `:stop-message` + `:remember-entities` still routing. New `cluster/daemon_test.clj` (3):
  settings, all `n` workers started with their index, stop-message variant. Full suite green
  before (423 tests / 811 assertions) and after (**433 tests / 859 assertions / 0 failures /
  0 errors**); no reflection warnings in the new namespace.
- **Docs:** ticked `sharding-parity-spec.md` (advanced passivation, remember-entities store,
  hand-off stop message, daemon process — all now ✅; external/custom allocation stay ❌),
  corrected its stale pre-B8 message-flow diagram, and updated the README sharding section
  (which still showed the removed `[:entity-message id msg]` pattern) + module table.
- **Milestone N remaining:** N7 (HTTP DSL). N8 (Distributed Data) is optional for the DoD.

### N6 · Split-Brain-Resolver + CoordinatedShutdown helpers — `DONE`
**Deps:** B5.
SBR config helper (`keep-majority`/`static-quorum`/`keep-oldest`/`down-all`/`lease-majority`,
`stable-after`, `down-all-when-unstable`) layered on `create-system`. `CoordinatedShutdown`
wrapper (`addTask`, `run`, `addJvmShutdownHook`) extending `prepare-for-shutdown`
(`cluster.clj:358`).
- **Done (2026-07-14):** both features added to `pekko-clj.cluster`.
  **SBR:** `split-brain-resolver-config` builds a `Config` for any of the five strategies
  (`:keep-majority`/`:static-quorum`/`:keep-oldest`/`:down-all`/`:lease-majority`) with
  `:stable-after` (ms number or HOCON string), `:down-all-when-unstable` (true→on/false→off/
  duration), `:role`, `:quorum-size`, `:down-if-alone`, and the `:lease-*` knobs — sets the
  `downing-provider-class` too, so it is self-contained. Wired into `create-system` via a new
  `:split-brain-resolver` map key (precedence: `:extra-config` > `:split-brain-resolver` >
  generated defaults > reference.conf). **CoordinatedShutdown:** `coordinated-shutdown` (the
  extension, works on any system), `shutdown-phases` / `shutdown-reasons` keyword→value maps,
  `add-shutdown-task` (a 0-arg fn; a returned `CompletionStage` is awaited, else completes with
  `Done`), `add-cancellable-shutdown-task` (returns a `Cancellable`), `add-jvm-shutdown-hook`,
  and `run-coordinated-shutdown` (→ `CompletableFuture<Done>`; reason keyword/Reason/nil).
- **Tests (`coordination_test.clj`, 16):** SBR config rendering for every strategy + duration/
  on-off/role/quorum handling + unknown-strategy throw; `create-system` merges SBR into the live
  system config and `:extra-config` wins over it; shutdown phase/reason maps; a task runs on
  shutdown; a `CompletionStage`-returning task is awaited; a cancelled task does not run; bad
  phase/reason throw; hook returns nil. Full suite green (343 tests / 631 assertions / 0
  failures / 0 errors). Ticked SBR + coordinated-shutdown in `cluster-parity-spec.md`.

### N7 · HTTP routing-DSL completion + marshalling + websockets — `DONE`
**Deps:** B9.
Finish the partial routing DSL (`http/routing.clj`): real param/header/body extraction
(`with-request-body` is stubbed), `RejectionHandler`/`ExceptionHandler`, a thin `entity`↔
EDN/JSON marshalling layer over Cheshire/Jsonista + existing `response`/`entity` builders.
Websockets (`handleWebSocketMessages` over a streams `Flow`, reuses N1).
- **Done (2026-07-21):** added `cheshire` 5.13.0 (`project.clj`, README module table,
  `docs/specs/README.md`). New **`pekko-clj.http.marshalling`**: `->json`/`json->` (Cheshire,
  keys keywordized by default, strings passed through so an encoded body is never
  double-encoded), `->edn`/`edn->` (**`clojure.edn`** — no eval, tested against a `#=` payload),
  `unmarshal` (dispatches on a ContentType/string/keyword; unknown types return the raw body)
  and an `application/edn` `ContentType`.
- **Bug found + fixed:** `response/json` rendered data with `pr-str`, i.e. it served **EDN under
  an `application/json` content type** (`{:a 1}`). It now encodes real JSON; `response/edn` and
  the `:edn` content type are new.
- **Body extraction:** `with-request-body` was a stub — it built a `CompletionStage<Route>` and
  passed it to `completeWithFuture`, so it only "worked" when the handler happened to return a
  response. Rewritten on `extractStrictEntity`: the body is buffered and the handler gets a plain
  **string** (timeout arity, default 5s). On top of it: `with-json-body`, `with-edn-body` and
  `with-body` (parses by request Content-Type), each completing **400** on a malformed body
  instead of throwing. Out: `complete-json` / `complete-edn`.
- **Extraction:** `params` (all query params → keyword map), `form-field`, `form-field-opt`,
  `form-fields` (form body → keyword map) — joining the existing `param`/`param-opt`/
  `header-value`/`header-value-opt`.
- **Failure handling:** `rejection-handler` (`:not-found` / `:all` / `:handle` class→fn map) and
  `exception-handler` (class→fn map, or one fn for any Throwable — note it builds through
  `japi.pf.FI$Apply`, not `java.util.function.Function`), applied with `handle-rejections` /
  `handle-exceptions`.
- **Websockets:** `websocket` (`handleWebSocketMessages`, plus a subprotocol arity),
  `text-message`, `message->text` (nil for binary/streamed) and `text-flow`, which builds the
  `Flow<Message,Message>` out of N1's stream ops (`flow-of`/`smap`/`sfilter`).
- **Tests:** new `http/marshalling_test.clj` (9) — JSON/EDN round trips, the pr-str regression,
  malformed-input throw, safe EDN read, content-type dispatch, and the `json`/`edn` entity
  builders. `http/integration_test.clj` +9 driving a **real server**: JSON round trip (handler
  sees keywordized data, response is JSON), malformed JSON → 400, EDN round trip (a set
  survives), `with-request-body` string handling, query params, form fields, exception handler
  (per-class → 400 vs fallback → 500), rejection handler (custom 404 + a 405 MethodRejection),
  and a **websocket echo** over a real client (`singleWebSocketRequest`, asserts the 101 upgrade
  and the uppercased reply). `http/routing_test.clj` +3 for the handler builders and ws helpers.
  Full suite green before (433 tests / 859 assertions) and after (**454 tests / 913 assertions /
  0 failures / 0 errors**).
- **Note:** no `docs/specs/*` file covers HTTP (the roll-up table records it as spec-less), so
  nothing to tick; documented instead in the `routing` ns docstring and a new README `## HTTP`
  section. `routing.clj` keeps its pre-existing reflective interop style (H3 scoped
  reflection-freedom to `core.clj`'s per-message path).
- **Milestone N remaining:** only N8 (Distributed Data), which the epic marks optional — so the
  epic's definition of done (B + H + N1–N7) is **met**.

### N8 · Distributed Data (selective CRDTs) — `DONE`
**Deps:** B5, N3. (Lower priority within N.)
New `pekko-clj.cluster.ddata` over `DistributedData(system).replicator`: common CRDTs
(`ORSet`, `LWWMap`, `PNCounter`) + four commands (`Update`/`Get`/`Subscribe`/`Delete`) with
consistency levels. Keep it opinionated — not the whole CRDT zoo.
- **Done (2026-07-21):** new `pekko-clj.cluster.ddata` (declares the
  `pekko-distributed-data_3` dep explicitly — it was already a pekko-cluster transitive).
  Extension access: `distributed-data`, `replicator` (ActorSystem **or** a cached replicator
  ref), `self-address`. Keys: `or-set-key` / `lww-map-key` / `pn-counter-key` + `key-id` and
  the `empty-*` constructors. Consistency: `write-consistency` / `read-consistency`
  (`:local` default, `:majority`, `:all` with a timeout; unknown level throws).
- **Commands** — `update!`, `get-data`, `delete!`, `subscribe`/`unsubscribe` — all return a
  **`CompletableFuture` of a Clojure map** rather than Pekko response classes: `:status` is
  `:success` / `:not-found` / `:timeout` / `:deleted` / `:failure` (a throwing modify fn
  surfaces as `ModifyFailure` → `{:status :failure :error … :cause …}`). Values come back as
  Clojure data via `crdt->clj` (ORSet → set, LWWMap → map, PNCounter → **long** when it fits,
  else bigint — so `(= 5 (value …))` works), with the raw CRDT kept under `:data`.
  `subscribe` mirrors N3's pub-sub shape: an `ActorRef` gets raw `Changed`/`Deleted` messages
  (convert with `change->map`), a function gets `{:key :value :data :deleted?}` from an
  internal `defactor` subscriber. Blocking `value` convenience for the common read.
- **Opinionated per-type ops** (they fill in the empty value and the node address, so users
  never touch `SelfUniqueAddress`): `add!` / `remove!` (ORSet), `put!` / `remove-key!`
  (LWWMap), `increment!` / `decrement!` (PNCounter). `update!` stays as the escape hatch —
  it takes `(fn [current] new-crdt)` and an `:initial`, wrapping it in `FnWrapper` for the
  Scala `Function1` the `Replicator.Update` constructor wants.
- **Tests (`cluster/ddata_test.clj`, 10 / 62 assertions):** keys and key-ids, every
  consistency level + unknown-level throws, `crdt->clj` conversions, replicator/self-address
  access and ref pass-through, ORSet add/remove with `:not-found` before first write, LWWMap
  put/overwrite/remove, PNCounter increment/decrement reading back as plain numbers, generic
  `update!` (incl. `:majority` on a single-node cluster and a throwing modify fn),
  subscribe → change notifications → unsubscribe stops them, and delete making a key
  permanently `:deleted` for both reads and writes. Full suite green before (454 tests /
  913 assertions) and after (**464 tests / 975 assertions / 0 failures / 0 errors**);
  `lein check` reports 0 reflection warnings for the new namespace.
- **Docs:** no `docs/specs/*` file covers ddata, so nothing to tick; documented in the ns
  docstring, a README `### Distributed Data (CRDTs)` subsection + module table row, and the
  `docs/specs/README.md` dependency block. Replicated values cross nodes, so the ns notes
  that elements need a serializer — N4's Transit one covers Clojure data.
- **Milestone N is COMPLETE** — N1–N9 all `DONE`, so every story in the epic is finished.

### N9 · Classic actor extras — `DONE`
**Deps:** B1, B3, H5.
`ReceiveTimeout` (`context.setReceiveTimeout`), `EventStream`/dead-letter subscription helpers
(generalize the cluster-event subscriber `cluster.clj:302`), `UnboundedPriorityMailbox`
helper, `CircuitBreaker` wrapper.
- **Done (2026-07-14):** all four delivered.
  **ReceiveTimeout** (in `core`): `set-receive-timeout` (java.time.Duration or ms; converts to the
  Scala `Duration` the API wants), `cancel-receive-timeout` (→ `Duration.Undefined`), the
  `receive-timeout` singleton value + `receive-timeout?` predicate. Also added `actor-props`
  (the raw Pekko `Props` for a `defactor`) and `spawn-props` (spawn from a decorated `Props`) to
  support mailbox/dispatcher setups. **EventStream** — new `pekko-clj.event-stream`:
  `event-stream`, polymorphic `subscribe` (ActorRef or handler-fn, class-keyed) /`unsubscribe`/
  `publish`, and `subscribe-dead-letters` / `subscribe-unhandled` conveniences with
  `dead-letter->map` / `unhandled->map` (generalizes the cluster-event subscriber). **Priority
  mailbox** — Java `CljPriorityMailbox` (a `(Settings, Config)`-instantiable
  `UnboundedStablePriorityMailbox` that resolves a Clojure priority fn named in config), plus
  `pekko-clj.mailbox` (`priority-mailbox-config` builds the mailbox `Config`; `with-mailbox`
  attaches it to `Props`). **CircuitBreaker** — new `pekko-clj.circuit-breaker`:
  `circuit-breaker`, sync `call` / async `call-async`, `succeed`/`fail`, `open?`/`closed?`,
  `on-open`/`on-close`/`on-half-open`.
- **Tests (4 new ns, 14 tests):** `receive_timeout_test` (fires when idle, accepts a Duration,
  cancel silences it), `event_stream_test` (subscribe/publish, unsubscribe stops delivery,
  dead-letters, unhandled-message + recipient), `mailbox_test` (config shape for var/symbol/
  string forms; a gated actor proves higher-priority messages dequeue first), `circuit_breaker_test`
  (success, opens after N failures → `CircuitBreakerOpenException`, async call, on-open listener,
  manual fail). `core.clj` stays reflection-free; new namespaces have no reflection warnings. Full
  suite green (357 tests / 663 assertions / 0 failures / 0 errors). No `docs/specs/*` covers these
  modules (core/event-stream/mailbox/circuit-breaker) — tracked entirely by this story.
- **Milestone N remaining (at time of N9):** N1, N2 (needs N1), N4, N5, N7, N8. N3/N6/N9 `DONE`.
  N1 has since landed, which unblocks N2.

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
| Cluster (`cluster-parity-spec.md`) | ~97% | Multi-DC (§6) | backlog (SBR/coord-shutdown done in N6) |
| Routing (`routing-parity-spec.md`) | ~95% | `prefer-local-routees` | backlog |
| Sharding (`sharding-parity-spec.md`) | ~95% | external/custom shard allocation | backlog (passivation/remember-entities-store/daemon-process done in N5) |
| Singleton (`singleton-parity-spec.md`) | ~95% | lease integration | backlog |
| Core / Persistence / Stream / HTTP / Serialization | — (no spec yet) | see B/N stories | this epic |

Modules without a `docs/specs/` file (core, persistence, stream, http, serialization) are
tracked entirely by the stories above; add parity specs as those stories land if useful.
