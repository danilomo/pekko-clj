# pekko-clj

An ergonomic Clojure wrapper for [Apache Pekko](https://pekko.apache.org/) actors. Actors should feel native to Clojure — the way GenServer feels native to Elixir.

pekko-clj provides a declarative `defactor` macro with implicit state binding, `core.match` pattern matching, and Erlang-style message passing (`!`, `<?>`, `<!`), so you can focus on message handlers instead of Java interop boilerplate.

## Quick Start

Add to `project.clj`:

```clojure
[pekko-clj "0.1.0-SNAPSHOT"]
```

Define an actor, spawn it, and send messages:

```clojure
(ns my-app.core
  (:require [pekko-clj.core :refer :all]))

(defactor counter
  "A simple counter actor."

  (init [args]
    {:count (or (:start args) 0)})

  (handle :inc
    (update state :count inc))

  (handle [:add n]
    (update state :count + n))

  (handle :get
    (reply (:count state)))

  (handle :reset
    {:count 0}))

(def sys (actor-system "myapp"))
(def c (spawn sys counter {:start 0}))

(! c :inc)
(! c :inc)
(! c [:add 10])
(<! sys c :get)  ;; => 12

(.terminate sys)
```

## Installation

### Leiningen

```clojure
[pekko-clj "0.1.0-SNAPSHOT"]
```

### Dependencies

pekko-clj depends on:

- `org.clojure/clojure 1.11.1`
- `org.clojure/core.match 1.1.0`
- `org.apache.pekko/pekko-actor_3 1.1.3`

## The `defactor` Macro

`defactor` is the primary way to define actors. It compiles declarative clauses into a Pekko actor with `core.match` pattern matching.

### Syntax

```clojure
(defactor name
  "Optional docstring"

  (init [args-binding]
    ;; Called once when actor starts. Return value becomes initial state.
    initial-state)

  (handle pattern
    ;; Message handler. `state` is implicitly bound.
    ;; Return value becomes new state.
    new-state)

  (on-stop
    ;; Cleanup when actor stops.
    cleanup-expr))
```

### Clauses

**`init`** (optional) — Called once before the actor starts processing messages. Receives the spawn arguments. The return value becomes the actor's initial state. If omitted, the spawn arguments are used directly as the initial state.

**`handle`** (required, one or more) — Message handlers. Each `handle` clause specifies a pattern (matched via `core.match`) and a body. The body has access to an implicit `state` binding containing the current actor state. The return value becomes the new state.

**`on-stop`** (optional) — Lifecycle hook called when the actor stops. Used for cleanup.

### Patterns

Patterns in `handle` clauses map directly to `core.match`:

| Pattern | Matches | Example |
|---------|---------|---------|
| `:keyword` | Exact keyword | `(handle :inc ...)` |
| `[:tag arg1 arg2]` | Vector with destructuring | `(handle [:add n] ...)` |
| `{:key val}` | Map with structure | `(handle {:type :cmd} ...)` |
| `symbol` | Anything (catch-all) | `(handle msg ...)` |
| `_` | Anything (wildcard) | `(handle _ ...)` |

### Implicit Bindings

Inside any `handle`, `init`, or `on-stop` body, these are available:

- **`state`** — The current actor state (automatically dereferenced)
- **`(self)`** — This actor's `ActorRef`
- **`(sender)`** — The sender of the current message
- **`(parent)`** — This actor's parent `ActorRef`
- **`(! target msg)`** — Send a message (with self as sender)
- **`(reply msg)`** — Reply to the sender (returns nil, won't affect state)
- **`(spawn actor-def)`** / **`(spawn actor-def args)`** — Spawn a child actor

## API Reference

### Actor System

```clojure
(actor-system)          ;; Create with default name
(actor-system "myapp")  ;; Create with specific name
```

### Spawning Actors

```clojure
;; Top-level (requires an ActorSystem)
(spawn system actor-def)
(spawn system actor-def args)

;; Inside an actor (creates a child actor)
(spawn actor-def)
(spawn actor-def args)
```

When spawning with `args`, the value is passed to the `init` clause. If the actor has no `init` clause, `args` becomes the initial state directly.

### Sending Messages

```clojure
;; Fire-and-forget (tell)
(! actor-ref msg)

;; Non-blocking ask — returns a Scala Future
(<?> actor-ref msg)
(<?> actor-ref msg timeout-ms)

;; Blocking ask — returns the reply value (or nil on timeout)
(<! system actor-ref msg)
(<! system actor-ref msg timeout-ms)

;; Reply to the current message sender (inside a handler)
(reply value)
```

The default timeout for ask operations is 30 seconds, configurable via `*timeout*`:

```clojure
(binding [*timeout* 5000]
  (<! sys actor :get))
```

### Behavior Switching

Actors can change their message handler at runtime using `become`:

```clojure
(defactor happy
  (handle :mood
    (reply "I'm happy!")
    state)
  (handle :toggle
    (become sad state)))

(defactor sad
  (handle :mood
    (reply "I'm sad...")
    state)
  (handle :toggle
    (become happy state)))
```

`become` atomically switches the actor's handler function and state.

### Scheduling

```clojure
;; Inside an actor, schedule a function to run after a delay
(schedule-once (java.time.Duration/ofSeconds 5)
               #(println "delayed!"))
```

### Context Functions

These work inside actor handlers where `*current-actor*` is bound:

| Function | Returns | Description |
|----------|---------|-------------|
| `(self)` | `ActorRef` | This actor's reference |
| `(sender)` | `ActorRef` | Sender of current message |
| `(parent)` | `ActorRef` | Parent actor's reference |

## Examples

### Counter

A stateful counter with increment, decrement, add, get, and reset:

```clojure
(defactor counter
  (init [args]
    {:count (or (:start args) 0)})

  (handle :inc   (update state :count inc))
  (handle :dec   (update state :count dec))
  (handle [:add n] (update state :count + n))
  (handle :get   (reply (:count state)))
  (handle :reset {:count 0}))
```

### Guardian Pattern

A supervisor that delegates messages to a worker:

```clojure
(defactor manager
  (handle msg
    (reply (.toUpperCase msg))))

(defactor guardian
  (init [_]
    (schedule-once (java.time.Duration/ofSeconds 1)
                   #(println "scheduled message!"))
    {:manager (spawn manager)})

  (handle msg
    (! (:manager state) msg)
    state))

;; Usage:
(def sys (actor-system "test"))
(def g (spawn sys guardian))
(<! sys g "hello")  ;; => "HELLO"
```

### Behavior Switching

Two actors that alternate personality on `:toggle`:

```clojure
(defactor happy
  (handle :mood   (reply "I'm happy!") state)
  (handle :toggle (become sad state)))

(defactor sad
  (handle :mood   (reply "I'm sad...") state)
  (handle :toggle (become happy state)))

(def sys (actor-system "mood"))
(def a (spawn sys happy nil))

(<! sys a :mood)    ;; => "I'm happy!"
(! a :toggle)
(<! sys a :mood)    ;; => "I'm sad..."
```

### Chess Game (Complex)

Demonstrates child actors, `spawn` inside actors, `parent`, `sender`, and state transitions:

```clojure
(defactor player
  (handle [:move move]
    (when (:my-turn state) (! (parent) move))
    state)

  (handle [:status status]
    ((:callback state) [:status status])
    (if (= :ok status) (assoc state :my-turn false) state))

  (handle [:game-start color]
    ((:callback state) [:game-start color])
    state)

  (handle [:your-turn move]
    ((:callback state) [:your-turn move])
    (assoc state :my-turn true)))

(defactor game
  (init [{:keys [white-ref black-ref white-cb black-cb]}]
    (let [white (spawn player {:color :white :my-turn true :callback white-cb})
          black (spawn player {:color :black :my-turn false :callback black-cb})]
      (! white-ref white)
      (! black-ref black)
      (! white [:game-start :white])
      (! black [:game-start :black])
      {:white white :black black :game (new-game)}))

  (handle move
    (let [{:keys [white black game]} state
          turn (:turn game)
          current (if (even? turn) white black)
          next    (if (even? turn) black white)
          result  (make-move game move)]
      (if result
        (do (! current [:status :ok])
            (! next [:your-turn move])
            (assoc state :game result))
        (do (! current [:status :not-ok])
            state)))))

(defactor lobby
  (init [_] :empty)

  (handle [:join callback]
    (if (= :empty state)
      {:first {:ref (sender) :cb callback}}
      (do
        (spawn game (merge state {:second {:ref (sender) :cb callback}}))
        :empty))))
```

## Low-Level API

For cases where `defactor` is more structure than you need, you can use the raw function-based API:

```clojure
;; Create an actor from a function and initial state
(new-actor system handler-fn initial-state)

;; Create an actor from a props map
(new-actor system {:function handler-fn
                   :state    initial-state
                   :pre-start  (fn [this] ...)
                   :post-stop  (fn [this] ...)})
```

Handler functions receive `(this, msg)` where `this` implements `IDeref` — use `@this` to get current state. The return value becomes the new state.

## Design Principles

1. **`state` is implicitly bound** in all handlers — no `@this` everywhere
2. **`self`, `sender`, `parent`** are functions, not Java method calls
3. **`!` (tell)** works both inside and outside actors
4. **`reply`** is a side-effect function (returns nil so it doesn't affect state)
5. **`spawn`** is polymorphic — takes a system (top-level) or uses current actor context
6. **`become`** cleanly switches behavior using `BecomeResult` (not vector overloading)
7. **`defactor`** compiles `handle` clauses into `core.match` pattern matching

## License

Copyright © 2025

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
