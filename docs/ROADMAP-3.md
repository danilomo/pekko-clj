# pekko-clj Roadmap 3 — Review-Hackathon Epic

This is the **living tracker** for the third epic: bugs and gaps found in the
2026-07-24 review hackathon over the two finished epics (`ROADMAP.md`,
`ROADMAP-2.md`). Same rules as before: **isolated sessions pick up one story at a
time**; this file is the source of truth — keep it accurate.

## Goal

Unchanged: maximum parity with Apache Pekko (current stable line) while staying a
nice, idiomatic Clojure library. This epic covers:

1. **Correctness bugs** — four, each **verified live** during the audit (transcripts
   quoted in the stories).
2. **Hardening / DX** — footguns, convention drift, hostile error paths, doc drift.
3. **Parity gaps** — every upstream API named below was confirmed to exist in the
   pinned versions via `javap` during the audit (the B7/N1 rule: never wrap from
   memory).

**Version check (2026-07-24):** the pins are the latest stable — Pekko core `1.6.0`
(2026-04-17), pekko-http `1.3.0` (2025-10-21). No bump story. Pekko `2.0.0-M3`
milestones exist (deprecated-code removal); not stable, out of scope — but worth
re-checking when 2.0.0 finals, since this library calls no deprecated APIs by
policy and should port easily.

Baseline at audit time (commit `4e03405`): 610 tests / 1354 assertions / 0
failures, `lein lint` clean, `lein check` zero reflection warnings (one benign
performance note, see H16).

## How to work this epic (every session read this)

1. Pick the next `TODO` story whose **deps are all `DONE`**; respect milestone order
   (B → H → N); within a milestone prefer lower-risk stories first.
2. Set it to `PROGRESS` here.
3. Implement it. For B stories: **write the regression test first and verify it
   fails** before fixing (the audit verified the behavior live but wrote no tests —
   that proof is the fixing session's job). Run `lein test` (targeted ns first,
   then full), then `lein lint`, then the reflection check (`lein check` must stay
   clean for src/).
4. Set it to `DONE` with a note; update `docs/specs/*` and `doc/` guides where they
   cover the touched module. **If you add or rename a macro clause, update the
   clj-kondo hook** (`resources/clj-kondo.exports/…/hooks/pekko_clj/`) and the
   `:cljfmt :extra-indents` in `project.clj`.
5. Never leave the build red between stories.

**Status legend:** `TODO` → `PROGRESS` → `DONE` (use `BLOCKED — <reason>` if needed).

---

## Story index

| ID | Title | Milestone | Status | Deps | Risk |
|----|-------|-----------|--------|------|------|
| B19 | `(stream/source [])` throws NPE | Bugs | DONE | — | trivial |
| B20 | Integer status codes produce 500s | Bugs | DONE | — | low |
| B21 | Death-pact not honored for unmatched Terminated | Bugs | DONE | — | medium |
| B22 | Singleton hand-over stalls under `:restart-with-stop` supervision | Bugs | DONE | — | medium |
| H13 | `core/!` silently sends as noSender inside persistent/delivery actors | Hardening | DONE | — | medium |
| H14 | Duration-convention sweep: accept ms-or-Duration everywhere | Hardening | DONE | — | low |
| H15 | Friendly errors for out-of-context calls + missing `:persistence-id` | Hardening | DONE | — | low |
| H16 | Docstring corrections + micro-polish batch | Hardening | DONE | — | trivial |
| H17 | Let persistent/delivery actors spawn as children (ActorRefFactory) | Hardening | DONE | — | low |
| H18 | Odds and ends: dead graph junctions, promise unwrap, client JSON helpers | Hardening | DONE | — | low |
| N20 | Streams operator batch 3 (timeouts, splits, zips, resources) | New | DONE | B19 | medium |
| N21 | Fixed-delay timers (`startTimerWithFixedDelay`) | New | DONE | — | low |
| N22 | Stash for persistent actors | New | DONE | — | low |
| N23 | Persistence event adapters (schema evolution) | New | DONE | — | medium |
| N24 | HTTP: SSE, client IP, async-route directives | New | DONE | — | medium |

**Definition of done for this epic:** all B stories `DONE`; H13–H16 `DONE`
(H17/H18 strongly recommended, cheap); N20–N22 `DONE` (N23/N24
prioritized-optional); `doc/`+`docs/` reflect reality; `lein test` + `lein lint`
green, `lein check` reflection-clean for src/.

**Epic COMPLETE (2026-07-30):** every story `DONE`, including the optional N23/N24.
`lein test` 675/1511 green, `lein lint` clean, `lein check` reflection-clean.

---

## Milestone B — Correctness bugs

### B19 · `(stream/source [])` throws NPE — `DONE`
**Done:** `source` now `(Source/from (or (seq coll) []))` and `source-cycle` feeds
`(or (seq coll) [])` to `.iterator` — empty/nil collections give an empty Source
(cycle surfaces Pekko's own `IllegalArgumentException "empty iterator"` at run
time, not our NPE); 4 regression tests added (test-first, verified failing).
**Deps:** none. **VERIFIED live (2026-07-24):**
```
(s/run-to-seq (s/source []) sys)
;; THROWS java.lang.NullPointerException — "this.iterable$1" is null
;; (same for (s/source nil))
```
Why: `source` (`stream.clj:157-160`) is `(Source/from (seq coll))`, and `(seq [])`
is nil, so `Source.from` gets a null Iterable. An empty collection is a completely
ordinary input for a dynamically-built stream — it must produce an empty Source
that completes immediately, which is exactly what `Source.from` does for an empty
Iterable.
**Fix:** `(Source/from (or (seq coll) []))` (the `seq` call is still wanted — it
also coerces Strings/arrays into something Iterable). Audit the ns's other
`(seq coll)` interop while there: `source-cycle` (`stream.clj:851-855`) has the
same nil-`.iterator` shape, though for `cycle` an empty input is a *user* error —
Pekko itself throws on an empty cycle iterator; make sure it surfaces as Pekko's
own IllegalArgumentException, not our NPE.
**Tests:** `(source [])` → runs to `[]`; `(source nil)` → `[]`; `(source "ab")`
still emits `\a \b`; empty `source-cycle` fails at run time with Pekko's error
(not construction-time NPE).

### B20 · Integer status codes produce 500s — `DONE`
**Done:** `response/->status-code` now resolves integers via `StatusCodes/lookup`
— a registered code (e.g. 201) returns the real StatusCode with correct
reason/isSuccess/allowsEntity (identical to `:created`), and a genuinely
unregistered code falls back to the 3-arg `StatusCodes/custom n "Custom"
"Custom"` (never the 5-arg empty-reason/false/false form that rendered a 500).
Fixes every status path (`routing/complete`, `response/response`, `redirect`) at
the single choke point. Note: `get(int)` throws for unregistered codes, so
`lookup` is the cleaner primitive. Tests: unit flags + real-server round-trip
(int 201 == :created, unregistered 289 keeps its body), both verified failing
first. **Deps:** none. **VERIFIED live (2026-07-24)** against a real bound server:
```
(r/GET "/kw"  [] (r/complete :created "kw-body"))   ;; -> 201 "kw-body"
(r/GET "/int" [] (r/complete 201 "int-body"))       ;; -> 500 "There was an internal server error."
```
Why: `response/->status-code` (`response.clj:44-53`) maps any integer through
`(StatusCodes/custom (int status) "" "" false false)` — reason `""`,
`isSuccess=false`, `allowsEntity=false`. The rendered response for such a status
blows up server-side (500), and even where it doesn't render, the flags are wrong
(`allowsEntity=false` drops bodies; `isSuccess=false` corrupts anything consulting
`.isSuccess`). Every path taking a status — `routing/complete`,
`response/response`, `redirect` — is affected the moment a caller writes `201`
instead of `:created`.
**Fix:** route integers through `StatusCodes/get(int)` (javap-confirmed present in
http-core 1.3.0) which returns the real registered StatusCode; for genuinely
unregistered codes (e.g. 599) fall back to
`(StatusCodes/custom n "Custom" "Custom")` (the 3-arg overload defaults sensible
flags) — never the 5-arg with empty reason/false/false. Keep keywords as the
documented primary form.
**Tests:** end-to-end (real server, integration_test style): int 201 behaves
exactly like `:created` (status + body); an int outside the registry (e.g. 599)
still round-trips with its body; `(resp/response 204 nil)` stays body-less.

### B21 · Death-pact not honored for unmatched Terminated — `DONE`
**Done:** `CljActor` now keeps the raw message alongside the `[:terminated ref]`
translation (`currentRawMessage`); `unhandled` detects when the catch-all passes
back the translated form of the Terminated being handled and delegates
`super.unhandled(rawTerminated)`, restoring Pekko's DeathPactException (default
supervision then stops the watcher). DeathPactException is added to the
onReceive rethrow list so `on-error` never intercepts it. `watchWith` markers are
unaffected (not translated → ordinary UnhandledMessage). `core/unhandled`
docstring corrected. Tests (test-first, key regression verified failing on
HEAD): bare watcher death-pacts and is stopped; handled `[:terminated]` clause
survives; unmatched watchWith marker does not death-pact.
**Deps:** none. **VERIFIED live (2026-07-24):** a `defactor` that `core/watch`es
another actor but has **no** `[:terminated _]` handle clause **survives** the
watched actor's death (the message is published as an UnhandledMessage and life
goes on). Pekko's contract is the opposite: an unhandled `Terminated` throws
`DeathPactException`, failing the watcher — watching without handling is a bug the
runtime is supposed to surface loudly.
Why: `CljActor.onReceive` (`CljActor.java:80-90`) translates `Terminated` to
`[:terminated ref]` *before* matching, so the `defactor` catch-all calls
`.unhandled` with the **translated vector**, and Pekko's
`unhandled(Object)` only special-cases a real `Terminated` instance. The
death-pact branch is unreachable. Bonus doc bug: `core/unhandled`'s docstring
(`core.clj:196-203`) claims the DeathPactException behavior that can never fire.
**Fix:** keep the original message alongside the translation (a
`currentRawMessage` field next to `currentMessage`), and in `CljActor.unhandled`
(or in the defactor catch-all path) detect "this is the translated form of the
current raw Terminated" and delegate `super.unhandled(rawTerminated)` — restoring
`DeathPactException` exactly. `watchWith` messages are unaffected (they are
user messages by design). Decide + document the `on-error` interplay:
DeathPactException is thrown from `unhandled` inside the handler path, so make
sure it is **not** routed to `on-error` (it is not a recoverable handler error —
either add it to the rethrow list next to `Error`/`InterruptedException` in
`onReceive`, or document that on-error can intercept it deliberately; recommend
the rethrow, matching Pekko where DeathPactException is not caught by the actor
itself).
**Tests:** watcher with no terminated-handler → watched actor dies → watcher is
**stopped** (DeathPactException through default supervision; observe via
`test-support/stopped-within?`); watcher **with** a `[:terminated ref]` clause
still works; `watchWith` marker message still arrives as-is when unmatched →
UnhandledMessage (no death pact — same as Pekko, where the custom message is an
ordinary message); `core/unhandled` docstring corrected.

### B22 · Singleton hand-over stalls under `:restart-with-stop` supervision — `DONE`
**Done:** `wrap-with-supervision` now threads the resolved termination-message
through and, for `:restart-with-stop`, applies
`BackoffOnStopOptions.withFinalStopMessage` (a `FnWrapper` over
`#(= % termination-message)`) so the supervisor stops itself once the singleton
stops in response — hand-over completes instead of the onStop supervisor
restarting the actor forever. `:restart-with-backoff` (onFailure) already hands
over on a clean self-stop (pinned by test); the default PoisonPill stops the
supervisor directly (pinned). `start` docstring + singleton-parity-spec updated.
Tests: three single-node cluster hand-over tests; the `:restart-with-stop`
regression verified failing (stalled >8s) on HEAD.
**Deps:** none. **VERIFIED live (2026-07-24)** on a single-node cluster:
```
(singleton/start sys coop-actor {:name "coop-mgr"
                                 :termination-message :bye     ; actor stops itself on :bye
                                 :supervision {:strategy :restart-with-stop
                                               :min-backoff-ms 200 :max-backoff-ms 500}})
(cluster/leave sys (cluster/self-address sys))
;; singleton STILL RUNNING 8s after leave — hand-over never completes
```
(The identical setup **without** `:supervision` stops within B17's 5s window.)
Why: with `:supervision`, `wrap-with-supervision` (`singleton.clj:43-76`) wraps the
actor in a `BackoffSupervisor`, and the manager's child becomes the *supervisor*.
At hand-over the manager sends the termination-message; the supervisor forwards it;
the actor stops itself — and the **onStop backoff supervisor restarts it**, forever.
The manager waits for its child (the supervisor) to terminate, which never happens.
This is the exact trap `BackoffOnStopOptions.withFinalStopMessage` exists for
(javap-confirmed in pekko-actor 1.6.0): a message matching the predicate makes the
supervisor stop itself once the child terminates.
**Fix:** thread the resolved termination-message into `wrap-with-supervision` and,
for `:restart-with-stop`, apply
`.withFinalStopMessage (FnWrapper for #(= % termination-message))` (it takes a
`scala.Function1` — reuse `pekko_clj.actor.FnWrapper`; mind boxing: return a
Boolean). Note the default termination-message (PoisonPill, B17) also flows
through the supervisor — PoisonPill kills the supervisor itself directly, which is
why the default config never stalled; make the fix keep that path working. Check
whether `:restart-with-backoff` (onFailure) needs anything: onFailure supervisors
stop themselves when the child stops *normally*, so it should already hand over —
pin that with a test rather than assuming.
**Tests:** the verified scenario above, per strategy: `:restart-with-stop` +
custom termination-message → singleton stops after `leave` within the window
(fails without the fix); `:restart-with-backoff` + custom message → same;
supervision + default PoisonPill → same. Update `start`'s docstring (the
`:supervision`/`:termination-message` interplay is currently undocumented).

---

## Milestone H — Hardening / DX

### H13 · `core/!` silently sends as noSender inside persistent/delivery actors — `DONE`
**Done:** new `pekko-clj.internal.context` ns holds one dynamic `*current-self*`
(ActorRef or nil, no pekko-clj deps → no cycle). `defactor-persistent` and
`defactor-delivery` command/event/on-stop binders bind it to `(.selfRef this)`
alongside their existing `*current-*-actor*` binding (outer `this-sym` tagged so
the interop stays reflection-free). `core/!` gains a middle branch: use
`*current-actor*` when bound (unchanged fast path), else `*current-self*`, else
noSender — so `sharding/tell`/`graceful-shutdown!` inherit the fix. **Deviation
from the sketch:** `*current-self*` is bound only in persistent/delivery binders,
NOT in `defactor` — classic actors bind `*current-actor*` which `!` already uses,
so binding it there is redundant and needlessly touches the classic hot path;
`*current-self*`'s docstring states this scope. Tests (bug-catch verified by
temporarily dropping the fallback): persistent `!` → entity sender; delivery `!`
→ entity sender; `sharding/tell` from a persistent entity round-trips back;
top-level `!` still noSender. No public API change. **Deps:** none.
`core/!` (`core.clj:38-44`) checks only `core/*current-actor*`; inside a
`defactor-persistent` or `defactor-delivery` body that var is unbound, so `!`
falls back to `(.tell target msg noSender)` — the reply path silently breaks
(replies go to dead letters). This is the **third** appearance of this footgun
class: it broke `sharding/passivate` inside persistent entities (found in N11,
fixed by bypassing `!`), and `sharding/tell` still routes through `!` today, so a
persistent entity calling `sharding/tell` has the same silent wrong-sender.
`persistence/tell` / `delivery/tell` exist, but nothing stops (or warns) the
habitual `core/!`. Epic-2 (N11 note) deliberately rejected unifying the actor
classes behind one interface; this story is the *narrow* version that fixes only
the sender resolution:
**Fix (recommended shape):** a tiny shared namespace (e.g.
`pekko-clj.internal.context`) holding one untyped dynamic var `*current-self*`
(an ActorRef or nil). All three macro-generated binders (`defactor`'s handler,
`build-command-handler`s in persistence + delivery, and their event/lifecycle
binders) additionally bind it to `(.selfRef this)`. `core/!` then becomes: use
`*current-actor*` when bound (unchanged fast path), else use `*current-self*` as
the sender, else noSender. `sharding/tell`/`graceful-shutdown!` inherit the fix
through `!`. No public API changes; the typed per-ns helpers stay.
**Tests:** inside a persistent command handler, `(core/! probe-ref :hi)` delivers
with the entity as sender (probe's `last-sender` = entity ref; fails today);
same inside a delivery actor; `sharding/tell` from inside a persistent entity to
another entity → the other entity's `reply` arrives back (round trip, fails
today); top-level `!` still noSender.

### H14 · Duration-convention sweep — `DONE`
**Done:** every pre-convention duration arg now accepts ms-or-Duration.
`core`/`persistence` each gained a private `->duration` (mirrors stream's) routing
`schedule-once`, `start-timer`, `start-single-timer`; stream's seven pre-N1 ops
(`source-tick`, `throttle`, `delay-each`, `grouped-within`, `take-within`,
`drop-within`, `keep-alive`) drop their `^Duration` param hints and run through
the existing `->duration`; delivery's `redeliver-interval` clause is coerced
inline in the macro (nil-safe, fully-qualified `java.time.Duration` since
generated code can't call a private fn) before the Java-side Duration cast.
Widening only; docstrings say "ms or java.time.Duration" uniformly. Tests: one
per fn family passing a plain number (timers/schedule-once, persistent timers,
delivery redeliver-interval, all seven stream ops). **Deps:** none.
The convention (established across N1/N5/N13/N16) is "durations are a
java.time.Duration **or** milliseconds", but the older surface still requires a
`Duration` and NPEs/mismatches on a number:
- `core.clj`: `start-timer`, `start-single-timer`, `schedule-once` (hinted
  `^Duration` params via CljActor methods; `set-receive-timeout` already accepts
  both — the inconsistency is visible within one ns).
- `persistence.clj` timer fns; `delivery`'s `(redeliver-interval …)` clause value
  (the Java side casts `(java.time.Duration)`).
- `stream.clj` pre-N1 ops: `source-tick`, `throttle`, `delay-each`,
  `grouped-within`, `take-within`, `drop-within`, `keep-alive`.
**Fix:** run every such argument through the ns's `->duration` (add one to core —
private, mirrors stream's). Widening only — passing a `Duration` keeps working, so
nothing breaks. Update docstrings to say "ms or java.time.Duration" uniformly.
**Tests:** one per fn family with a plain number (fails today with a
ClassCastException); existing Duration-passing tests stay green.

### H15 · Friendly errors: out-of-context calls + missing `:persistence-id` — `DONE`
**Done:** (1) each of `core`, `persistence`, `persistence.delivery` gained a
private nil-checking `(current-actor fn-name)` accessor (typed return → hot path
allocation-free and reflection-free); `self`/`sender`/`parent`/`context`/`stash`
+ timer fns now route through it and throw `IllegalStateException` naming the fn
and the rule instead of a bare NPE. (2) `defactor-persistent` now guards a
missing `:persistence-id` at macro-expansion (like `defactor-delivery`);
`sharding/start`'s runtime guard is annotated as belt-and-braces (only trips for
a hand-built actor-def). (3) `validate-actor-clauses` guards a non-seq clause
with `seq?` and reports "unknown clause" instead of "Don't know how to create
ISeq". Tests: out-of-context error per ns (message names the fn), missing-id as a
macroexpand-time test, non-list clause guard. **Deps:** none.
- `core/self`/`sender`/`context`/`stash`/timers (and the persistence/delivery
  counterparts) called outside a handler → bare NPE on the nil dynamic var. Throw
  `IllegalStateException` naming the fn and the rule ("call inside an actor
  handler / init"). Cheapest shape: a private `(current-actor)` accessor per ns
  that nil-checks once; keep the hot path allocation-free.
- `persistence/spawn` (+`spawn-named`, delivery `spawn`) with an actor-def whose
  `:persistence-id` clause is missing → today an NPE from
  `(persistence-id-fn# args#)` inside `:make-props`. `sharding/start` already
  guards this (N10) with a message naming the fix; `defactor-delivery` guards it
  at expansion. Give `defactor-persistent` the same **expansion-time** guard
  (delivery proves the macro has enough information), which also simplifies
  `sharding/start`'s runtime guard into a belt-and-braces check.
- While in `core.clj`: `validate-actor-clauses` `(first clause)` on a non-seq
  clause (a stray keyword/string in the body) throws a bare "Don't know how to
  create ISeq" — guard with `seq?` and report "unknown clause" instead.
**Tests:** each error path asserts the message names the offender (mirror the H8
guard-test style); `(persistence/spawn sys def-without-id {})` case removed —
it becomes a macroexpand-time test.

### H16 · Docstring corrections + micro-polish batch — `DONE`
**Done:** all six items landed — `http/client` `response-headers` now documents the
last-value contract (+ a pin test mirroring `http/core-test`'s twin);
`http/response` `json`/`edn` point at `marshal/raw-body` instead of the removed
string passthrough; `core/unhandled` was already corrected by B21 (verified);
`stream/source-maybe` now says `CompletableFuture<Optional>` not "a Promise";
`schedule-once` documents that `f` runs on the scheduler thread (not the actor),
pointing at `start-single-timer`; and `response-status-keyword`'s `(case (int
code) …)` cleared the last `lein check` performance note — **the tree is now fully
clean (zero reflection AND zero performance warnings).** The `doc/06-http.md`
guide already documented the N15 marshalling change correctly, so no guide edits.
**Deps:** none. All confirmed against source on 2026-07-24; each is a few lines:
- `http/client.clj:212` — `response-headers` says multi-valued headers "return the
  first value"; `into {}` keeps the **last**. H11 fixed the identical wording in
  `http/core.clj` but missed the client twin.
- `http/response.clj:97-98,105-106` — `json`/`edn` docstrings still promise the
  string passthrough N15 deliberately removed ("already-encoded JSON string …
  passed through unchanged"). Point at `marshal/raw-body` instead.
- `core.clj:196-203` — `core/unhandled` DeathPactException claim: fixed by B21
  (do not fix here; just check it happened if B21 landed first).
- `stream.clj:862-867` — `source-maybe` says the materialized value is "a Promise";
  in javadsl it is a `CompletableFuture<Optional>`. Say that.
- `http/client.clj:175` — the one `lein check` performance warning ("case has int
  tests, but tested expression is not primitive"): `response-status-keyword`'s
  `case` on a boxed int. `(case (int code) …)` or hint `response-status`'s return.
- `core/schedule-once` (`core.clj:460-463`) — no warning that `f` runs on the
  scheduler/dispatcher thread, NOT in the actor, so touching `state` from it races.
  Say so, and point at `start-single-timer` (which delivers a message instead) as
  the actor-safe alternative.
**Tests:** the client-headers last-value contract gets the same pin test
`http/core-test` already has for its twin; the rest are doc-only.

### H17 · Let persistent/delivery actors spawn as children — `DONE`
**Done:** `persistence/spawn`/`spawn-named` and `delivery/spawn`/`spawn-named`
retagged `^ActorSystem` → `^ActorRefFactory` (param renamed `system` → `factory`);
`.actorOf` is declared on `ActorRefFactory`, so no call-site change and no
reflection. A persistent/delivery actor can now be spawned as a child via
`(persistence/spawn (core/context) def args)`. Tests: spawn a persistent (and a
delivery) actor from inside a `defactor` handler → it recovers, replies, and a
parent stop tears it down. **Deps:** none.
`persistence/spawn`/`spawn-named` and `delivery/spawn`/`spawn-named` hint
`^ActorSystem` and call `.actorOf` on it — a persistent actor cannot be spawned as
a *child* of another actor, though Pekko allows it and `core`/`routing` already
accept any `ActorRefFactory` (H9 fixed routing for exactly this reason; the hint
is not doc-only, it compiles to a cast). **Fix:** retag to `^ActorRefFactory`
(ActorSystem IS-A ActorRefFactory — no call-site change), accept
`(core/context)` as the factory.
**Tests:** spawn a persistent actor from inside a `defactor` handler via
`(persistence/spawn (core/context) def args)`; it recovers and replies; parent
stop tears it down.

### H18 · Odds and ends — `DONE`
**Decisions recorded:**
- **Dead graph junctions** — DELETED `stream/broadcast`/`balance`/`merge-n`/
  `partition` (unusable without a GraphDSL to wire into; no src/test used them).
  Removed their now-unused imports and the `partition` `:refer-clojure :exclude`.
  `fan-out`/`balance-work` cover the common cases.
- **`completion->promise`** — now unwraps `.getCause`, so `:error` is the real
  exception (matching `await-completion`); added a failure test.
- **`client/get-json`/`post-json`** — chose the breaking-better path: they now
  marshal via `pekko-clj.http.marshalling` (`get-json` parses the response to
  Clojure data; `post-json` takes Clojure `data`, encodes it, parses the reply).
  No callers existed, so the break is free. Integration round-trip test added.
- **Duplicate `then`/`then-apply`** — FOLDED: `client`'s are now re-export `def`s
  of `pekko-clj.http.core`'s (one implementation), keeping the client API stable.

`lein check` stays fully clean; `lein lint` clean.

**Deps:** none. Small items, one session:
- **Dead API:** `stream/broadcast`, `balance`, `merge-n`, `partition`
  (`stream.clj:798-823`) return raw GraphDSL junctions, but the ns exposes no
  GraphDSL to wire them into (full GraphDSL is deliberate backlog) — they are
  unusable as shipped and nothing in src/test uses them beyond construction.
  Either delete them (0.1.0-SNAPSHOT, breaking-OK precedent: B14/N13) or move
  them under an explicit "raw graph stages for interop with hand-written
  GraphDSL" docstring section. Recommend delete; `fan-out` (alsoTo-chain) covers
  the common case and N20 adds `also-to-all`.
- `stream/completion->promise` (`stream.clj:584-592`) delivers the raw
  `CompletionException` under `:error`; unwrap `.getCause` for consistency with
  `await-completion`'s unwrap convention.
- `client/get-json` / `post-json` predate `pekko-clj.http.marshalling` and return
  the raw body string while their names say json. Either parse via
  `marshal/json->` (breaking, better) or rename/deprecate toward an explicit
  `-string` name. Record the decision here.
- `http/core.clj` + `http/client.clj` both define identical `then`/`then-apply`;
  fold client's into a re-export or leave with a cross-reference note (no strong
  preference — record what was done).

---

## Milestone N — Parity

### N20 · Streams operator batch 3 — `DONE`
**Done:** all listed operators landed in `pekko-clj.stream`, each javap-confirmed on
javadsl Source AND Flow (so the `op` macro stays reflection-free on both branches).
Timeout guards `idle-timeout`/`completion-timeout`/`initial-timeout`/
`backpressure-timeout`; splitters `split-when`/`split-after` (SubSource/SubFlow —
recombine with the existing `merge-substreams`/`concat-substreams`); combinators
`also-to-all` (array-hinted to pick the `Graph...` varargs over the `Seq` overload),
`also-to-mat`/`wire-tap-mat` (keep-mat combiner), `merge-all`, `merge-sorted`
(2-arity `compare`, 3-arity comparator fn), `zip-latest`/`zip-latest-with` (Pair →
Clojure vector), `flat-map-prefix`, `concat-lazy`, `initial-delay`; failure/resource
`on-error-complete` (0-arg / Class / predicate arities — predicate is
`java.util.function.Predicate`, not the japi one) and `map-with-resource`
(create/map/close, close emits an optional final element); sources
`source-from-iterator` (fresh iterator per run, coerces a Clojure coll) and
`source-from-java-stream`. **Native replacements:** `dedupe` now calls Pekko's
`dropRepeated()` (behaviour identical; the hand-rolled statefulMapConcat version
retired). `dedupe-by` stays hand-rolled — `dropRepeated`'s only keyed overload takes
an *equality comparator*, not a key fn, so it isn't a clean drop-in. `stateful-map`
gained a 4-arity backed by native `statefulMap` with the onComplete emission hook the
statefulMapConcat 3-arity can't offer (existing 3-arity unchanged). **Semantics
pinned live before documenting:** `flatMapPrefix` consumes the prefix and its Flow
transforms only the *rest* of the stream (verified `[1 2 3 4 5]` prefix-2 identity →
`[3 4 5]`); docstring/test corrected from the wrong "prefix included" guess.
`zipLatest` completes as soon as *any* input completes (tests keep the other side
open via `concat source-never`). Skipped deliberately (niche): `optionalVia`,
`aggregateWithBoundary`. 24 driving tests (timeout ops both ways, split ops through
Source and Flow); `lein test` 667/1485 green, `lein lint` clean, `lein check`
reflection-clean.
**Deps:** B19 (touches the same ns; land the bug fix first).
Every operator below was **javap-confirmed present** on javadsl `Source` in
pekko-stream 1.6.0 (2026-07-24). Same wrapping conventions as N13 (op macro,
Clojure vectors not `japi.Pair`, ms-or-Duration, H7-style throws):
- **Timeout guards** (the big practical gap): `idle-timeout`,
  `completion-timeout`, `initial-timeout`, `backpressure-timeout`.
- **Substream splitters:** `split-when`, `split-after` (return SubSource/SubFlow —
  reuse the `merge-substreams`/`concat-substreams` dispatch, and test the Flow
  path like the N13 SubFlow regression).
- **Combinators:** `also-to-mat`, `wire-tap-mat`, `also-to-all`, `merge-all`,
  `merge-sorted` (takes a Comparator — accept a Clojure fn), `zip-latest`,
  `zip-latest-with`, `flat-map-prefix`, `concat-lazy`, `initial-delay`.
- **Failure/resource:** `on-error-complete` (0-arg, class, and predicate arities),
  `map-with-resource` (create/map/close fns).
- **Sources:** `source-from-iterator` (Creator of Iterator),
  `source-from-java-stream`.
- **Native replacements:** Pekko has `dropRepeated` — replace `dedupe`'s
  statefulMapConcat implementation with it (behavior identical: consecutive
  duplicates; keep `dedupe-by` hand-rolled unless a keyed overload exists — check
  javap first). Also check native `statefulMap` vs our statefulMapConcat-based
  `stateful-map` — the native one has an `onComplete` emission hook ours lacks;
  expose the native one without breaking the existing signature (new fn or a new
  arity; record the decision).
Skipped deliberately (niche, note in ns docstring if asked): `optionalVia`,
`aggregateWithBoundary`.
**Tests:** one driving test per operator (N13 style); timeout ops asserted both
ways (fires vs doesn't); split ops through both a Source and a Flow.

### N21 · Fixed-delay timers — `DONE`
**Done:** `CljActor` and `CljPersistentActor` gained `startTimerWithFixedDelay`
(3-arg) + `startTimerWithFixedDelayAndInitial` (4-arg) pass-throughs to
`TimerScheduler.startTimerWithFixedDelay` (javap-confirmed java.time.Duration
overloads), mirroring the fixed-rate pair. **Choice:** exposed as a sibling fn
`start-timer-fixed-delay` (2 arities) in both `core` and `persistence`, matching
the flat fn style rather than an opts arg. `start-timer`'s docstring now names it
as fixed-RATE and points at the delay variant; both docstrings explain the
difference. H14's ms-or-Duration applies. Tests: fires periodically (both
arities), replaces on same key, cancel works — classic and persistent. **Deps:**
none.
`CljActor`/`CljPersistentActor` only expose `startTimerAtFixedRate`
(`CljActor.java:277-300`), but Pekko's own guidance prefers **fixed-delay** for
most periodic work (fixed-rate bursts to catch up after pauses/GC);
`startTimerWithFixedDelay` javap-confirmed on `TimerScheduler` 1.6.0.
**Fix:** add `startTimerWithFixedDelay` pass-throughs to both Java classes
(2-and-3-arg like the fixed-rate pair); expose as an opts arg or a sibling fn in
`core`/`persistence` (recommend `(start-timer key interval msg {:mode :fixed-delay}`
— no, simpler: a `start-timer-fixed-delay` sibling, matching the existing flat fn
style; record the choice). Document the rate-vs-delay difference in both
docstrings. H14's ms-or-Duration applies.
**Tests:** fires periodically; replaces on same key; cancel works — for both
classic and persistent actors.

### N22 · Stash for persistent actors — `DONE`
**Done:** added `stash`/`unstash`/`unstash-all` fns to `pekko-clj.persistence` and
`pekko-clj.persistence.delivery`, calling Pekko's inherited
`AbstractPersistentActor` `stash()`/`unstash()`/`unstashAll()` (javap-confirmed
public; probed reflection-free). **No Java pass-throughs:** a same-named method on
`CljPersistentActor` would OVERRIDE Pekko's `Eventsourced.stash()` and break its
persist-stash integration — the inherited methods are directly callable, so the
Clojure fns call them. Documented the asymmetry honestly: Pekko's unstash
PREPENDS to the mailbox front, whereas `pekko-clj.core`'s CljActor stash re-sends
to self (TAIL append); StashOverflowException / mailbox stash-capacity noted.
Tests: stash-until-ready round trip → unstash-all reprocesses in order, each
persisting its event (classic persistent and delivery). **Deps:** none.
`defactor-persistent` bodies have no stash: `core/stash` explodes on the CljActor
hint (documented), and `CljPersistentActor` exposes nothing — yet
`AbstractPersistentActor` **has** public `stash()`/`unstash()`/`unstashAll()`
(javap-confirmed; Pekko's Eventsourced integrates the user stash with its
internal persist-stash correctly on its own). Canonical use-case: stash commands
arriving before `on-recovery-complete` state is warm.
**Fix:** pass-throughs on `CljPersistentActor` (`stash`/`unstash`/`unstashAll` —
note Pekko's own stash, NOT CljActor's LinkedList design; the semantics differ:
Pekko unstash **prepends** to the mailbox, CljActor's appends — document this
asymmetry in both places honestly rather than papering over it) + `stash`,
`unstash`, `unstash-all` fns in `pekko-clj.persistence` bound via
`*current-persistent-actor*`. Mirror into `persistence.delivery`
(`AbstractPersistentActorWithAtLeastOnceDelivery` inherits the same trait).
Boundedness: Pekko's stash capacity comes from mailbox config — surface the
`StashOverflowException` behavior in the docstring.
**Tests:** stash-until-recovery-complete round trip; unstash-all order; a
persistent actor stashing and then persisting still applies events in order.

### N23 · Persistence event adapters — `DONE`
**Done:** new `pekko_clj.actor.CljEventAdapter` (implements `EventAdapter`, an
`(ExtendedActorSystem)` ctor) resolves `to-journal`/`from-journal`/`manifest` — each
optional, a `"ns/var"` string — from a **fixed** config root
`pekko-clj.persistence.adapter`, plus a `pekko-clj.persistence.adapter/config` HOCON
builder (mirrors `mailbox/priority-mailbox-config`) that also emits the
`event-adapters` registration + `event-adapter-bindings` (default binding
`java.lang.Object`). **Deviation from the sketch's `<name>` path:** javap of
`EventAdapters$.instantiate` confirmed Pekko instantiates an adapter *name-blind* —
it passes only the `ExtendedActorSystem`, never the adapter's own config section
(unlike a mailbox's `(Settings, Config)`), so the instance cannot know its logical
binding name. Hence ONE adapter per ActorSystem reading a fixed root; branch inside
the fns on event shape / manifest instead of registering several. Documented
honestly in the ns + Java docstrings. **`fromJournal` return contract:** `nil` →
drop (`EventSeq.empty`), a value tagged by `(adapter/many coll)` → one event per
element (split), anything else → single — even a Clojure vector, so
`[:v1 x]` → `[:v2 x default]` is never mis-split. The `EventSeq` is built in **Java**
(the `create(Object…)` vs `create(Seq)` overload is ambiguous from Clojure); the
split marker is metadata (`::split`) checked via `IMeta`/`RT.toArray`. **LevelDB
honors `event-adapters`** — verified live by the recovery test (not just asserted).
Tests (4/13): config shape + `:journal-plugin` guard; `[:v1 x]` → `[:v2 x :default]`
upcast on recovery; one-to-many split; manifest round trip. `lein test` 671/1498
green, `lein lint` clean, `lein check` reflection-clean.
**Deps:** none. Optional but high-value for real CQRS apps.
No wrapper for Pekko's `EventAdapter`/`ReadEventAdapter`/`WriteEventAdapter` —
the schema-evolution seam (upcasting old journal events, splitting one event into
many via `EventSeq`, tagging at the adapter layer). Journals register adapters by
**class name in config** (`pekko.persistence.journal.<plugin>.event-adapters`), so
the shape is a config-instantiated Java bridge resolving Clojure fns — the
`CljPriorityMailbox` pattern exactly:
- `CljEventAdapter` Java class: `(ExtendedActorSystem)` ctor, reads
  `pekko-clj.persistence.adapter.<name>.{to-journal,from-journal,manifest}` config
  paths naming `ns/var` fns; `fromJournal` returns `EventSeq` (map a Clojure
  return of one-value / seq / nil onto `EventSeq.single/create/empty`).
- Clojure helper `pekko-clj.persistence.adapter/config` generating the
  registration + binding HOCON (mirrors `mailbox/priority-mailbox-config`).
**Tests:** an adapter that upcasts `[:v1 x]` → `[:v2 x default]` on read: persist
v1 events, restart with the adapter bound, recovered state reflects v2; a
one-to-many `EventSeq` split; manifest round trip. LevelDB honors
`event-adapters` config (verify early — if it does not, document which test
journal to use before sinking time).

### N24 · HTTP: SSE, client IP, async-route directives — `DONE`
**Done:** all three landed in `pekko-clj.http.routing`, every signature
javap-confirmed on javadsl first (B11's lesson). **SSE:** `sse` completes a route
via `complete(StatusCode, Source, EventStreamMarshalling/toEventStream)`; a public
`->server-sent-event` coerces each element (a `ServerSentEvent`, a string, or a
`{:data :event :id :retry}` map → the 4-arg `ServerSentEvent/create` with
`Optional`/`OptionalInt`) and is `smap`-ped over the source. Pairs with
`without-request-timeout`; client side (EventSource) skipped — the streaming client
+ `stream/lines` framing already reads it, shown in the test. **Client IP:**
`extract-client-ip` over `extractClientIP`, passing the peer IP **string** (nil when
unknown) rather than the raw `RemoteAddress` — friendlier and the 90% case; the
docstring names the required `remote-address-attribute = on` and the test pins both
on (→ `127.0.0.1`) and off (→ nil). **Async routes:** `on-success`
(`onSuccess(CompletionStage, Function)`) binds the value; `on-complete`
(`onComplete(CompletionStage, Function<Try,Route>)`) hands the fn a Clojure map
`{:success true :value v}` / `{:success false :error throwable}` (the `scala.util.Try`
unwrapped in the wrapper) so a failed actor ask becomes a chosen response, not a bare
500. `core/<?>` already returns a `CompletableFuture`, so it feeds them directly. 4
integration tests (SSE framing, on-success actor round trip, on-complete
success+failure, client-ip on/off); `doc/06-http.md` gains a section for each.
`lein test` 675/1511 green, `lein lint` clean, `lein check` reflection-clean.
**Deps:** none. Optional. Verify each signature with javap before wrapping (B11's
lesson: route-layer code can look fine and never match).
- **Server-sent events:** javadsl `EventStreamMarshalling` + `ServerSentEvent` —
  a `(sse source-of-events)` completion directive taking a stream Source of
  `{:data … :event … :id … :retry …}` maps → `ServerSentEvent.create`; pairs with
  `without-request-timeout` (N16). Client side optional (EventSource) — record
  scope decision.
- **Client IP:** `extract-client-ip` over `extractClientIP` (nil/absent semantics:
  it depends on `remote-address-attribute` being enabled — document the config
  requirement in the docstring, don't let it silently return nothing).
- **Async routes:** `on-success` (`onSuccess(CompletionStage, Function)`) and
  `on-complete` (result-or-throwable fn) so handlers can `<?>` an actor and build
  the Route from the reply without blocking — today only `complete-future`
  (response-only) exists, forcing either blocking or raw HttpResponse building.
**Tests:** integration-test style against a bound server: SSE stream consumed with
the streaming client + framing (N14's `lines`/`frame-delimiter` on the chunked
body); on-success driving an actor `<?>` round trip; client-ip with the attribute
enabled in the test config.

---

## Audit notes (what was checked, how — 2026-07-24)

Fresh-eyes pass at commit `4e03405` over: all 13 Java classes (full read); full
reads of `core.clj`, `persistence.clj`, `persistence/{query,delivery}.clj`,
`stream.clj`, all six `http/*.clj`, `cluster/{sharding,singleton}.clj`,
`serialization.clj`; targeted reads/greps of `cluster.clj`, `cluster/ddata.clj`,
`routing.clj` and the small support namespaces (those had the most recent epic-2
rework + tests, so got the lighter pass). Live verification runs:
`(source [])` NPE, death-pact deviation, int-status 500 (real server), singleton
`:restart-with-stop` stall (real single-node cluster, 8s poll) — all four
reproduced; upstream API existence for every N-story checked via `javap` against
the pinned jars; latest-stable versions checked against pekko.apache.org
(2026-07-24). Baseline suite/lint/reflection all green before and after (audit
made no code changes).

Known-deferred items **not** re-litigated (see epic-1/2 backlogs): typed actors,
durable state, GraphDSL/GraphStage, multi-DC, external/custom shard allocation,
singleton lease, EventsBySlice, cluster-metrics, replicated event sourcing,
Alpakka, more CRDTs, HOCON-builder refactor.

Test-suite hygiene: no disabled/skipped tests; the remaining fixed sleeps are the
deliberate ones H2 documented. Deeper test-quality auditing was not repeated this
pass (epic-2 did it); the B-story regression tests above close the specific gaps
this audit exposed (no empty-collection stream test, no death-pact test, no
int-status integration test, no supervised-singleton hand-over test).
