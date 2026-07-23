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
| B11 | Fix HTTP route macros: static paths never match | Bugs | DONE | — | medium |
| B12 | Make `persist-all` atomic (journal `persistAll`) | Bugs | DONE | — | medium |
| B13 | Snapshot cadence survives recovery | Bugs | DONE | — | low |
| B14 | Make the sharding envelope Transit-serializable | Bugs | DONE | — | medium |
| B15 | `defactor-persistent` unmatched commands → `unhandled()` | Bugs | DONE | — | low |
| B16 | Preserve the stash across restarts | Bugs | DONE | — | medium |
| B17 | Singleton `termination-message` default is a silent no-op | Bugs | DONE | — | low |
| B18 | `spawn-pool-with-resizer` `:pressure-threshold` mismatch | Bugs | DONE | — | trivial |
| H7 | Throw on unknown keywords (kill the silent fallbacks) | Hardening | DONE | — | low |
| H8 | Macro clause validation (`defactor`/`defactor-persistent`) | Hardening | DONE | — | low |
| H9 | Named actors (`spawn` name support) + router factories | Hardening | DONE | — | low |
| H10 | Packaging: LevelDB out of main deps, drop `:main` | Hardening | DONE | — | low |
| H11 | `doc/` guide reconciliation + naming polish | Hardening | DONE | B11 | trivial |
| H12 | Subscriber lifecycle + odds-and-ends cleanups | Hardening | DONE | — | low |
| N10 | Persistent sharded entities (CQRS aggregates) | New | DONE | B14 | high |
| N11 | Persistence depth: `persist-async`, `defer`, plugins, recovery, lifecycle | New | DONE | B12, B15 | medium |
| N12 | At-least-once delivery | New | DONE | N11 | medium |
| N13 | Streams consistency fixes + missing operators | New | DONE | — | medium |
| N14 | Streams FileIO + StreamConverters | New | DONE | N13 | low |
| N15 | HTTP routing completion: segment capture, static content, auth | New | DONE | B11 | medium |
| N16 | HTTPS + compression + request timeouts | New | DONE | N15 | medium |
| N17 | Transit record support | New | DONE | — | low |
| N18 | Router parity leftovers | New | DONE | H7 | low |
| N19 | Small parity odds and ends | New | DONE | — | low |

**Definition of done for this epic:** all B + H stories `DONE`; N10, N11, N13, N15
`DONE` (the rest are prioritized-optional); `doc/`+`docs/` reflect reality;
`lein test` + `lein lint` green, `lein check` reflection-clean for src/.

---

## Milestone B — Correctness bugs — **complete** (all eight `DONE`, 2026-07-22)

### B11 · Fix HTTP route macros: static paths never match — `DONE`
**Note (2026-07-22):** fixed as scoped — the five macros now compile the pattern
into a chain of path directives (static segment → `path-prefix`, `:param` →
`path-prefix-var`, last segment → `path`/`path-var`), all of which consume from
the *unmatched* path, so static paths match, params bind by name, and both nest
under `path-prefix`. The method directive moved inside the path chain, so a
matching path with the wrong method is now a 405 instead of a 404. `path` /
`path-prefix` themselves were rewritten to split on `/` and tolerate a leading
slash (`"/api/v1"`, `"api/v1"`, `"users"` all work) — that is what made the
documented `(path "/users" …)` form dead. Pre-empted N15's first bullet: the
segment capture is public as **`path-var` / `path-prefix-var`** (see N15).
Added a clj-kondo hook (`hooks/pekko_clj/routing.clj`, wired in the export's
`config.edn`) so `[id]` bindings resolve in user code, plus cljfmt indents for
the two new directives. Tests (`http/integration_test.clj`, real server):
`macro-static-path-test`, `macro-param-path-test`,
`macro-nests-under-path-prefix-test`, `macro-path-must-end-test`,
`path-var-directives-test`, `multi-segment-path-directive-test` — verified 4
assertions across the static/nested tests fail against the old macros before the
fix. `doc/06-http.md` updated (path-pattern rules, nesting, `path-var`, and the
`Await/result` → `stream/await-completion` note); no `docs/specs/*` checklist
covers HTTP routing (`routing-parity-spec.md` is about *router* strategies).
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

### B12 · Make `persist-all` atomic — `DONE`
**Note (2026-07-22):** fixed as scoped — `persistAllEvents` now tag-wraps the
whole batch into a `List` and makes one `persistAll(Iterable, Procedure)` call
instead of recursing through nested single `persist`s; the callback still runs
per event, in order, so event application and snapshot cadence are unchanged.
Empty/nil batches stay a no-op. Test: `persist-all-batch-is-atomic`
(`persistence_test.clj`) — a batch with an unserializable event in the middle is
rejected by the journal; with one atomic write no event of the batch is applied,
where the nested form had already applied everything before the bad event
(verified failing without the fix). **Atomicity is only partly observable under
LevelDB**, so the assertion is on actor state, not journal contents: the
`LeveldbStore` serializes an AtomicWrite's events into a *shared* LevelDB write
batch and commits it even when a later event of that same atomic write fails to
serialize, so a rejected batch can still leave bytes on disk there — measured,
not assumed (the first draft of this test asserted an untouched journal and
failed). The contract is documented in `persist-all`'s docstring and in
`README.md`; ordering + recovery stay covered by
`persist-all-stress-ordering-under-retention` and
`persist-all-large-batch-crosses-snapshot-boundaries`. No `docs/specs/*`
checklist covers persistence.
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

### B14 · Make the sharding envelope Transit-serializable — `DONE`
**Note (2026-07-22): decision = (b), plain data.** The envelope is now
`{::entity-id id ::message msg}` — a map with keys namespaced to
`pekko-clj.cluster.sharding` — and the `EntityMessage` record is gone;
`entity-message` still builds it and a new `entity-message?` predicate replaces
the `instance?` check in the extractor (2 lines). Chose (b) over the tracker's
leaning to (a) for two reasons: (a) fixes the envelope for *Transit only* — a
user on any other serializer would hit the same wall — whereas plain data crosses
under every serializer that can carry Clojure data, Java serialization included;
and registering the handler in `pekko-clj.serialization` would have made a
low-level namespace depend on `pekko-clj.cluster.sharding` (no cycle today, but
`cluster` → `serialization` already exists, so the next `serialization` require
added to sharding would create one). Namespaced keys keep the envelope
distinguishable from a user message that happens to be a map. **Breaking:** the
`EntityMessage` record and its `->EntityMessage`/`map->EntityMessage`
constructors are removed; the envelope is internal (built by `tell`/`ask`,
unwrapped by the extractor before delivery), so nothing else in the tree used it.
The real symptom was worse than a serialization exception: Transit *writes* a
record as a plain map, so the payload arrived with its type erased and the
extractor's `instance?` check silently returned no entity id — the message was
dropped rather than failing loudly (measured while verifying the tests).
Tests: `entity-message-envelope-is-plain-data-test` (round trip through
`write-bytes`/`read-bytes`, plus "an unqualified-key user map is not an
envelope") and `sharding-under-transit-serialization-test` (single-node region
under `:transit-serialization` + `pekko.actor.serialize-messages = on`, the N4
technique — tell/ask by id, two ids stay isolated); both verified failing
against a restored record envelope. `docs/specs/sharding-parity-spec.md` updated
(envelope row + the two flow diagrams); `serialization.clj`'s "records are not
handled" note now points at the envelope as the worked example.
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

### B16 · Preserve the stash across restarts — `DONE`
**Note (2026-07-22):** fixed as scoped — both lifecycle hooks now route the
stash through one private `drainStashToSelf()` (which `unstashAll` also uses, so
there is a single re-enqueue path): `preRestart` drains it *after* the
`:pre-restart` hook (so a hook that deliberately calls `clear-stash` still wins)
and before `super.preRestart`, putting the messages back in the mailbox a
restart keeps; `postStop` drains whatever is left to a self that is already
stopped, which routes it to dead letters. Because preRestart drains first,
postStop finds an empty stash on the restart path — the messages are re-enqueued
once, not dead-lettered as well. Ordering is unchanged from the existing
"unstash appends to tail" contract, now documented for the restart case in
`core/stash`'s docstring and `CljActor.stash`'s javadoc. Tests:
`stash-survives-restart` (two stashed messages, `:restart` supervision, fresh
instance re-stashes then processes both in order) and
`stash-becomes-dead-letters-on-stop` (poison-pill with a non-empty stash →
both messages observed via `event-stream/subscribe-dead-letters`, in stash
order); both verified failing without the fix, with the six existing stash tests
still green. No `docs/specs/*` or `doc/` guide covers stash.
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

## Milestone H — Hardening / DX — **complete** (all six `DONE`, 2026-07-22)

### H7 · Throw on unknown keywords — `DONE`
**Note (2026-07-22):** fixed all four sites as scoped. `strategy->pool`/
`strategy->group` (`routing.clj`) now `case`-match `(nil :round-robin)` to the
round-robin default and throw `IllegalArgumentException` naming the valid
options for anything else — nil still means "default" exactly as it did
before, since `spawn-pool`/`spawn-group`'s `:or` only fills in the default when
the `:strategy` key is *absent*, not when it's explicitly `nil`. Also rewrote
the ns docstring's strategy table: `:consistent-hash` is gone from it (it was
never accepted by `strategy->pool`/`-group` — falls to round-robin, the exact
bug this story fixes) and a note now points at the dedicated
`spawn-consistent-hash-pool`/`-group` functions that actually implement it
(mirrors the same fix already made in `doc/03-routing.md` for H11).
`->overflow-strategy` (`stream.clj`) keeps `nil → default` as an explicit case
clause and throws on anything else. `wrap-with-supervision`
(`singleton.clj`) throws on an unrecognized `:strategy` once a `:supervision`
map is supplied at all (the outer nil/false-means-no-supervision check is
unchanged — that's a different semantic layer than the bug here).
`CljSupervisorStrategy.toDirective` (Java) now throws `IllegalArgumentException`
instead of silently escalating on a decider return that isn't one of the four
known keywords; made `public` (from `private`) purely so it's unit-testable
directly — it's still only called internally by `oneForOne`/`allForOne`.
Tests: `pool-unknown-strategy-throws`, `group-unknown-strategy-throws`
(`routing_test.clj`); `buffer-unknown-strategy-throws` plus a
`buffer-with-valid-strategy-passes-elements-through` green-path regression
since `buffer` had no test coverage at all before this (`stream_test.clj`);
`singleton-unknown-supervision-strategy-throws` (`singleton_test.clj` — a plain
non-cluster system suffices since `wrap-with-supervision` runs before any
cluster extension is touched); `to-directive-unknown-result-throws`
(`supervision_test.clj`, direct-calls the now-public method to avoid a flaky
actor-crash integration test). All new throw tests verified failing (silent
fallback / no exception) before their fix. Existing valid-keyword tests across
all four sites stay green. `docs/specs/routing-parity-spec.md` already
documents consistent-hash correctly as its own function, not a `:strategy`
value — nothing to tick there. `lein test` (500 tests), `lein lint`, `lein
check` (no reflection warnings) all clean.
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

### H8 · Macro clause validation — `DONE`
**Note (2026-07-22):** fixed all three cases as scoped, in both macros.
`defactor` (`core.clj`): new `validate-actor-clauses` runs before
`parse-actor-clauses` and throws on (a) any clause whose head isn't one of
`init`/`handle`/`on-stop`/`on-restart`/`supervision`/`on-error`, and (b) more
than one of the five singleton clauses (`handle` is the only one allowed to
repeat — group-by was silently keeping the *first* on a duplicate, discarding
the rest). Also added the `on-error` binding-vector-length guard next to the
existing reserved-anaphor checks: `(on-error [ex] ...)` now throws naming the
problem instead of splicing `nil` into a `let` binding position and surfacing
a confusing downstream compiler error.
`defactor-persistent` (`persistence.clj`): same idea via a new
`validate-persistent-clauses`, adapted to this macro's flatter grammar — it
walks the clause seq treating `:persistence-id` as a bare keyword/value pair
(not a list clause) so it isn't misidentified as an unknown clause, and
enforces at most one each of `init`/`tagger`/`snapshot-every`/
`on-recovery-complete`/`:persistence-id` (`command`/`event` repeat freely;
`delete-events-on-snapshot` is a boolean flag where duplicates are harmless, so
neither is in the singleton set — matches the tracker's list exactly, which
didn't include them either).
No clause heads were added or renamed, so the clj-kondo hooks
(`resources/clj-kondo.exports/.../hooks/pekko_clj/defactor.clj`) and the
cljfmt `:extra-indents` need no changes.
Tests (mirroring the existing H6 reserved-anaphor guard tests' macroexpand-1 +
unwrap-CompilerException pattern): `defactor-rejects-unknown-clause-head`,
`defactor-rejects-duplicate-init-clause`,
`defactor-rejects-on-error-with-wrong-binding-arity` (`defactor_test.clj`);
`defactor-persistent-rejects-unknown-clause-head`,
`defactor-persistent-rejects-duplicate-tagger-clause`,
`defactor-persistent-rejects-duplicate-persistence-id`
(`persistence_test.clj`). All six verified failing (silently accepted instead
of throwing) before their fix. `lein test` (506 tests), `lein lint`, `lein
check` (no reflection warnings) all clean.
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

### H9 · Named actors + router factories — `DONE`
**Note (2026-07-22):** fixed both parts as scoped, went with the opts-map
option over a parallel `spawn-named` (keeps `spawn`'s existing polymorphic
shape rather than adding a whole second entry point — `persistence/spawn-named`
stays as-is since that macro's `spawn` isn't polymorphic the same way).
`core/spawn` gets a new trailing-opts arity for both the context and
top-level forms (`(spawn actor-def args opts)` / `(spawn system actor-def args
opts)`) supporting `{:name "child-name"}`; the existing 3-arg arity now
internally dispatches on `(instance? ActorSystem first-arg)` the same way the
2-arg arity already did, so `(spawn actor-def args opts)` and `(spawn system
actor-def args)` share one arity without ambiguity. `core/spawn-props` gets a
matching 3-arg form. Both funnel through a new private `actor-of` helper that
picks the `(Props)` or `(Props, String)` `.actorOf` overload.
`routing.clj`: every `spawn-*` function (`spawn-pool`, `spawn-group`,
`spawn-consistent-hash-pool`/`-group`, `spawn-scatter-gather-pool`,
`spawn-tail-chopping-pool`, `spawn-pool-with-resizer`, `spawn-cluster-pool`,
`spawn-cluster-group`) now takes an optional `:name` in its opts map and its
`system` parameter is hinted `^ActorRefFactory` instead of `^ActorSystem` —
that's not just a docs-only relaxation: the old hint meant passing an
`ActorContext` (i.e. `(core/context)`, to make a router a child of another
actor) would `ClassCastException` at the `.actorOf` call site, since Clojure
compiles a type-hinted interop call against the hinted class. Added a private
`actor-of` helper mirroring core's. `ActorSystem` import dropped from
`routing.clj` (no longer referenced anywhere after the hint changes — `lein
check` would have flagged it unused).
Tests: `spawn-with-name-resolves-via-actor-selection`,
`spawn-with-name-inside-context-resolves-as-child`,
`spawn-props-with-name-resolves-via-actor-selection` (`core_test.clj`);
`named-workers-enable-known-group-paths` (spawns two named workers, then
builds the group router from literal `"/user/..."` strings the way
`spawn-group`'s own docstring always showed — that pattern was unreachable
before this story) and `spawn-pool-as-child-of-actor-context` (spawns a pool
via `(core/context)` from inside a running actor) in `routing_test.clj`. All
new/changed-signature tests confirmed passing against the existing (unchanged)
test suite too — no regressions across `routing-test`/`core-test`/`actor-test`/
`defactor-test`. Docs: `doc/03-routing.md` gained a "Named Actors and Group
Routers" section with both examples; `README.md`'s spawn snippet shows the
named form. No `docs/specs/*` checklist covers this (checked
`routing-parity-spec.md` — no name/child-router rows exist to tick).
`lein test` (511 tests), `lein lint`, `lein check` (no reflection warnings)
all clean.
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

### H10 · Packaging: LevelDB out of main deps, drop `:main` — `DONE`
**Note (2026-07-22):** fixed as scoped. `[org.iq80.leveldb/leveldb "0.12"]`
moved from the main `:dependencies` to the `:dev` and `:test` profiles
(both, since `lein test`/`lein check`/`lein repl` merge different default
profile sets); `:main ^:skip-aot pekko-clj.core` dropped outright — `core.clj`
never defined a `-main`, so it was a no-op entry point on a library besides
the misleading manifest metadata. Compile-safety confirmed exactly as the
story predicted: `persistence/query.clj` only imports
`org.apache.pekko.persistence.query.journal.leveldb.javadsl.LeveldbReadJournal`
(from `pekko-persistence-query`, already a main dependency), never
`org.iq80.leveldb` directly.
**Verified via the built artifact**, not just inspection: `lein jar`'s
embedded `META-INF/maven/pekko-clj/pekko-clj/pom.xml` now lists the leveldb
dependency with `<scope>test</scope>` (confirms downstream consumers of the
jar won't pull it transitively), and the manifest's `Main-Class` fell back to
Leiningen's own default (`clojure.main`) instead of naming `pekko-clj.core`.
`lein test` (511 tests, unchanged — persistence tests run fine since `:dev`/
`:test` still carry LevelDB) stayed green throughout.
README gained a paragraph after the persistence section: LevelDB is
test-only/wired up in `test/resources/persistence-test.conf`, and production
users configure their own Pekko Persistence plugin (jdbc/r2dbc/cassandra).
No `docs/specs/*` checklist covers packaging. `lein lint`, `lein check` (no
reflection warnings) both clean.
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

### H11 · `doc/` guide reconciliation + naming polish — `DONE`
**Note (2026-07-22):** swept all seven `doc/` guides plus `README.md` against the
current API. Fixed: `doc/intro.md` and `doc/01-actors.md` described `<?>` as
returning "a native Scala `Future`" (B2 made it a `CompletableFuture`/
`CompletionStage`) — same stale line found and fixed in `README.md`, which the
tracker didn't call out but has the identical bug. `doc/04-cluster.md`'s
sharding example still showed the pre-B14/B8 `[:entity-message id msg]`
envelope pattern in the handler and in "Key Differences" #2; rewritten to match
the real API — the actor matches the raw unwrapped message and reads
`(sharding/entity-id)`. `doc/06-http.md` was already current (fixed as part of
B11's own note) and `doc/05-streams.md:56`'s `scala.concurrent.Future` import
turned out to be correct, legitimate Scala-contrast code, not drift — verified
against git history (file untouched since creation) and left as-is.
Found one more doc bug while sweeping not named in the tracker:
`doc/03-routing.md`'s `spawn-cluster-pool` example called it with a stray
positional size arg and options (`:max-instances-per-node`,
`:allow-local-routees`) that don't exist on the real 3-arg signature
(`:total-instances`/`:max-per-node`/`:allow-local`) — copy-pasting it would
throw. Also clarified `:consistent-hash`, listed in the strategy table as if
`{:strategy :consistent-hash}` worked with `spawn-pool`/`spawn-group` — it
doesn't (falls back to round-robin, the exact silent-fallback H7 will fix); the
table now points at the dedicated `spawn-consistent-hash-pool`/`-group`
functions that actually implement it.
Naming polish, all three items from the tracker: `member->map` now also
returns `:up-number` (kebab-case) alongside the retained `:upNumber` (back-compat,
cheap to keep); `event->map`'s `:previous-status` on `:member-removed` is now
lower-cased to match `:status`'s convention (was e.g. `:Removed` vs `:removed`);
`request-query-params`/`request-headers` docstrings corrected from "first value"
to "last value" (`into {}` on the pair sequence keeps the last, not the first —
chose the docstring fix over switching to `.toMultiMap` since the tracker
offered both and the doc was simply wrong).
Tests: `pekko-clj.cluster-test/members-by-age-test` extended to assert
`:up-number` is present and matches `:upNumber`;
`member-removed-event-previous-status-is-lower-cased` (new) drives the private
`event->map` directly with a real `Member` forced into `Removed` status via
`Member$.MODULE$.removed(uniqueAddress)` — avoids needing an actual multi-node
removal — and asserts `:previous-status` is `:up`, not `:Up`.
`pekko-clj.http.core-test/request-query-params-multi-valued-test` and
`request-headers-multi-valued-test` (new) pin the "last value wins" contract
directly (the header test needed `withHeaders` with an explicit list rather
than chained `.addHeader` calls, since `addHeader` prepends — verified by a
failing first draft). All four new/extended tests fail without their
corresponding fix. `lein test` (494 tests, targeted + full), `lein lint`, and
`lein check` (no reflection warnings) all clean. No `docs/specs/*` checklist
covers these doc guides or the naming polish (`cluster-parity-spec.md`'s
`upNumber` mentions are prose about the underlying Pekko field, not our map
keys — nothing to tick).
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

### H12 · Subscriber lifecycle + odds-and-ends cleanups — `DONE`
**Note (2026-07-22):** fixed all four items as scoped.
**Fn-subscriber leak:** `event-stream`, `cluster.pubsub`, and `cluster.ddata`
each get the same small pattern (duplicated per-namespace, matching this
codebase's existing style of small private per-ns helpers like `make-props`
rather than a new shared utility ns): a `(defonce internal-subscribers (atom
#{}))` registry, populated only when `subscribe` spawns its own handler actor
(never for a caller-supplied ActorRef), and a private `stop-if-internal!` that
`unsubscribe` now calls alongside its existing deregistration — poison-pilling
the actor if (and only if) it's in the registry, then forgetting it. A
caller-supplied ActorRef is never touched, since the caller owns its lifecycle.
`cluster.ddata/subscribe`'s docstring used to candidly admit the gap ("pass it
to unsubscribe (and stop it when it is one this function spawned)") — that
manual burden is gone now.
**Cluster subscribe initial state:** chose `initialStateAsEvents` over mapping
`CurrentClusterState` to a new `:current-state` type — it reuses every
existing per-event-type branch in `event->map` (a synthetic `:member-up` per
already-Up member, etc.) instead of introducing a second, differently-shaped
snapshot representation. `subscribe` gained an opts-map arity with `:events`
(`:all` default, `:member-events`, `:reachability-events` — narrows to
Pekko's `MemberEvent`/`ReachabilityEvent` marker interfaces instead of always
subscribing to the blanket `ClusterDomainEvent`); an unrecognized `:events`
value throws `IllegalArgumentException` (new capability, so no prior
silent-fallback to fix — just built correctly from the start, consistent with
H7). Note: subscribing to an already-Up cluster still surfaces the
event-class gap the initial-state fix doesn't touch — `SeenChanged` (and a
few other `ClusterDomainEvent` subtypes `event->map` never mapped) render as
`{:type :unknown}` when they occur as *live* events, same as always; that's
unrelated pre-existing incompleteness, not this story's target (only the
snapshot-object case was in scope).
**Leftover `set! *warn-on-reflection*`:** deleted all six pairs (`core.clj`,
`cluster/daemon.clj`, `cluster/ddata.clj`, `serialization.clj`,
`http/marshalling.clj`) — `:global-vars` in `project.clj` already controls the
flag for the whole `src/` compile; per-ns `set!` was leftover from before that
change and did nothing but risk exactly the "leaks into whatever compiles next"
bug the `:global-vars` switch was made to avoid.
**`ask-blocking`:** added a `(catch CancellationException _ nil)` clause
alongside the existing `TimeoutException`/`AskTimeoutException` handling — a
cancelled future has no reply to return either way, so it's nil like a timeout
rather than an uncaught `CancellationException` escaping `<!`.
Tests: `unsubscribe-stops-internally-spawned-subscriber-test` +
`unsubscribe-does-not-stop-a-caller-supplied-ref-test` in all three of
`event_stream_test.clj`, `cluster/pubsub_test.clj`, `cluster/ddata_test.clj`
(new `test-support/stopped-within?` helper: a throwaway DeathWatch actor,
reusable wherever a test needs to confirm an actor actually stopped rather
than just being unreachable); `cluster-subscribe-initial-state-is-synthesized-
as-events`, `cluster-subscribe-member-events-filter`,
`cluster-subscribe-unknown-events-filter-throws` in `cluster_test.clj`;
`blocking-ask-cancelled-future-returns-nil` in `core_test.clj` (uses
`with-redefs` on the public `core/<?>` to hand `ask-blocking` an
already-cancelled `CompletableFuture`, since the real future it blocks on
isn't otherwise externally reachable to cancel). All new tests verified
failing without their corresponding fix. No test needed for the `set!`
deletions — `lein check`'s reflection-warning grep is the regression guard.
`lein test` (521 tests), `lein lint`, `lein check` (no reflection warnings)
all clean. No `docs/specs/*` checklist changes — `cluster-parity-spec.md`'s
subscribe/unsubscribe rows were already ✅ and stay that way.
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

**Epic complete (2026-07-23):** all B + H stories, the four required N stories
(N10, N11, N13, N15), and the entire prioritized-optional tail (N12, N14, N16,
N17, N18, N19) are `DONE`. Every story in the index is `DONE`.


### N10 · Persistent sharded entities (CQRS aggregates) — `DONE`
**Note (2026-07-23):** built as scoped, all four bullets.
**Entity mode in `CljPersistentActor`:** the constructor now takes either the
eager props (`:persistence-id` + `:state`, what `persistence/spawn` builds) or
entity props (`:persistence-id-fn` + `:init-fn`), keyed off the presence of
`:persistence-id-fn`. In entity mode both functions are invoked with
`getSelf().path().name()` — the entity id — which is available in the
constructor because the `Actor` trait initializes `self` before the subclass
constructor body runs. `defactor-persistent` gained an `:entity-props` key on
the actor-def holding that (argument-free) props map; `:make-props` is
unchanged, so `spawn`/`spawn-named` behave exactly as before.
**`sharding/start`** dispatches through a new private `entity-props` on
`(:type actor-def)`: `:persistent-actor` → `CljPersistentActor/create` of the
entity props, anything else → the classic `CljActor/create` path, now fed
`(:args opts)` instead of a hardcoded `nil`. Two guards rather than silent
surprises (H7 style): a persistent def with no `:persistence-id` clause throws
at `start` naming the fix, and `:args` combined with a persistent def throws
(its `init` gets the entity id, so args would be silently dropped).
**Also added:** `graceful-shutdown!` (`ShardRegion/gracefulShutdownInstance`)
and `state->map` for `shard-region-state` (`{:shards {shard-id #{entity-id}}
:failed #{}}`, mirroring `stats->map`); `shard-region-state`'s inline
fully-qualified `ShardRegion` reference replaced by a real import.
**Contract worth knowing:** under sharding, `:persistence-id` and `init` receive
the *entity id string*, not the args map they get from `spawn` — the shared
Props has no args to give them. Documented in `start`, in
`defactor-persistent`'s docstring, and in `doc/04-cluster.md`.
Tests (`cluster/sharding_test.clj`, 6 new, all against a real single-node
cluster): `persistent-entity-recovers-after-passivation-test` — deposits/
withdrawals, asserts the derived persistence id (`account-acct-1`), passivates,
then checks the state came back **and** that a *second* `on-recovery-complete`
fired (a recovery counter keyed by persistence id; without it the assertion
would pass just as well against an entity that was never stopped — the first
draft had exactly that hole); `persistent-entities-isolate-state-by-id-test`
(two ids, plus `persist-all` inside a sharded entity);
`persistent-entity-requires-a-persistence-id-test` (both guards);
`start-args-reach-a-classic-entity-init-test`; `shard-region-state->map-test`;
`graceful-shutdown-stops-the-region-test` (single node: nowhere to hand off to,
so the region terminates — observed via DeathWatch with
`test-support/stopped-within?`). Verified failing without the fix by restoring
the old `(CljActor/create ((:make-props actor-def) nil))` line: the persistent
tests fail on every assertion and the `:args` test reports `:greeting nil`.
`docs/specs/sharding-parity-spec.md` updated (three new function rows, `:args`
option row, two new status rows); `doc/04-cluster.md` gained a "Persistent
Entities" section. `lein test` (537 tests, was 531), `lein lint`, `lein check`
(no reflection warnings) all clean.
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

### N11 · Persistence depth — `DONE`
**Note (2026-07-23):** all four bullets built.
**Lifecycle:** `CljPersistentActor` now extends `AbstractPersistentActorWithTimers`
and gained `:post-stop`, `:supervisor-strategy`, `:journal-plugin-id`,
`:snapshot-plugin-id` and `:recovery` props, plus the six timer methods and
watch/unwatch mirroring `CljActor`'s. New `defactor-persistent` clauses:
`on-stop`, `supervision`, `recovery`, `journal-plugin-id`, `snapshot-plugin-id`
(all singletons, wired into the H8 validation set and its error message).
Two clauses were **deliberately not** ported from `defactor`, both for the same
reason — their contract is "return the new state", which for an event-sourced
actor means state no event produced, gone on the next replay: `on-error`
(let the failure reach supervision instead) and `on-restart` (a restart replays
the journal, so `on-recovery-complete` is the hook that fires once the state is
valid again). Documented in the macro's docstring.
**Superclass change was not free** — worth knowing if this is ever touched
again. `AbstractPersistentActorWithTimers` mixes in both `Timers` and
`Eventsourced`, which both define `aroundReceive`/`aroundPreRestart`/
`aroundPostStop`; Scala resolves that by linearization and emits the result as
*synthetic bridge* methods, which javac ignores when computing inherited
members, so the subclass would not compile ("inherits unrelated defaults").
Fixed by overriding the three explicitly and delegating to the **`Eventsourced`**
static forwarders — Eventsourced is the outermost link, verified against the
bridge's own bytecode (`javap -c`). The first attempt delegated to `Timers`,
which enters the chain one link too low and skips the whole recovery/persist
state machine: every persistence test failed with the actor stuck at
`recovering? = true` and no event ever applied. That comment is now in the file.
**Async writes:** three new marker classes next to `PersistAll` — `PersistAsync`
(`persist-async` / `persist-all-async` → `persistAllAsync`), `Defer`
(`defer` → `deferAsync`), and `PersistOps` (`then`, an ordered list that nests).
The actor's `matchAny` now routes through a recursive `runOp`, which is what
makes `(then (persist …) (defer …))` work — a command handler returns one value,
so without a combinator `defer` could never follow a persist, which is its only
real use. A deferred value is handed **back to the command handler** once the
preceding writes complete (sender still in scope, so `reply` works); it is never
journalled and never reaches the event handler.
**Recovery:** `recovery-settings` (public) turns `:none` / `:default` / a map
(`:from-snapshot` — `:latest`, `:none`, a criteria map or a
SnapshotSelectionCriteria — `:to-sequence-nr`, `:replay-max`) / a `Recovery` into
Pekko's `Recovery`, throwing on anything else. `snapshot-criteria` moved up the
file to be usable from it.
**Also fixed, found while testing (a real bug, not scope creep):**
`sharding/passivate` sent `ShardRegion.Passivate` via `core/!`, which falls back
to `noSender` when `core/*current-actor*` is unbound — and the Shard identifies
*which* entity to passivate by the message's sender. Inside a classic entity
`core/*current-actor*` is bound so it worked by luck; inside a persistent entity
it is not, so the shard silently ignored the request and the entity never
stopped. Now sends `(.tell parent msg (.self context))`, correct for any actor
kind. This also exposed that N10's passivation test had been passing for the
wrong reason (the old `core/context` call NPE'd, crashing the actor, and the
crash-restart replayed the journal just as convincingly as a passivation would);
that test now `ask`s `:passivate` and asserts the reply, so a completed command
is proven before the second recovery is counted.
**API note:** `core/self`/`context`/`stop`/timers read `core/*current-actor*`,
which is type-hinted `CljActor` — calling them from a persistent actor throws a
ClassCastException. Added the persistent counterparts (`self`, `sender`,
`context`, `tell`, `stop`, `watch`, `unwatch`, `start-timer`,
`start-single-timer`, `cancel-timer`, `timer-active?`, `cancel-all-timers`) to
`pekko-clj.persistence`, following the precedent `persistence/reply` already
set. Considered instead unifying both actor classes behind a shared interface so
`core`'s helpers work for either — rejected for this story: it means retagging
`core/*current-actor*` and reconciling the stash surface (Pekko's own
`UnrestrictedStash` vs `CljActor`'s `LinkedList`), which is a refactor of `core`
with no bullet asking for it.
**Kondo/cljfmt (per CLAUDE.md):** the three new value clauses added to the
hook's `clause-heads`; the hook's `on-stop`/`on-restart` branch now scopes the
caller's anaphors instead of a hardcoded `[state]`, so `this` resolves in a
persistent `on-stop`. No cljfmt entry needed — `on-stop`/`supervision` already
have one, and the three new clauses take a single value argument, where
cljfmt's default argument alignment is correct.
Tests (`persistence_test.clj`, 12 new): `persistent-on-stop-clause-runs`,
`persistent-supervision-clause-supervises-children` (a child resumed rather than
restarted — verified failing, i.e. the child's state resets, with the clause
removed), `persistent-timers-work`, `persist-async-applies-events`,
`persist-async-events-recover` (proves they are real journal writes),
`defer-runs-after-the-write` (the reply carries the already-applied event),
`deferred-values-are-not-journalled`, `journal-plugin-id-clause-redirects-writes`
(events absent from the *configured* LevelDB journal because they went to
inmem), `recovery-none-skips-replay`, `recovery-replay-max-bounds-replay`,
`recovery-settings-shapes`, `defactor-persistent-rejects-duplicate-lifecycle-clause`.
README's persistence section documents the new write modes and clauses. No
`docs/specs/*` checklist covers persistence. `lein test` (549 tests, was 537),
`lein lint`, `lein check` (no reflection warnings) all clean.
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

### N12 · At-least-once delivery — `DONE`
**Note (2026-07-23):** built as a **sibling** of `defactor-persistent`, not on
top of the N11 Java class as the sketch proposed — deliberately, and worth
recording. `AtLeastOnceDelivery` and the `Timers` that N11 gave
`CljPersistentActor` are two Scala traits, and with no Scala compiler in this
build (`:java-source-paths` only), Java single inheritance cannot combine them:
there is no provided abstract class mixing both. Folding `AtLeastOnceDelivery`
into `CljPersistentActor` instead would also saddle **every** persistent actor
with the trait's unconditional periodic redelivery tick. So N12 is a lean,
self-contained sibling — `CljAtLeastOnceDeliveryActor` (extends
`AbstractPersistentActorWithAtLeastOnceDelivery`) + a new namespace
`pekko-clj.persistence.delivery` with `defactor-delivery` — and N11 /
`CljPersistentActor` are **untouched** (zero regression risk). This matches the
codebase's documented preference (H12, N11 notes) for controlled duplication over
premature shared abstractions; the alternative (a shared `PersistentActorOps`
interface to retag the macro + helpers against) was rejected as the same refactor
N11 already declined, for a prioritized-optional story.
**No linearization dance needed:** unlike `AbstractPersistentActorWithTimers`
(N11's fight), `AbstractPersistentActorWithAtLeastOnceDelivery` exposes concrete
`aroundReceive`/`aroundPreRestart`/`aroundPostStop`, so extending it from Java
compiles without the Eventsourced-forwarder overrides.
**Restart survival is by replay, not snapshots.** `deliver` / `confirm-delivery!`
are called from the **event** handler, so recovery re-runs them for every replayed
event — re-issuing the still-unconfirmed deliveries (Pekko re-derives the same
delivery ids from the restored delivery sequence number) and dropping the
confirmed ones. The outstanding set is therefore rebuilt from the journal with no
delivery snapshot involved, which is why this lean class ships no snapshotting
(adding composite state+delivery snapshots is the obvious future enhancement). To
make that work the delivery event handler is 3-arg `(fn [this state event])` —
`this` is bound during recovery too — where `defactor-persistent`'s is 2-arg.
**API:** clauses `redeliver-interval` (java.time.Duration), `redelivery-burst-limit`,
`warn-after-unconfirmed`, `max-unconfirmed` (each overrides the matching Pekko
method only when supplied), plus `init`/`command`/`event`/`on-recovery-complete`/
`on-stop`/`supervision`. Helpers `deliver` (an ActorRef or ActorPath + a
delivery-id→message fn), `confirm-delivery!`, `num-unconfirmed`, plus
`reply`/`self`/`sender`/`context`/`tell`/`watch`/`unwatch`/`recovering?` typed to
the delivery class via `*current-delivery-actor*`; the class-agnostic
`persist`/`persist-all`/`persist-async`/`persist-all-async`/`defer`/`then` are
re-exported unchanged. `deliver` shadows `clojure.core/deliver`, so the ns does
`(:refer-clojure :exclude [deliver])`. Validation (H8 style) throws at expansion
on an unknown clause, a duplicate singleton, or a missing `:persistence-id`.
**Java suppressions, both intentional and documented in-file:**
`org.apache.pekko.japi.Function` (the only type `AtLeastOnceDeliveryLike.deliver`
accepts from Java — no non-deprecated overload) is deprecated →
`@SuppressWarnings("deprecation")` on `deliverTo`; the inherited Scala trait
accessors have raw generic return types (`SortedMap`, `Option`) → class-level
`@SuppressWarnings("unchecked")`. Reflection-clean.
**Kondo/cljfmt (per CLAUDE.md):** added a `defactor-delivery` hook (delegates to
the shared `rewrite` with `[this state]` anaphors, correct for both command and
event bodies here) and the four config clause-heads to the hook's shared set;
registered `pekko-clj.persistence.delivery/defactor-delivery` in the export
`config.edn`. No cljfmt `:extra-indents` entry needed — the config clauses take a
single value (default indent), and command/event/init/on-recovery-complete/on-stop/
supervision are already covered.
Tests (`persistence/delivery_test.clj`, 5, real LevelDB-backed system,
redeliver-interval 300 ms): `redelivers-until-confirmed-then-stops-test` (a
message arrives ≥2×, `num-unconfirmed` = 1, ack → `num-unconfirmed` polls to 0,
then the received count is stable), `delivery-state-survives-restart-test`
(deliver, poison-pill the sender + `stopped-within?`, re-spawn with the same
persistence id → redelivery resumes from replay and `num-unconfirmed` is rebuilt
to 1, then ack stops it), and three macro-validation guards
(unknown-clause / missing-`:persistence-id` / duplicate-singleton). README gained
an "At-least-once delivery" subsection + a Features bullet; no `docs/specs/*` or
`doc/` guide covers persistence. `lein test` (610 tests, was 605), `lein lint`,
`lein check` (no reflection warnings) all clean.
**Deps:** N11.
`AbstractPersistentActorWithAtLeastOnceDelivery` is a marquee Pekko persistence
feature with no wrapper: reliable actor-to-actor delivery with redelivery +
confirmation, surviving restarts. Design a small clause/API surface
(`deliver`/`confirm-delivery`, `:redeliver-interval`, unconfirmed-warning) on top of
the N11-refactored Java class. Keep it opinionated; document the
delivery-id-in-message pattern.
**Tests:** delivery redelivered until confirmed; confirmation stops redelivery;
state (delivery snapshots) survives restart.

### N13 · Streams consistency fixes + missing operators — `DONE`
**Note (2026-07-23):** every bullet done.
**Consistency:**
- `merge-substreams`/`concat-substreams` now dispatch on SubSource *and* SubFlow
  (and throw a named error on anything else) instead of hinting `^SubSource`,
  which compiled to a checkcast and so ClassCastExceptioned on the `group-by`-of-
  a-Flow path. Verified failing before the fix.
- `source-queue` and `source-actor-ref` both return `{:source … :queue …}` /
  `{:source … :actor-ref …}`, matching `run-source-queue`. **Breaking, no
  deprecation shim**: the tracker asked for a deprecation window, but the old
  shapes were positional vectors in *opposite* orders (`[queue source]` vs
  `[source actor-ref]`) — the very inconsistency the story exists to remove — and
  one function cannot return both a vector and a map. A parallel set of
  `*-map`-suffixed names would have left the confusing pair in place as the
  obvious-looking API. Recorded here as the decision; five call sites in
  `stream_test.clj` updated.
- `zip-with-index` (and the new `zip`/`zip-all`) map Pekko's `japi.Pair` to
  Clojure `[a b]` vectors, so downstream steps need no interop.
- `distinct`/`distinct-by` renamed to `dedupe`/`dedupe-by` — they only ever
  dropped *consecutive* duplicates, which is `clojure.core/dedupe`, not
  `distinct`. Old names kept as `^:deprecated` aliases (clj-kondo reports uses,
  so the two remaining deliberate uses in the test carry `:clj-kondo/ignore`).
  `dedupe` and `interleave` added to the ns `:refer-clojure :exclude`.
- `system-materializer` added (via `SystemMaterializer`, the B9 pattern) and
  documented as the default; `materializer`'s docstring now says plainly that it
  leaks if called per stream. A private `->materializer` lets **every** `run-*`
  (plus `preMaterialize` and `to-actor`) accept an ActorSystem in place of a
  Materializer. Side effect worth noting: `README.md` and `doc/05-streams.md`
  already showed `(s/run … sys)`, which could not work — `run`'s
  `^Materializer` hint compiled to a checkcast — so those examples were
  documenting a form that threw. They are true now.
- `Source/lazily` **is** deprecated in Pekko 1.6 (`Deprecated: true` on the
  module method, found with `javap -v` on `Source$`; the static forwarder on
  `Source` carries no flag, which is why a plain `javap` looks clean).
  `source-lazily` moved to `Source/lazySource`.
**New operators:** `skeep` (the Clojure-shaped `collect` — one `mapConcat`
stage, drop on nil), `zip`, `zip-all`, `interleave`, `prepend`, `or-else`,
`divert-to`, `limit`, `source-never`, `source-unfold-async`, `sink-head-option`,
`sink-last-option`. **`take-last` is not a Source/Flow operator** — the tracker
listed it as "trivial with `op`", but keeping a tail requires knowing where the
stream ends, so Pekko only has `Sink.takeLast(int)`; shipped as `sink-take-last`
with that reason in its docstring.
Tests (`stream_test.clj`, 15 new): `system-materializer-is-shared-per-system`,
`run-fns-accept-an-actor-system`, `merge-substreams-works-through-a-flow`
(the SubFlow regression, verified failing), `concat-substreams-works-through-a-flow`,
`source-queue-and-actor-ref-share-a-map-shape`,
`dedupe-drops-consecutive-duplicates` (pins "consecutive only" and the aliases),
`skeep-maps-and-drops-nils`, `zip-and-zip-all`, `interleave-alternates`,
`prepend-and-or-else`, `divert-to-removes-matching-elements`,
`limit-fails-past-the-bound`, `option-sinks-are-empty-safe` (contrasted against
`sink-head` failing on an empty stream), `sink-take-last-keeps-the-tail`,
`source-never-never-completes`, `source-unfold-async-emits`,
`source-lazily-defers-creation`; the two old `distinct-*` tests renamed and
`zip-with-index-pairs` de-interop'd. README and `doc/05-streams.md` gained the
materializer guidance and the operator list. No `docs/specs/*` checklist covers
streams. `lein test` (566 tests, was 549), `lein lint`, `lein check` (no
reflection warnings) all clean.
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

### N14 · Streams FileIO + StreamConverters — `DONE`
**Note (2026-07-23):** built as scoped, in one new `pekko-clj.stream` section.
**Files:** `source-from-file` (`FileIO/fromPath`, optional chunk size) and
`sink-to-file` (`FileIO/toPath`, optional open-option collection — keywords like
`:append`/`:create`/`:truncate-existing` or `java.nio.file.OpenOption` values via
a private `->open-option`); both coerce a String/`File`/`Path` argument through a
private `->path` and materialize to `CompletionStage<IOResult>`.
**StreamConverters:** `source-from-input-stream`/`sink-to-output-stream` (factory
fn → blocking `java.io.*Stream`, `IOResult` mat value) and their inverses
`sink-as-input-stream`/`source-as-output-stream` (the mat value *is* the blocking
stream; optional read/write timeout coerced through the existing `->duration`).
**ByteString + framing:** `->byte-string` (String→UTF-8 / byte-array / passthrough,
throws otherwise), `byte-string->string`, `byte-string->bytes`; `frame-delimiter`
(`Framing/delimiter`, delimiter as String or ByteString, `FramingTruncation`
ALLOW/DISALLOW) and `lines` (newline framing + UTF-8 decode → String Flow). Added
`io-result->map` (`{:count :success? :error}`, guards `getError` behind
`wasSuccessful`), mirroring `stats->map`/`state->map`.
**Naming deviates from the tracker sketch, deliberately** — the tracker named the
reverse coercion `byte-string->` (a dangling arrow); split into the two clear
`byte-string->string`/`byte-string->bytes` since a single reverse can't cover both
the text and raw-bytes cases. `lines` splits on `\n` only (documented: a CRLF file
leaves a trailing `\r`; use `frame-delimiter` with `"\r\n"`), because
`Framing/delimiter` frames on a fixed byte sequence.
**Reflection:** clean — `->path`/`->byte-string`/`io-result->map` args and the
`sink-to-file` open-option `Set` are hinted; `asInputStream`/`asOutputStream` have
both a `java.time.Duration` and a `FiniteDuration` overload, so the `^Duration`
from `->duration` disambiguates. Composes with `http.response/stream` for file
serving (no new HTTP code — `source-from-file` already yields a ByteString Source;
N15 covers the routing-directive path).
Tests (`stream_test.clj`, 10 new): `byte-string-coercions`,
`io-result->map-shapes-success-and-failure` (`IOResult/createSuccessful` /
`createFailed`), `file-source-and-sink-round-trip` (write IOResult byte count +
`run-mat :both` to read the file's own IOResult), `sink-to-file-append-option-appends`,
`lines-splits-a-multiline-file`, `frame-delimiter-strips-and-splits`,
`source-from-input-stream-reads-bytes`, `sink-to-output-stream-writes-bytes`,
`sink-as-input-stream-bridges-out` (slurp the materialized InputStream),
`source-as-output-stream-bridges-in` (write to the materialized OutputStream).
`doc/05-streams.md` gained a "Files and blocking I/O" section; README's streams
paragraph lists the new surface. No `docs/specs/*` checklist covers streams (same
as N13). `lein test` (582 tests, was 572), `lein lint`, `lein check` (no
reflection warnings) all clean.
**Deps:** N13.
No file or blocking-IO integration at all — a glaring practical gap for a streams
API: `FileIO.fromPath`/`toPath` (source/sink of ByteString with IOResult mat-value),
`StreamConverters.fromInputStream`/`fromOutputStream`/`asInputStream`/`asOutputStream`,
plus ByteString helpers (`->byte-string`, `byte-string->`, a `lines` framing flow via
`Framing.delimiter`). Compose with `http.response/stream` for file serving (ties into
N15's static content).
**Tests:** file round trip incl. IOResult count; framing on a multi-line file;
input-stream source.

### N15 · HTTP routing completion: segment capture, static content, auth — `DONE`
**Note (2026-07-23):** all remaining bullets done (`path-var` was already
delivered by B11).
**Static content:** `from-resource`, `from-resource-directory`, `from-file`,
`from-directory` over `getFromResource`/`getFromResourceDirectory`/
`getFromFile`/`getFromDirectory`. Content types come from Pekko's default
resolver (file extension), and the directory forms resolve the *unmatched* path,
so they nest under `path-prefix` exactly like the B11 route macros. `from-file`
takes a String or a File; its 2-arity coerces to File because the explicit
`ContentType` overload is only declared on `(File, ContentType)`.
**Auth:** `basic-auth` over `authenticateBasic` — realm, an authenticator, and
the inner route; the 401 and the `WWW-Authenticate` challenge are Pekko's.
**The authenticator signature deviates from the tracker's sketch, deliberately.**
The tracker proposed `(fn [user pass] user-or-nil)`, but the password is not
reachable: javadsl's `ProvidedCredentials` exposes only `identifier()` and
`verify(secret)`, and `verify` is a constant-time comparison. Recovering the raw
password would mean re-parsing the Authorization header ourselves and giving up
that property for nothing, so the signature is `(fn [user verify] principal-or-nil)`
where `verify` takes *your* known secret. `bearer-token` is extraction only, on
`header-value-opt` as scoped: nil when the header is absent or uses another
scheme, and the route decides what that means (its docstring points at
`authenticateOAuth2` for a challenge-based flow).
**Marshalling wart — decision: drop the passthrough.** `->json`/`->edn` now
encode every value, strings included, and a pre-encoded body is marked with the
new `marshal/raw-body`. Keeping the passthrough would have meant documenting that
`(resp/json "hello")` emits invalid JSON with nothing to signal it — precisely
the silent-wrong-result class this epic exists to remove — and "is this string
already encoded?" is knowable only to the caller. Named `raw-body` rather than
the tracker's `raw-json` because the same marker serves `->edn`; it is a plain
map with a namespaced key (`::raw`), following B14's reasoning about not
depending on a type surviving anything. **Breaking** for anyone passing
pre-encoded strings; three existing assertions updated
(`marshalling_test.clj` x2, `integration_test.clj` json-content-type-test — the
last one was itself relying on the passthrough).
Tests (`http/integration_test.clj`, 6 new, all against a real bound server):
`from-resource-serves-a-classpath-file` (incl. the resolved `text/css` content
type), `from-resource-directory-resolves-the-unmatched-path` (nested hit + 404
miss), `from-directory-serves-filesystem-files` (plus `from-file`),
`basic-auth-accepts-rejects-and-challenges` (accept / wrong password / unknown
user / no credentials → 401 with the realm in the challenge),
`bearer-token-extracts-or-passes-nil` (incl. case-insensitive scheme and a Basic
header yielding nil), `json-string-bodies-are-encoded-not-passed-through`; plus
`raw-body`/`raw-body?` unit assertions in `marshalling_test.clj`. New test
fixtures `test/resources/public/css/app.css` and `test/resources/public/data.json`.
`doc/06-http.md` gained "Static Content", "Authentication" and a "Marshalling:
strings are values" section recording the decision; the routing ns docstring
lists the new directive groups. No `docs/specs/*` checklist covers HTTP routing
(`routing-parity-spec.md` is about *router* strategies). `lein test` (572 tests,
was 566), `lein lint`, `lein check` (no reflection warnings) all clean.
**Deps:** B11.
- ~~**`path-var`** — extract one path segment as a value, plus
  `path-prefix-var`.~~ **Done in B11** (2026-07-22): both are public in
  `pekko-clj.http.routing`, built on `PathMatchers/segment`, and are what the
  route macros expand to.
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

### N16 · HTTPS + compression + request timeouts — `DONE`
**Note (2026-07-23):** all three parts built, plus the strict-entity timeout
options. Signatures verified with `javap` first (the epic's reflection rule):
`ConnectionContext/httpsServer|httpsClient(SSLContext)`,
`ServerBuilder.enableHttps(HttpsConnectionContext)`,
`Http.singleRequest(req, HttpsConnectionContext)` /
`setDefaultClientHttpsContext`, `withRequestTimeout(java.time.Duration,
Supplier|Function, …)`, `encodeResponse`/`decodeRequest` on `AllDirectives`.
**HTTPS:** new ns `pekko-clj.http.tls` — `ssl-context` (a JSSE `SSLContext` from
a keystore/truststore opts map; each store is a `KeyStore` **or** anything
`clojure.java.io/input-stream` reads — path/File/URL/`io/resource` — so it works
off the classpath; throws if neither store is given) and
`https-server-context`/`https-client-context` (each takes an opts map **or** a
ready `SSLContext`). `bind-server` gained a 5th arg that is now *either* a
`Materializer` (unchanged back-compat, kept by an `instance?` check) *or* an opts
map `{:https … :materializer …}`; the route-vs-function bind logic moved to a
private `bind-route`. Client: `set-default-client-https-context!` plus a
per-request `:https-context` on `request`/`GET`/… (routes to the
`singleRequest(req, ctx)` overload). **Chose keystore-based `SSLContext` over
Pekko's `PekkoSSLConfig` path** — the JSSE route is standard, dependency-free, and
lets the same helper serve key managers (server) and trust managers (client).
**Compression:** `encode-response`/`decode-request` (auto-negotiated) plus
`encode-response-with [coders]` / `decode-request-with coder`; a private `->coder`
maps `:gzip`/`:deflate`/`:none` (or a `Coder`) and **throws** on anything else
(H7 style, no silent fallback). **Timeouts:** `with-request-timeout` (2-arity →
503; 3-arity → a custom `HttpResponse`) and `without-request-timeout`. The
hard-coded strict-entity timeouts are now optional trailing args:
`http/entity->string`/`entity->bytes` (default 10 s) and
`client/response-body`/`response-body-bytes` (default 30 s).
**Two gotchas recorded in the tests:** (1) the built-in client does **not**
auto-decode a gzipped response — the body bytes arrive gzipped with
`Content-Encoding: gzip`, so a caller inflates them (a `GZIPInputStream` in the
test); (2) a route handler runs on a Pekko dispatcher thread where a test's
`binding [*system* …]` is gone, so reaching for the materializer via the dynamic
var NPE'd ("system must not be null") — the strict-entity test reads it from the
route context via `extract-materializer` instead.
Test fixtures: `test/resources/certs/server.p12` (self-signed, SAN
`dns:localhost,ip:127.0.0.1` so loopback hostname verification passes) and
`truststore.p12` (that cert only, for the client to trust). Tests: `tls_test.clj`
(4: store-required throw, build-from-keystore, build-from-truststore, contexts
from opts-or-SSLContext); `routing_test.clj` (3: coder/timeout directives build,
unknown-coder throws); `http/integration_test.clj` (6, real bound server:
`https-round-trip-test`, `gzip-encode-response-test` + the
no-`Accept-Encoding` skip case, `gzip-decode-request-test`,
`request-timeout-returns-503-test` (fast route OK, slow route → 503),
`strict-entity-timeout-option-test`). `doc/06-http.md` gained HTTPS/Compression/
Request-timeout sections; README's HTTP paragraph lists the new directives and
shows the TLS setup. No macro clause added/renamed → no clj-kondo hook / cljfmt
change. No `docs/specs/*` checklist covers HTTP (routing-parity-spec.md is about
*router* strategies). `lein test` (605 tests, was 592), `lein lint`, `lein check`
(no reflection warnings) all clean.
**Deps:** N15.
- **HTTPS:** server (`ConnectionContext/httpsServer` from a keystore opts map,
  `ServerBuilder.enableHttps`) and client (`Http.setDefaultClientHttpsContext` /
  per-request context).
- **Compression:** `encodeResponse` / `decodeRequest` directives.
- **Timeouts:** `withRequestTimeout` directive; expose the strict-entity timeouts
  hard-coded in `http/core.clj:151-153` and `client.clj:21-23` as options.
**Tests:** self-signed round trip; gzip response verified by header + decoded body;
timeout returns 503.

### N17 · Transit record support — `DONE`
**Note (2026-07-23):** built as scoped. `:records` (record classes *or* class
names, both interchangeable) is accepted by `transit-config` — which writes them
to `pekko-clj.serialization.transit.records` and so flows through
`cluster/create-system`'s `:transit-serialization` map — and by new 4-arg
arities of `write-bytes`/`read-bytes` for standalone use; the two sources are
unioned, so a per-call list adds to whatever the system already registers.
Writes reuse transit-clj's own `record-write-handler` (tag = `(.getName klass)`,
rep = the record as a map). Reads do **not** reuse transit-clj's
`record-read-handler`: that one `resolve`s the `ns/map->Rec` var and so needs
the defining namespace already loaded, which is not guaranteed when the class
name arrives as a config string — ours goes through the record's generated
static `create(IPersistentMap)` factory instead (identical semantics, `map->Rec`
just calls it, and it also restores the ext map for non-basis keys).
`->record-class` validates via `(supers klass)` that the class is an `IRecord`
and throws `IllegalArgumentException` for a non-record or an unloadable name —
at `transit-config` time, so a typo fails at system construction, not at the
first cross-node message.
**Handler cache reworked** from "one entry per system" to two levels: the
existing weak-keyed outer map (system → atom) now holds a map from the
explicit record set → handler maps, since handlers are no longer a function of
the system alone. The weak-key invariant is unchanged and now called out at
`ref-handler-entries` (cached values must never strongly reference the system;
they reach it via `WeakReference`).
**Two things the audit note didn't anticipate, both measured:**
(1) `:records` deliberately does **not** bind the record classes themselves in
`serialization-bindings` — without AOT a defrecord class lives in Clojure's
DynamicClassLoader and Pekko's `ReflectiveDynamicAccess` cannot resolve it by
name, so the system fails to start (hit live while writing the end-to-end test).
It binds `clojure.lang.IRecord` instead, which is stable and app-classloader
visible; records were already covered by the default `IPersistentCollection`
binding, so this only matters for a custom `:bindings`.
(2) Unknown-tag failure mode: transit's default returns an opaque
`TaggedValueImpl` that then flows on into user code as if it were the message
(verified — see `neg` check in the session). `read-bytes` now installs a
`:default-handler` that throws `ex-info` naming the tag and pointing at
`:records`. Transit's `ReaderImpl.read` wraps anything a handler throws in a
bare `RuntimeException`, so `read-bytes` unwraps its own marked error
(`::unknown-tag` in the ex-data) and rethrows it unchanged; unrelated
`RuntimeException`s — including the existing `->format`
`IllegalArgumentException` — propagate untouched.
Tests (`serialization_test.clj`, 10 new): round trip when registered (all three
formats), the `unregistered-record-decays-to-a-map` control that proves the
registration is what preserves the type, nested records (in a map/vector/set and
record-in-record), non-basis extra keys, unknown-tag throw + ex-data,
non-record/unknown-name rejection, class-vs-name interchangeability,
`transit-config` shape (records path present, `IRecord` bound, record classes
*not* bound, nothing emitted when `:records` is absent), and two live-system
tests under `serialize-messages = on` — one with `:records` (type survives the
real actor message path) and one without (still decays), which is the negative
control for the config plumbing.
README's Serialization section gained a "Records" subsection; the ns docstring's
"records are NOT handled" note now describes the opt-in instead. No
`docs/specs/*` checklist or `doc/` guide covers serialization (grepped) —
nothing to tick. `lein test` (531 tests, was 521), `lein lint`, `lein check`
(no reflection warnings) all clean.
**Deps:** none — B14 removed the library's own record from the wire (plain-data
envelope) instead of teaching Transit to read records, so this story is now the
*only* place record support would land, not a generalization of it.
User records in messages/events currently fail serialization (documented limitation,
`serialization.clj:33-34`). Add opt-in support: `transit-config {:records [my.ns.Foo …]}`
generating a tagged write handler (record → map + tag from class name) and read
handler (`map->Foo`) per record; same option on `write-bytes`/`read-bytes` for
standalone use. Cache constructed handler maps alongside the existing per-system
cache.
**Tests:** record round trip standalone and through a live system
(`serialize-messages = on`); nested records; unknown-tag failure mode is clear.

### N18 · Router parity leftovers — `DONE`
**Note (2026-07-23):** three of four bullets built; the fourth turned out not to
exist in classic routing.
**Group variants:** `spawn-scatter-gather-group` (`ScatterGatherFirstCompletedGroup`)
and `spawn-tail-chopping-group` (`TailChoppingGroup`) — the route-to-existing-actors
counterparts of the existing pools. Built on the **Java-friendly** constructors
(`(java.lang.Iterable<String>, java.time.Duration[, java.time.Duration])`), found via
javap, so a plain `ArrayList` of paths + `Duration/ofMillis` cross without touching
Scala's immutable `Iterable`/`FiniteDuration` (the only constructor javap shows first
takes those); the path arg is hinted `^Iterable` to disambiguate.
**Pool `:supervisor-strategy` / `:dispatcher`:** every pool spawner (`spawn-pool`,
consistent-hash, scatter-gather, tail-chopping, resizer) now accepts a
`pekko-clj.supervision` strategy (a Pekko `SupervisorStrategy`) via
`.withSupervisorStrategy` and a dispatcher name via `.withDispatcher`. These withers
are declared on each concrete pool class, not the `Pool` interface (same as
`withResizer`), so a private `configure-pool` **macro** applies them on the concrete
constructor *expression* — inlined per cond branch — to stay reflection-free.
`spawn-cluster-pool`'s local pool passes `nil nil` (behavior unchanged).
**Reflection wrinkle worth recording:** `withSupervisorStrategy` has a covariant
bridge (`RoundRobinPool` and `Pool` return types), so an untyped argument left
Clojure unable to pick an overload → reflection, which cascaded to a
target-unknown `withDispatcher`. Hinting the argument `^SupervisorStrategy` (and
`^String` for the dispatcher) resolves both; `lein check` is clean.
**prefer-local-routees — dropped as N/A.** `preferLocalRoutees`/
`withPreferLocalRoutees` exists **only** in `pekko-actor-typed` (Typed's
`GroupRouter`); classic routing — all this library wraps — has no such method on any
pool, group, or cluster-router settings class (grepped the whole
`pekko-actor`/`pekko-cluster` surface at 1.6.0). The classic analogue already
shipped is `:allow-local` (`allowLocalRoutees`) on the cluster routers. Recorded the
spec's long-standing ❌ as ⛔ N/A rather than wrapping a method that isn't there.
Tests (`routing_test.clj`, 6 new): `scatter-gather-group-returns-first-response`,
`scatter-gather-group-requires-timeout`, `tail-chopping-group-returns-response`,
`tail-chopping-group-requires-timeout-and-interval`,
`pool-supervisor-strategy-resumes-routee` (size-1 pool + `resume-decider`: `:inc`
twice → 2, `:boom` throws, `:get` still 2 — verified via a throwaway test that the
**default** pool loses the count here, so the assertion genuinely turns on the
strategy), `pool-dispatcher-option-routes` (routees on
`pekko.actor.default-dispatcher`, always present). `docs/specs/routing-parity-spec.md`
updated (sections 7/9/10 + status table + test list); `doc/03-routing.md` gained
"Scatter-gather and tail-chopping (pool and group)" and "Supervising a pool's
routees" sections. No macro clause added/renamed (the DSL macros are untouched), so
no clj-kondo hook / cljfmt change. `lein test` (588 tests, was 582), `lein lint`,
`lein check` (no reflection warnings) all clean.
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

### N19 · Small parity odds and ends — `DONE`
**Note (2026-07-23):** all five bullets built.
**CircuitBreaker:** `circuit-breaker` gained `:max-reset-timeout`
(`.withExponentialBackoff`) and `:random-factor` (`.withRandomFactor`), applied
via `cond->`. **`:exponential-backoff-factor` intentionally NOT added** — javap
shows Pekko's Java `withExponentialBackoff` takes only the max reset timeout (the
factor is hardcoded 2.0 internally, no overload exposes it); documented that in the
option's docstring rather than shipping an ignored key. `defineFailureFn` is a
*per-call* `BiFunction<Optional,Optional,Boolean>`, not a breaker wither, so
`:failure-fn` became an optional trailing arg on `call`/`call-async` — a fn of
`(result-or-nil, throwable-or-nil) -> truthy`, letting a *successful* result count
as a failure. A private `->failure-bifn` adapts it (`.orElse … nil` unwraps each
Optional).
**Pub-sub:** `count-subscribers` and `get-topics` — blocking asks (`core/<!`,
default 5s) of the mediator's `Count`/`GetTopics`, reached through the
Java-friendly `DistributedPubSubMediator/getCountInstance` /
`getTopicsInstance` statics (the messages are Scala case objects); `get-topics`
maps `CurrentTopics.getTopics` to a Clojure set. Had to import the bare
`DistributedPubSubMediator` class (only its nested `$Subscribe` etc. were imported,
so the static call read as a namespace and failed to compile).
**Sharding:** `stats->map` now surfaces `ShardRegionStats.getFailed`; each region
value changed from a bare `{shard-id count}` map to `{:stats {shard-id count}
:failed #{…}}` (breaking the region-value shape — the only test on it asserted just
`:regions` presence, and the helper is a thin view, so recorded as the shape
decision rather than kept dual).
**Persistence query:** `envelope->map` adds `:metadata` from
`EventEnvelope.getEventMetaData` (`.orElse … nil` → nil when absent).
**Watch:** `core/watch` gained a 2-arity `(watch ref msg)` = Pekko's `watchWith`;
added a tiny `watchWith(ActorRef, Object)` to `CljActor` (mirrors `watch`,
delegating to `getContext().watchWith`). The custom message flows through the normal
handler and is NOT translated to `[:terminated ref]` — documented in both.
Tests (4 new deftests + 2 extended): `circuit-breaker-failure-fn-counts-results-as-failures-test`
(two `:bad` results — successful calls — trip a max-failures-2 breaker, then it
throws `CircuitBreakerOpenException`), `circuit-breaker-backoff-and-random-factor-construct-test`;
`count-subscribers-and-get-topics-test` (single-node cluster: empty topics →
subscribe → topic appears + positive count); `watch-with-custom-message-delivers-marker`
(marker map delivered, not a terminated vector); extended `envelope->map-shape`
(nil metadata on a plain envelope, present via `.withMetadata`) and
`cluster-sharding-stats-test` (polls until the entity counts appear, then asserts
every region value has a map `:stats` and a set `:failed`). Reflection-clean
(hinted `^Optional`, `^CurrentTopics`; `->duration` disambiguates the two
`withExponentialBackoff` overloads). Docstrings updated on every touched fn;
`docs/specs/sharding-parity-spec.md`'s `cluster-sharding-stats` return shape updated
for `:stats`/`:failed`. No `doc/` guide or other `docs/specs` checklist covers
circuit-breaker / pub-sub / persistence-query / death-watch (grepped) — those live
in docstrings. No macro clause added/renamed → no clj-kondo hook / cljfmt change.
`lein test` (592 tests, was 588), `lein lint`, `lein check` (no reflection
warnings) all clean.
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
B14 ─► N10
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
