# pekko-clj Roadmap 4 — Final Review-Hackathon Epic

This is the **living tracker** for the fourth — and intended **final** — epic: serious
bugs found in the 2026-07-30 review hackathon over the three finished epics
(`ROADMAP.md`, `ROADMAP-2.md`, `ROADMAP-3.md`). Same rules as before: **isolated
sessions pick up one story at a time**; this file is the source of truth — keep it
accurate.

## Goal

Unchanged: maximum parity with Apache Pekko (current stable line) while staying a
nice, idiomatic Clojure library. **This epic is deliberately narrow by instruction:
serious bugs only — no N (feature) stories, no hardening batches.** Minor
observations from the audit are recorded in the audit notes below without stories;
fix them opportunistically if a story touches the same file, otherwise leave them.

**Version check (2026-07-30, Maven Central metadata):** Pekko core `1.6.0` is still
the latest stable (matches the pin). **pekko-http `1.4.0` is out** (the project pinned
`1.3.0`) — that gap was story B24, now closed: both pins are at the latest stable.
Pekko `2.0.0-M1..M3` milestones exist for both; not stable, out of scope.

Baseline at audit time (commit `1c18bfc`, epic-3 complete): 675 tests / 1511
assertions / 0 failures, `lein lint` clean, `lein check` zero reflection warnings.

## How to work this epic (every session read this)

1. Pick the next `TODO` story whose **deps are all `DONE`**; within the milestone
   prefer lower-risk stories first.
2. Set it to `PROGRESS` here.
3. Implement it. For B stories: **write the regression test first and verify it
   fails** before fixing (the audit verified behavior via bytecode/metadata but
   wrote no tests — that proof is the fixing session's job). Run `lein test`
   (targeted ns first, then full), then `lein lint`, then the reflection check
   (`lein check` must stay clean for src/).
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
| B23 | Sharded entity ids are URL-encoded, never decoded | Bugs | DONE | — | medium |
| B24 | Bump pekko-http 1.3.0 → 1.4.0 (latest stable) | Bugs | DONE | — | low |

**Definition of done for this epic:** both B stories `DONE`; `doc/`+`docs/` reflect
reality; `lein test` + `lein lint` green, `lein check` reflection-clean for src/.
When done, mark this epic COMPLETE — no further review epics are planned.

## EPIC COMPLETE (2026-07-30)

Both stories `DONE`. Final state: **679 tests / 1543 assertions / 0 failures**,
`lein lint` clean (0 errors, 0 warnings, formatting clean), `lein check`
reflection-free for `src/`. Pins are at the latest stable of both lines
(Pekko `1.6.0`, pekko-http `1.4.0`). No further review epics are planned; the
minor observations below were left unfixed by design and remain the standing
backlog for whoever next touches those files.

---

## Milestone B — Correctness bugs

### B23 · Sharded entity ids are URL-encoded, never decoded — `DONE`
**Done (2026-07-30):** re-verified the audit's bytecode claim independently before
touching anything — `URLEncoder` appears in `Shard.class` (the child name) with the
raw id going to `entityProps`, and `URLDecoder` appears in **no** class in
`pekko-cluster-sharding_3-1.6.0`. Both derivation sites now decode UTF-8:
`sharding/entity-id` and `CljPersistentActor`'s entity branch (which feeds both
`persistence-id-fn` and `init-fn`). `entity-message` coerces the id with `str`
(nil preserved, so the extractor's "no id → drop" behavior is unchanged).
Migration caveat is in the `pekko-clj.cluster.sharding` ns docstring.

Tests written first and confirmed failing pre-fix (9 / 5 / 3 assertion failures
across the three integration tests): `entity-id-decodes-the-url-encoded-actor-name-test`,
`persistent-entity-id-with-special-chars-keeps-one-journal-key-test`,
`entity-message-coerces-the-id-to-a-string-test`, `numeric-entity-id-round-trips-test`.
Suite 675/1511 → **679/1543 green**, `lein lint` clean, `lein check` reflection-free.
Docs: `doc/04-cluster.md` gained an "Entity ids" section;
`docs/specs/sharding-parity-spec.md` gained the `entity-id` row, an
"Arbitrary entity ids" feature row, and the four test entries.

One correction to the audit's framing, found by the tests: `~` is **not** an
unreserved character to `URLEncoder` (it encodes to `%7E`), so the set of ids
affected is slightly wider than "`/`, space, `@`, `:`, `+`, non-ASCII" — only
alphanumerics and `. - * _` pass through untouched.

**Deps:** none. **VERIFIED via bytecode (2026-07-30)** against the pinned
`pekko-cluster-sharding_3-1.6.0` jar: `javap -c` on
`org.apache.pekko.cluster.sharding.Shard` shows the entity actor being created
under `URLEncoder.encode(entityId, "utf-8")`:
```
49: aload_1
50: ldc_w   #1620   // String utf-8
53: invokestatic #1626 // Method java/net/URLEncoder.encode:(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
```
and **no `URLDecoder` call exists anywhere in the sharding classes** — Pekko keeps
the raw id in its own state maps and only encodes the actor *name*, so anyone
deriving the id from the path name must decode it themselves (the classic Akka docs'
canonical persistent-entity snippet is exactly
`URLDecoder.decode(getSelf().path().name(), "utf-8")`).

Two pekko-clj sites derive the entity id from the path name without decoding:

1. `sharding/entity-id` (`cluster/sharding.clj:112-116`) — returns
   `(.name (.path (core/self)))`.
2. `CljPersistentActor` entity mode (`CljPersistentActor.java:93`) —
   `Object entityId = getSelf().path().name();` feeds both `persistence-id-fn`
   and `init-fn`.

Why it's serious: for any entity id containing characters URL-encoding changes
(`/`, space, `@`, `:`, `+`, non-ASCII — completely ordinary in emails, order keys,
dates), the persistence id **silently** differs from the id used at `tell`:
journals are keyed under the encoded form (`order%2F2026` instead of
`order/2026`), `query/events-by-persistence-id` with the natural id finds nothing,
and `(entity-id)` reports the wrong id to handlers. Nothing errors; the data is
just filed under the wrong key.

**Fix:** URL-decode (UTF-8) in both sites — `java.net.URLDecoder/decode` in
`entity-id`, `URLDecoder.decode(name, StandardCharsets.UTF_8)` in the
`CljPersistentActor` entity branch. Identity for plain-ASCII ids, exact inverse of
Pekko's encode otherwise (`URLEncoder`/`URLDecoder` are symmetric, `+`/space
included).

**Fold-in hardening (same story):** `entity-message` (`sharding.clj:74-81`) passes
the id through raw, but `ShardRegion$MessageExtractor.entityId` is declared to
return `String` (checked in the same bytecode pass) — a numeric id sent via
`sharding/tell`/`ask` flows through as a Long and throws a ClassCastException deep
inside Pekko, while `entity-ref` already coerces with `(str entity-id)`. Coerce in
`entity-message` for symmetry.

**Migration caveat (put a sentence in the ns docstring or start's docstring):**
journals written for special-char ids under the old encoded persistence id are
orphaned by this fix — acceptable pre-1.0, but say it out loud.

**Tests:**
- `defactor` entity with id `"a/b c@d"` → `(entity-id)` returns the raw id
  (fails today: returns the encoded form).
- persistent entity with such an id → the `:persistence-id` fn receives the raw
  id, and state survives passivate/revive under the same journal key (fails
  today).
- numeric id via `sharding/tell` round-trips (CCE today).
- plain-ASCII ids behave exactly as before (regression guard).

### B24 · Bump pekko-http 1.3.0 → 1.4.0 (latest stable) — `DONE`
**Done (2026-07-30):** `pekko-http_3` pinned to `1.4.0`; suite unchanged at 675/1511
green, `lein lint` clean, `lein check` reflection-free — no behavior change surfaced,
so no new tests. Version tables updated in `CLAUDE.md`, `README.md`,
`docs/specs/README.md`. **One thing the bump surfaced:** http-core 1.4.0 is built
against `scala3-library_3` `3.3.8` while pekko-actor 1.6.0 still pulls `3.3.7`, so
nearest-wins resolution silently downgraded pekko-http's stdlib. Added
`:managed-dependencies [[org.scala-lang/scala3-library_3 "3.3.8"]]` to `project.clj`
to pin the newer patch (3.3.x is the Scala LTS line — binary-compatible for both);
this also clears Leiningen's "Possibly confusing dependencies" warning. Revisit the
pin whenever pekko core moves off 3.3.7.

**Deps:** none. **VERIFIED (2026-07-30)** against Maven Central metadata
(`repo1.maven.org/.../pekko-http_3/maven-metadata.xml`): stable line is
`1.0.0 … 1.3.0, 1.4.0` (plus `2.0.0-M1`, out of scope). The project pins `1.3.0`
in `project.clj`; the epic goal is parity with the **latest stable** release.
Pekko core is fine: `1.6.0` is still the newest stable there.

Release-notes summary for 1.4.0 (pekko.apache.org, releases-1.4): maintenance
release — ccompat removal (PR890), corrected Content-Length header rendering by
method/status (PR968), `end()` called on Inflaters/Deflaters for earlier resource
release (#1133), Jackson → 2.21.5 and patch-level dependency bumps. **No breaking
changes or deprecations documented.** Built against Pekko 1.1+ jars (we are on
1.6.0), so compatible.

**Fix:** bump every pekko-http artifact in `project.clj` to `1.4.0`; run the full
suite (the HTTP integration tests exercise server, client, TLS, SSE, websockets,
compression — good coverage for a bump); confirm `lein check` stays
reflection-clean (a changed overload surface would show up here first, per the
B7/N1 rule). Update the version tables: `CLAUDE.md` (Version Information),
`README`/`doc/` guides and `docs/specs/` wherever `1.3.0` is named.

**Tests:** existing suite green on 1.4.0 (no new tests unless the bump surfaces a
behavior change — if it does, pin it with a test and record it here).

---

## Audit notes (what was checked, how — 2026-07-30)

Fresh-eyes pass at commit `1c18bfc` over **everything**: full reads of all 29
Clojure namespaces (`core`, `persistence` + `query`/`delivery`/`adapter`,
`stream`, all six `http/*`, `cluster` + `sharding`/`singleton`/`daemon`/`pubsub`/
`ddata`, `routing`, `supervision`, `serialization`, `mailbox`, `circuit-breaker`,
`event-stream`, `test`, `internal/context`, examples) and all 13 Java classes.
Baseline `lein test` run: 675/1511 green. Verification evidence: `javap -c` on the
pinned sharding jar for B23 (encode present, decode absent; the extractor's
null-entityId drop behavior was also confirmed accurate against
`ClusterSharding$$anon$1` — non-envelope messages are safely unhandled, as the
`create-message-extractor` comment claims); Maven Central metadata + release notes
for B24.

Minor observations recorded **without stories** (per this epic's serious-bugs-only
rule; fix opportunistically if a story touches the file):

- `core/reply`, `core/forward`, `core/watch`/`unwatch`, and `spawn`'s
  inside-actor branches still bare-NPE when called outside a handler — H15's
  friendly-error convention covered `self`/`sender`/`parent`/`context`/`stash`/
  timers but missed these.
- `routing/basic-auth` treats a `false` return from the authenticator as a present
  principal (`Optional/ofNullable` — only nil rejects). The docstring documents
  the nil-only contract, but it's un-Clojurey; a `when`-truthy guard would match
  expectations better.
- Combining a persistent actor's `(tagger …)` clause with an N23 event adapter:
  `to-journal` receives the `Tagged` wrapper (actor-side tagging wraps first),
  while `from-journal` sees the bare payload — an asymmetry worth a doc note in
  `persistence.adapter` if anyone hits it.
- `examples/chess.clj`: the lobby passes `{:first … :second …}` to `game`, whose
  init destructures `white-ref`/`black-ref`/`white-cb`/`black-cb` — the example is
  an explicitly stubbed skeleton (`#_"(new-game)"`) and cannot run as shipped;
  align the keys whenever the file is next touched.
- `routing/exception-handler` with a literal map of more than 8 entries loses
  insertion order (hash-map) — handler registration order then becomes undefined.
  Niche; a doc sentence would do.

Everything else checked out clean: actor lifecycle/stash/death-pact/become,
persist/persist-all atomicity and snapshot retention, delivery-actor recovery,
the full streams operator surface, HTTP server/client/routing/TLS/SSE, cluster
membership/singleton/daemon/pubsub/ddata, Transit serialization (including the
handler cache and record round-trips), mailbox, circuit breaker, supervision, and
the testkit.

Known-deferred items **not** re-litigated (see epic-1/2/3 backlogs): typed actors,
durable state, GraphDSL/GraphStage, multi-DC, external/custom shard allocation,
singleton lease, EventsBySlice, cluster-metrics, replicated event sourcing,
Alpakka, more CRDTs, HOCON-builder refactor, Pekko 2.0.0 (revisit when it finals).
