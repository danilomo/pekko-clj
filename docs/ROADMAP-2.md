# pekko-clj Roadmap 2 — Final Review Epic ("Fable pass")

This is the **living tracker** for the second epic: bugs and gaps found in a full
fresh-eyes audit (2026-07-22) of the finished "Parity, Hardening & Ergonomics" epic
(`docs/ROADMAP.md`). It follows the same rules: **isolated sessions pick up one story
at a time**; this file is the source of truth — keep it accurate.

## Goal

Same as epic 1: maximum parity with Apache Pekko (current 1.6.x line) while staying a
nice, idiomatic Clojure library. This epic covers:

1. **Correctness bugs** — confirmed defects (one verified live, see B11).
2. **Hardening / DX** — silent fallbacks, missing validation, packaging, doc drift.
3. **Parity gaps** — the highest-value Pekko capabilities still unwrapped.

Scope stays classic-actors-only. Everything below cites the offending file/lines as of
commit `7e59e55`.

## How to work this epic (every session read this)

1. Pick the next `TODO` story whose **deps are all `DONE`**; respect milestone order
   (B → H → N); within a milestone prefer lower-risk stories first.
2. Set it to `PROGRESS` here.
3. Implement it; add/expand tests; run `lein test` (targeted ns first, then full),
   then `lein lint` and the reflection check (`lein check` must stay clean for src/).
4. Set it to `DONE` with a one-line note; update `docs/specs/*` and the `doc/` guides
   where they cover the touched module. **If you add or rename a macro clause, update
   the clj-kondo hook** (`resources/clj-kondo.exports/…/hooks/pekko_clj/defactor.clj`)
   and the `:cljfmt :extra-indents` in `project.clj`.
5. Never leave the build red between stories.

**Status legend:** `TODO` → `PROGRESS` → `DONE` (use `BLOCKED — <reason>` if needed).

---

## Story index

| ID | Title | Milestone | Status | Deps | Risk |
|----|-------|-----------|--------|------|------|
| B11 | Fix HTTP route macros: static paths never match | Bugs | TODO | — | medium |
| B12 | Make `persist-all` atomic (journal `persistAll`) | Bugs | TODO | — | medium |
| B13 | Snapshot cadence survives recovery | Bugs | DONE | — | low |
| B14 | Make the sharding envelope Transit-serializable | Bugs | TODO | — | medium |
| B15 | `defactor-persistent` unmatched commands → `unhandled()` | Bugs | DONE | — | low |
| B16 | Preserve the stash across restarts | Bugs | TODO | — | medium |
| B17 | Singleton `termination-message` default is a silent no-op | Bugs | DONE | — | low |
| B18 | `spawn-pool-with-resizer` `:pressure-threshold` mismatch | Bugs | DONE | — | trivial |
| H7 | Throw on unknown keywords (kill the silent fallbacks) | Hardening | TODO | — | low |
| H8 | Macro clause validation (`defactor`/`defactor-persistent`) | Hardening | TODO | — | low |
| H9 | Named actors (`spawn` name support) + router factories | Hardening | TODO | — | low |
| H10 | Packaging: LevelDB out of main deps, drop `:main` | Hardening | TODO | — | low |
| H11 | `doc/` guide reconciliation + naming polish | Hardening | TODO | B11 | trivial |
| H12 | Subscriber lifecycle + odds-and-ends cleanups | Hardening | TODO | — | low |
| N10 | Persistent sharded entities (CQRS aggregates) | New | TODO | B14 | high |
| N11 | Persistence depth: `persist-async`, `defer`, plugins, recovery, lifecycle | New | TODO | B12, B15 | medium |
| N12 | At-least-once delivery | New | TODO | N11 | medium |
| N13 | Streams consistency fixes + missing operators | New | TODO | — | medium |
| N14 | Streams FileIO + StreamConverters | New | TODO | N13 | low |
| N15 | HTTP routing completion: segment capture, static content, auth | New | TODO | B11 | medium |
| N16 | HTTPS + compression + request timeouts | New | TODO | N15 | medium |
| N17 | Transit record support | New | TODO | B14 | low |
| N18 | Router parity leftovers | New | TODO | H7 | low |
| N19 | Small parity odds and ends | New | TODO | — | low |

**Definition of done for this epic:** all B + H stories `DONE`; N10, N11, N13, N15
`DONE` (the rest are prioritized-optional); `doc/`+`docs/` reflect reality;
`lein test` + `lein lint` green, `lein check` reflection-clean for src/.

---

## Milestone B — Correctness bugs

### B11 · Fix HTTP route macros: static paths never match — `TODO`
**Deps:** none. **VERIFIED live** (2026-07-22): a real server bound with
`(routing/GET "/users" [] (routing/complete "users list"))` answers **404** for
`GET /users`; the `:param` form (`(GET "/user/:id" [id] …)` → 200) works; neither
form composes under `path-prefix` (nested → 404).

Why: the static-path branch of `GET`/`POST`/`PUT`/`DELETE`/`PATCH`
(`http/routing.clj:397-412` and clones) expands to `(path "/users" …)`, but the
javadsl `path(String)` directive matches a **single path segment** — a string with a
leading `/` can never match. The `:param` branch (`:407-406`) matches
`(http/request-path req)` — the **full** request path — so it ignores any enclosing
`path-prefix` context. `doc/06-http.md` showcases exactly the broken form; the unit
tests (`http/routing_test.clj:105-115`) only assert the route object is non-nil, so
nothing caught it.

**Fix:** rewrite the macros to split the pattern into segments and build a
`path-prefix`/`path` chain (static segments → `pathPrefix`, `:param` segments → a
segment-extracting directive — see N15's `path-var`, which this story may pre-empt in
private form; final segment ends with `pathEnd`-equivalent matching). Both branches
must consume the **unmatched** path so they nest under `path-prefix`.
**Tests:** end-to-end (real server, like `integration_test`): static path, `:param`
path, multi-param, nested under `path-prefix`, trailing-segment mismatch → 404, and a
regression that `(GET "users" …)` (no slash) also works. Update `doc/06-http.md`
(also H11).

### B12 · Make `persist-all` atomic — `TODO`
**Deps:** none.
`CljPersistentActor.persistAllEvents` (`CljPersistentActor.java:156-166`) persists a
`persist-all` batch as **nested single `persist` calls** — one journal write per
event. Pekko's `persistAll` writes the whole batch **atomically** (all-or-nothing in
one journal write); with the nested form, a crash mid-batch leaves a partial event
sequence — exactly the broken half-applied command that `persist-all` exists to
prevent.
**Fix:** call the real `persistAll(Iterable<Object>, Procedure)` with the tag-wrapped
events (`withTags` per event still applies); the callback applies each event +
snapshot counting as today. Keep the empty/nil-seq no-op.
**Tests:** ordering regression stays green (`persistence_test`); add an assertion
that all events of a batch share one journal write where observable (at minimum:
recovery after a kill between... — if not observable with LevelDB, document the
atomicity contract in the `persist-all` docstring and test ordering + recovery).

### B13 · Snapshot cadence survives recovery — `DONE`
**Note (2026-07-22):** fixed as scoped — `createReceiveRecover` now resets
`eventsSinceSnapshot` to 0 on `SnapshotOffer` and increments it per replayed
event in the recovery `matchAny`, so cadence carries across a restart instead
of forgetting pre-restart progress. Test:
`snapshot-cadence-survives-recovery` (`persistence_test.clj`) — uses
`snapshot-every 2, keep 1, delete-events-on-snapshot` to make the snapshot
boundary observable via journal truncation (`current-events-by-persistence-id`);
verified it fails without the fix (wrong boundary → 3 surviving events instead
of 2) before confirming the fix passes.
**Deps:** none.
`eventsSinceSnapshot` (`CljPersistentActor.java:52`) is a plain counter incremented
only in `handlePersistedEvent`; `createReceiveRecover` (`:90-106`) never touches it.
After any restart, an actor that was 49 events past its last snapshot (with
`snapshot-every 50`) starts counting from 0 again — a frequently-restarting actor can
drift arbitrarily far past its snapshot cadence, making recovery ever slower.
**Fix:** track it during recovery — count replayed events after the `SnapshotOffer`
(reset to 0 on offer, increment in the recovery `matchAny`), so cadence continues
where it left off. (Do not switch to `lastSequenceNr % snapshotEvery == 0`: an offer
at a non-multiple sequence-nr — e.g. after a manual `trigger-snapshot!` — would break
the modulo.)
**Tests:** persist `n-1` events with `snapshot-every n`, restart, persist 1 more →
snapshot fires (probe `SaveSnapshotSuccess` via state or a journal query).

### B14 · Make the sharding envelope Transit-serializable — `TODO`
**Deps:** none.
`EntityMessage` is a **defrecord** (`sharding.clj:62`). The Transit serializer binds
`clojure.lang.IPersistentCollection` (`serialization.clj:56-62`) — which records
implement — but Transit has **no record handlers** (the ns says so itself,
`serialization.clj:33-34`). Result: with `:transit-serialization` on, every
cross-node `sharding/tell`/`ask` fails at serialization time — the library's own
envelope can't cross the wire with the library's own serializer. All current tests
are single-node, so nothing caught it.
**Fix (pick one, record the decision here):**
(a) register built-in Transit write/read handlers for `EntityMessage` in
`pekko-clj.serialization` (tag e.g. `"pekko-clj/entity-msg"`), or
(b) change the envelope wire shape to plain data (breaking for `entityId` dispatch —
the extractor keys off the record type, so (a) is less invasive).
**Tests:** `write-bytes`/`read-bytes` round trip of an `EntityMessage`; an
end-to-end single-node sharding exchange under `pekko.actor.serialize-messages = on`
with `:transit-serialization` (mirrors the N4 test technique).

### B15 · `defactor-persistent` unmatched commands → `unhandled()` — `DONE`
**Note (2026-07-22):** fixed as scoped. `build-command-handler` now ports
`catch-all-pattern?` from `core.clj` and appends `:else (do (.unhandled this
command) nil)` unless the user already supplied their own catch-all clause
(bare symbol or `:else`) — mirrors `defactor`'s B3 fix exactly. Added a tiny
`unhandled` override to `CljPersistentActor` (mirrors `CljActor`'s, though
`AbstractPersistentActor.unhandled` is already public — the override is
documentation, not a functional necessity) and tagged the command handler's
`this` gensym with `CljPersistentActor` so the new `.unhandled` call resolves
without reflection. Docstring updated. Tests:
`persistent-actor-unmatched-command-goes-unhandled` (verified it fails without
the fix), `persistent-actor-user-catch-all-wins`.
**Deps:** none.
The generated command handler falls through to `:else nil`
(`persistence.clj:101-103`): an unmatched command is **silently dropped** — no dead
letter, no event-stream signal. `defactor` got this fixed in epic-1 B3 (unmatched →
Pekko `unhandled()` → `UnhandledMessage` on the event stream); the persistent macro
should behave identically.
**Fix:** default branch calls `.unhandled` on the actor (add the tiny Java override
mirroring `CljActor`'s) unless the user supplied their own catch-all (reuse/port
`catch-all-pattern?` from `core.clj:237-243`).
**Tests:** unmatched command → actor survives, `UnhandledMessage` observed
(`event-stream/subscribe-unhandled`); user catch-all still wins.

### B16 · Preserve the stash across restarts — `TODO`
**Deps:** none.
`CljActor`'s stash is a private `LinkedList` on the instance
(`CljActor.java:42, 289-337`). On a supervised restart the fresh instance starts with
an empty list — **stashed messages vanish silently**. Pekko's `Stash` contract is the
opposite: `preRestart` unstashes to the mailbox so messages survive a restart, and on
stop they go to dead letters (observable). Ours does neither.
**Fix:** in `preRestart` (`:163-174`), before `super.preRestart`, drain the stash via
`getSelf().tell(msg, sender)` (they land in the mailbox and reach the new instance);
in `postStop`, dead-letter any remaining stashed messages so they are observable.
Mind the ordering interplay with the existing "unstash appends to tail" note
(`core.clj:497-508`) — document restart semantics there too.
**Tests:** stash 2 messages, throw, `:restart` supervision → new instance processes
both; stop with a non-empty stash → messages appear as DeadLetters.

### B17 · Singleton `termination-message` default is a silent no-op — `DONE`
**Note (2026-07-22):** fixed as scoped — `singleton/start` now defaults
`:termination-message` to `(PoisonPill/getInstance)` instead of the keyword
`:stop`, and the docstring spells out that a custom message must self-stop
the actor. Test: `singleton-default-termination-message-stops-without-cooperation`
(`singleton_test.clj`) — a 1-node `cluster/leave` triggers hand-over with
nowhere to go, so the manager sends the termination-message and waits for the
child to stop; polls `singleton-running-here?` down to false within 5s.
Verified it fails (times out) without the fix, against an actor
(`vanilla-singleton`) that defines no `:stop` handling at all.
**Deps:** none.
`singleton/start` defaults `:termination-message` to the keyword `:stop`
(`singleton.clj:110-111`). Pekko sends that message to the singleton at hand-over and
**waits for the actor to terminate** — but a `defactor` that doesn't explicitly
handle `:stop` by stopping itself just treats it as unhandled, so hand-over stalls
until retries are exhausted. A trap in the default configuration.
**Fix:** default to `PoisonPill` (Pekko's own default), which stops any actor with no
cooperation needed; keep `:termination-message` for actors that want graceful
cleanup, and document in `start`'s docstring that a custom message **must** be
handled by stopping self (`(core/stop (core/self))`).
**Tests:** singleton with the default settings hands over / stops cleanly on system
shutdown (or manager stop) without the actor defining a `:stop` handler.

### B18 · `spawn-pool-with-resizer` `:pressure-threshold` mismatch — `DONE`
**Note (2026-07-22):** fixed as scoped, plus a latent bug the same constructor
call had: the 7-arg `DefaultResizer` call had `backoff-rate` and
`messages-per-resize` in the wrong positions (landing in `backoffThreshold`/
`backoffRate` respectively) and hardcoded `messagesPerResize` to `3`, silently
discarding the user's `:messages-per-resize`. Reordered to match the real
`(lowerBound, upperBound, pressureThreshold, rampupRate, backoffThreshold,
backoffRate, messagesPerResize)` signature and exposed the previously-missing
`:backoff-threshold` option (default `0.3`, Pekko's own default). Added
`:pressure-threshold` validation (non-negative integer) and updated docstrings
in `routing.clj` + `routing-parity-spec.md`. Tests:
`pool-with-resizer-accepts-docstring-example`,
`pool-with-resizer-rejects-percentage-pressure-threshold`.
**Deps:** none.
Docstring says "% busy routees to scale up (default: 1)" and the example passes
`{:pressure-threshold 0.8}` (`routing.clj:314, 322-324`), but `DefaultResizer`'s
`pressureThreshold` is an **int** (mailbox-depth threshold: 0 = busy while processing,
1 = busy when ≥1 queued, …) and the code does `(int pressure-threshold)`
(`routing.clj:337`) — so the documented `0.8` silently truncates to `0`.
**Fix:** correct the docstring + example to the real int semantics; validate the
input is a non-negative integer (throw otherwise, catching the `0.8` case).
**Tests:** docstring example compiles with a valid value; `0.8` throws.

---

## Milestone H — Hardening / DX

### H7 · Throw on unknown keywords — `TODO`
**Deps:** none.
Epic-1 modules (ddata, streams `keep-mat`, coordination) throw on unknown keywords;
the older modules silently fall back, which hides typos:
- `strategy->pool` / `strategy->group`: unknown strategy → round-robin
  (`routing.clj:56-57, 67-68`); the ns docstring even lists `:consistent-hash` as a
  strategy that `strategy->pool` does not accept.
- `->overflow-strategy`: unknown keyword → the call-site default (`stream.clj:92-106`).
- `wrap-with-supervision`: unknown `:strategy` → **no supervision at all**
  (`singleton.clj:70-71`).
- `CljSupervisorStrategy.toDirective`: unknown decider return → escalate
  (`CljSupervisorStrategy.java:35-38`).
**Fix:** all four throw `IllegalArgumentException` naming the valid options
(`nil` keeps meaning "the default" where it does today). Fix the routing ns
docstring's strategy list while there.
**Tests:** one unknown-keyword throw test per site; existing valid-keyword tests stay
green.

### H8 · Macro clause validation — `TODO`
**Deps:** none.
`parse-actor-clauses` uses `group-by first` (`core.clj:245-252`): an unknown clause —
a typo like `(on-stap …)` or `(handel …)` — is **silently discarded**, as is a second
`init` clause. Also `(second on-error-params)` (`core.clj:398-400`) assumes a 2-element
binding vector; `(on-error [ex] …)` generates an invalid `let` with a confusing
compiler error. Same silent-discard applies to `defactor-persistent`'s clause parsers
(`persistence.clj:47-89`).
**Fix:** both macros throw at expansion on (a) unknown clause heads, (b) duplicate
singleton clauses (`init`, `on-stop`, `on-restart`, `supervision`, `on-error`,
`tagger`, `snapshot-every`, `on-recovery-complete`, `:persistence-id`), (c) an
`on-error` binding vector whose length ≠ 2. Keep the error style of the existing
reserved-anaphor guards. The kondo hook already lints unknown heads as plain code —
no hook change needed unless clause sets change.
**Tests:** expansion-throw tests per case (mirror the H6 guard tests).

### H9 · Named actors + router factories — `TODO`
**Deps:** none.
There is **no way to choose an actor's name** through the core API — `spawn`
(`core.clj:82-102`), `spawn-props`, and `new-actor` all call the name-less
`.actorOf`. Yet `spawn-group`'s documented examples route to `"/user/w1"`-style
paths (`routing.clj:122-144`) that users cannot create. (`persistence/spawn-named`
already exists.)
**Fix:** add name support to core — either `(spawn sys actor-def args {:name "w1"})`
or a parallel `spawn-named` (match `persistence`'s naming for symmetry); same for
`spawn-props`. Give the `routing/spawn-*` functions an optional `:name` and accept an
`ActorRefFactory` (context) where only `ActorSystem` is accepted today, so routers
can be children.
**Tests:** named spawn resolves via `actor-selection "/user/<name>"`; a group router
built from two named workers actually routes.

### H10 · Packaging: LevelDB out of main deps, drop `:main` — `TODO`
**Deps:** none.
`[org.iq80.leveldb/leveldb "0.12"]` sits in the main `:dependencies` with the comment
"for persistence tests" (`project.clj:29-30`) — every downstream consumer inherits a
test-only storage engine. Move it to the `:dev`/`:test` profiles. (Compile-safe:
`persistence/query.clj` only references the `LeveldbReadJournal` class from
`pekko-persistence-query`, not org.iq80 — verify with `lein check` after the move.)
Also drop `:main ^:skip-aot pekko-clj.core` (`project.clj:31`) — this is a library.
**Verify:** `lein jar` + inspect the pom for the removed dep; full test suite still
green (tests run under the profiles that now carry LevelDB); README note that users
supply their own journal plugin in production.

### H11 · `doc/` guide reconciliation + naming polish — `TODO`
**Deps:** B11 (write the corrected HTTP examples once they actually work).
Epic 1 reconciled `README.md`/`CLAUDE.md`/`docs/specs` but never touched the `doc/`
guides, which still teach pre-epic APIs:
- `doc/01-actors.md:153` — "`<?>` returns a native Scala `Future` … `Await/result`"
  (B2 made it a `CompletableFuture`).
- `doc/04-cluster.md:88-89, 131` — the removed `[:entity-message id msg]` envelope
  pattern (B8 delivers unwrapped payloads + `(sharding/entity-id)`).
- `doc/05-streams.md:56` — `scala.concurrent.Future` import in examples.
- `doc/06-http.md` — showcases the (currently broken) `GET` macro and `Await/result`.
Sweep all seven files (`intro`, `01`–`06`) against the current API.
Naming polish while in doc-land (tiny code fixes, keep back-compat where cheap):
- `member->map` returns `:upNumber` (camelCase) next to `:unique-address`
  (`cluster.clj:315-322`) — add `:up-number`.
- `:previous-status` is not lower-cased (`cluster.clj:426` → `:Removed` vs
  `:status`'s `:removed`) — normalize.
- `http.core/request-query-params` docstring says multi-valued params "return the
  first value" but `into {}` keeps the **last** (`http/core.clj:114-122`) — fix the
  doc (or switch to `.toMultiMap`, then document that).

### H12 · Subscriber lifecycle + odds-and-ends cleanups — `TODO`
**Deps:** none.
- **Fn-subscriber leak:** `pubsub/subscribe`, `ddata/subscribe`,
  `event-stream/subscribe` spawn an internal handler actor; the matching
  `unsubscribe` stops delivery but **leaves the actor alive forever**. Stop internal
  subscribers on unsubscribe (track them, e.g. an internal registry or a marker the
  unsubscribe fns check) or return a handle whose `close!` does both; make all three
  modules consistent and documented.
- **Cluster subscribe initial state:** the first message a `cluster/subscribe`
  handler sees is the `CurrentClusterState` snapshot, which `event->map` renders as
  `{:type :unknown}` (`cluster.clj:453-457`). Map it (`{:type :current-state …}`) or
  subscribe with `initialStateAsEvents`; also allow an optional event-class filter
  (e.g. `:member-events`) instead of always `ClusterDomainEvent`.
- **Leftover `set! *warn-on-reflection*`:** `project.clj:44-48` moved the flag to
  `:global-vars` precisely because per-ns `set!` was unreliable, yet `core.clj:531`
  still resets it to `false`, and `daemon.clj`, `ddata.clj`, `serialization.clj`,
  `marshalling.clj` still carry set!-pairs. Delete them all.
- **`ask-blocking`:** also treat `CancellationException` as nil-timeout
  (`core.clj:134-143`) so a cancelled future doesn't escape as a raw throw.

---

## Milestone N — Parity

### N10 · Persistent sharded entities (CQRS aggregates) — `TODO`
**Deps:** B14. **Highest-value story in the epic.**
`sharding/start` hard-codes `CljActor/create` (`sharding.clj:260`), so a
`defactor-persistent` definition cannot be sharded — yet "persistent entity per
aggregate, addressed by id, passivated when idle" is *the* canonical Pekko sharding
pattern. Feeding a persistent def in today would NPE (its props-map has
`:command-handler`, not the `:function` CljActor requires).
**Fix:** dispatch in `start` on `(:type actor-def)`:
- `:persistent-actor` → build `CljPersistentActor` props. The shared entity Props
  gets nil args, so the persistence id must come from the **entity id** — extend
  `CljPersistentActor` to accept a `persistence-id-fn`-of-path-name mode (Pekko names
  each entity actor by its id, as `sharding/entity-id` already exploits): persistence
  id = `(persistence-id-fn (path-name))`, with the actor-def's existing
  `:persistence-id-fn` receiving the entity id as its argument.
- else → current classic path, plus a new `:args` option so plain entities can share
  init args (today init always receives nil, `sharding.clj:244-246`).
Also: expose region **graceful shutdown** (`ShardRegion.gracefulShutdownInstance`)
and a `state->map` for `shard-region-state` mirroring `stats->map`.
**Tests:** end-to-end sharded persistent counter — tell/ask by id, passivate, message
again → state recovered from the journal; two ids isolate state; `:args` reaches a
classic entity's init; graceful-shutdown drains a region.

### N11 · Persistence depth — `TODO`
**Deps:** B12, B15.
The persistent macro supports far fewer capabilities than `defactor`:
- **Lifecycle:** no `on-stop`, no `supervision`, no timers (`CljPersistentActor`
  extends `AbstractPersistentActor`; move to `AbstractPersistentActorWithTimers` and
  wire the same clauses/props `CljActor` has).
- **`persist-async`** (`persistAsync`) and **`defer`** (`deferAsync`) for
  throughput-sensitive actors — expose as marker-returning helpers like
  `persist`/`persist-all` (extend the `PersistAll`-marker approach).
- **Per-actor plugin ids:** `journalPluginId`/`snapshotPluginId` overrides
  (sharding-settings already does this for remember-entities; the actor itself
  cannot).
- **Recovery customization:** `Recovery.create(fromSnapshot/replayMax)` /
  `Recovery.none()` via a `(recovery …)` clause — enables "commands-only" actors and
  bounded replay.
Update the kondo hook + cljfmt indents for every new clause (per CLAUDE.md).
**Tests:** each clause end-to-end; persist-async ordering vs persist; recovery-none
skips replay.

### N12 · At-least-once delivery — `TODO`
**Deps:** N11.
`AbstractPersistentActorWithAtLeastOnceDelivery` is a marquee Pekko persistence
feature with no wrapper: reliable actor-to-actor delivery with redelivery +
confirmation, surviving restarts. Design a small clause/API surface
(`deliver`/`confirm-delivery`, `:redeliver-interval`, unconfirmed-warning) on top of
the N11-refactored Java class. Keep it opinionated; document the
delivery-id-in-message pattern.
**Tests:** delivery redelivered until confirmed; confirmation stops redelivery;
state (delivery snapshots) survives restart.

### N13 · Streams consistency fixes + missing operators — `TODO`
**Deps:** none.
Consistency (bug-adjacent):
- `merge-substreams`/`concat-substreams` hint `^SubSource` (`stream.clj:558-566`) —
  a `SubFlow` (from `group-by` on a **Flow**) throws `ClassCastException`. Dispatch
  like the `op` macro does.
- `source-queue` returns `[queue source]` but `source-actor-ref` returns
  `[source actor-ref]` (`stream.clj:732-741` vs `:445-469`) — unify on maps
  (`{:source … :queue …}` / `{:source … :actor-ref …}`) with a deprecation window,
  matching `run-source-queue`'s shape.
- `zip-with-index` leaks `japi.Pair` elements (`stream.clj:848-851`) — map to
  `[elem idx]` vectors.
- `distinct` (`stream.clj:819-831`) actually implements **`dedupe`** (consecutive
  duplicates) while shadowing `clojure.core/distinct` (all duplicates) — rename to
  `dedupe`/`dedupe-by` (keep deprecated aliases one release).
- `materializer` creates a fresh (leakable) materializer per call
  (`stream.clj:112-116`) — add `system-materializer` (via `SystemMaterializer`, the
  B9 pattern) as the documented default, and let every `run-*` accept an
  `ActorSystem` in place of a `Materializer`.
- Verify `Source/lazily` (`stream.clj:167-173`) against 1.6 javap (B7 technique);
  migrate to `Source.lazySource` if deprecated.
Missing operators (each trivial with `op`): `collect` (PF map+filter — expose as
Clojure-idiomatic `skeep`, keep-shaped: fn returns nil to drop), `zip`, `zip-all`,
`interleave`, `prepend`, `or-else`, `divert-to`, `limit`, `take-last`,
`Source/never`, `source-unfold-async`, `sink-head-option`/`sink-last-option`
(empty-safe variants of head/last).
**Tests:** per-op driving tests; SubFlow regression through a Flow `group-by`;
run-with-system-arity smoke test.

### N14 · Streams FileIO + StreamConverters — `TODO`
**Deps:** N13.
No file or blocking-IO integration at all — a glaring practical gap for a streams
API: `FileIO.fromPath`/`toPath` (source/sink of ByteString with IOResult mat-value),
`StreamConverters.fromInputStream`/`fromOutputStream`/`asInputStream`/`asOutputStream`,
plus ByteString helpers (`->byte-string`, `byte-string->`, a `lines` framing flow via
`Framing.delimiter`). Compose with `http.response/stream` for file serving (ties into
N15's static content).
**Tests:** file round trip incl. IOResult count; framing on a multi-line file;
input-stream source.

### N15 · HTTP routing completion: segment capture, static content, auth — `TODO`
**Deps:** B11.
- **`path-var`** — the idiomatic directive B11's macros will want: extract one path
  segment as a value (`(path-var (fn [id] …))`, javadsl
  `path(segment(), fn)`-equivalent), plus `path-prefix-var`. Today the only capture
  mechanism is the macro pattern-match hack.
- **Static content:** `getFromResource`/`getFromDirectory` wrappers
  (`from-resource`, `from-directory`) with content-type detection.
- **Auth:** `authenticateBasic` wrapper (`basic-auth` taking a
  `(fn [user pass] user-or-nil)`), and a bearer-token helper on top of
  `header-value`.
- **Marshalling wart to resolve (decision):** `->json`/`->edn` pass strings through
  unchanged (`marshalling.clj:33-37, 50-53`), so a bare Clojure string can never be
  served as a *JSON/EDN string value* (`(resp/json "hello")` emits invalid JSON
  `hello`). Either drop the passthrough (breaking; add `raw-json` for pre-encoded
  bodies) or keep + document loudly. Record the decision here.
**Tests:** end-to-end per directive; auth 401/challenge; static file with correct
content type; the json-string decision's behavior pinned.

### N16 · HTTPS + compression + request timeouts — `TODO`
**Deps:** N15.
- **HTTPS:** server (`ConnectionContext/httpsServer` from a keystore opts map,
  `ServerBuilder.enableHttps`) and client (`Http.setDefaultClientHttpsContext` /
  per-request context).
- **Compression:** `encodeResponse` / `decodeRequest` directives.
- **Timeouts:** `withRequestTimeout` directive; expose the strict-entity timeouts
  hard-coded in `http/core.clj:151-153` and `client.clj:21-23` as options.
**Tests:** self-signed round trip; gzip response verified by header + decoded body;
timeout returns 503.

### N17 · Transit record support — `TODO`
**Deps:** B14 (which handles the built-in record; this generalizes).
User records in messages/events currently fail serialization (documented limitation,
`serialization.clj:33-34`). Add opt-in support: `transit-config {:records [my.ns.Foo …]}`
generating a tagged write handler (record → map + tag from class name) and read
handler (`map->Foo`) per record; same option on `write-bytes`/`read-bytes` for
standalone use. Cache constructed handler maps alongside the existing per-system
cache.
**Tests:** record round trip standalone and through a live system
(`serialize-messages = on`); nested records; unknown-tag failure mode is clear.

### N18 · Router parity leftovers — `TODO`
**Deps:** H7.
- Group variants that exist upstream but not here:
  `ScatterGatherFirstCompletedGroup`, `TailChoppingGroup`.
- `:supervisor-strategy` option on the pool spawners (pools supervise their routees;
  today only the default escalate is available — pass a
  `pekko-clj.supervision` strategy through `.withSupervisorStrategy`).
- `:dispatcher` option (`.withDispatcher`) on pools.
- `prefer-local-routees` for cluster groups (promoted from the epic-1 backlog;
  `routing-parity-spec.md`'s last ❌).
Update `routing-parity-spec.md`.
**Tests:** group variants end-to-end; a pool child failure handled by the provided
strategy (not escalated).

### N19 · Small parity odds and ends — `TODO`
**Deps:** none. A grab-bag of one-liners; do in one session:
- **CircuitBreaker:** `:max-reset-timeout` + `:exponential-backoff-factor` +
  `:random-factor` (the `CircuitBreaker.create` overload/withers), and
  `defineFailureFn` (`:failure-fn` — count specific results as failures).
- **Pub-sub:** `count-subscribers`/`get-topics` (mediator `Count`/`GetTopics`).
- **Sharding:** surface `ShardRegionStats.getFailed` in `stats->map`.
- **Persistence query:** surface `EventEnvelope.eventMetadata` in `envelope->map`
  (nil when absent).
- **Watch:** optional message form of watch — `watchWith`-style
  (`(core/watch ref msg)`) so `Terminated` can carry a user marker.
Update the relevant docstrings; one test each.

---

## Backlog (documented, not scheduled — carried from epic 1, still valid)

Typed actors; durable state (needs JDBC/R2DBC); full GraphDSL / custom GraphStage;
cluster-metrics; replicated event sourcing; Alpakka connectors; EventsBySlice;
external/custom shard allocation; multi-DC; singleton lease integration. New from
this audit: more CRDTs (ORMultiMap, Flag, GCounter, LWWRegister — `ddata` is
deliberately small, revisit on demand); ddata durable-storage/expiry options; HOCON
generation via a proper Config builder instead of string concat (`create-system`,
`split-brain-resolver-config` — works today, just brittle).

---

## Dependency order (summary DAG)

```
B11 ─► H11, N15 ─► N16
B12 ─┬► N11 ─► N12
B15 ─┘
B14 ─► N10, N17
B13, B16, B17, B18   (independent)
H7 ─► N18
H8, H9, H10, H12     (independent)
N13 ─► N14
N19                  (independent)
```

---

## Audit notes (what was checked, 2026-07-22)

Full read of every `src/main/clj` namespace and `src/main/java` class at `7e59e55`,
cross-checked against Pekko 1.6 javadsl semantics; `B11` verified against a live
server (static-path macro → 404, param macro → 200, nested → 404). Test-suite gaps
that let these through: HTTP macro tests assert only route construction; sharding and
serialization tests are single-node; no restart-with-stash test; no snapshot-cadence-
after-recovery test. Each story above names its regression test so the gap closes
with the fix.
