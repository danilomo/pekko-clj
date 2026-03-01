# Introduction to pekko-clj

pekko-clj wraps [Apache Pekko](https://pekko.apache.org/) actors in an idiomatic Clojure DSL. If you've used Erlang/OTP or Elixir's GenServer, the concepts will feel familiar: actors are lightweight processes that receive messages, manage state, and communicate exclusively through asynchronous message passing.

## Getting Started

### Prerequisites

- JDK 11+
- [Leiningen](https://leiningen.org/)

### Setup

Clone the repository and compile:

```bash
git clone <repo-url>
cd pekko-clj
lein compile
```

Start a REPL:

```bash
lein repl
```

### Your First Actor

```clojure
(require '[pekko-clj.core :refer :all])

;; 1. Define an actor
(defactor greeter
  (handle name
    (reply (str "Hello, " name "!"))
    state))

;; 2. Create an actor system
(def sys (actor-system "my-first-system"))

;; 3. Spawn an actor
(def g (spawn sys greeter nil))

;; 4. Send a message and get a reply
(<! sys g "World")  ;; => "Hello, World!"

;; 5. Shut down
(.terminate sys)
```

## Core Concepts

### Actors

An actor is an isolated unit of computation that:
- Has private **state** (not shared with other actors)
- Processes **messages** one at a time (thread-safe by design)
- Can **spawn** child actors
- Can **send messages** to other actors

### Defining Actors with `defactor`

The `defactor` macro defines an actor with declarative clauses:

```clojure
(defactor my-actor
  "Optional docstring."

  (init [args]
    ;; Runs once at startup. args come from spawn.
    ;; Return value = initial state.
    {:some-key (:value args)})

  (handle :some-message
    ;; Pattern-matched message handler.
    ;; `state` is implicitly available.
    ;; Return value = new state.
    (update state :some-key inc))

  (handle [:complex pattern]
    ;; Vectors, keywords, maps — all core.match patterns work.
    (assoc state :last-pattern pattern))

  (on-stop
    ;; Cleanup when the actor is stopped.
    (println "Actor stopped")))
```

### State Management

State flows naturally through return values:

```clojure
(defactor counter
  (init [args]
    {:count 0})

  ;; Returning a new map replaces the state
  (handle :inc
    (update state :count inc))

  ;; Side-effects + state: reply doesn't affect state
  (handle :get
    (reply (:count state))  ;; reply returns nil
    state))                 ;; explicitly return state to keep it
```

The key rule: **the return value of a handler becomes the new state**. If you need to perform a side-effect (like `reply`) without changing state, return `state` explicitly at the end.

### Message Passing

pekko-clj provides three messaging patterns:

```clojure
;; Tell (fire-and-forget)
(! actor-ref :some-message)

;; Ask (non-blocking, returns a Scala Future)
(<?> actor-ref :get-value)

;; Ask (blocking, returns the value directly)
(<! system actor-ref :get-value)
(<! system actor-ref :get-value 5000)  ;; with 5s timeout
```

`!` works both inside actors (sender = self) and outside (sender = noSender).

### Spawning Child Actors

Actors can create child actors, forming supervision hierarchies:

```clojure
(defactor worker
  (handle msg
    (reply (str "processed: " msg))
    state))

(defactor supervisor
  (init [_]
    {:worker (spawn worker nil)})   ;; spawn child in init

  (handle msg
    (! (:worker state) msg)         ;; delegate to child
    state))
```

### Behavior Switching with `become`

Actors can change their message handler at runtime:

```clojure
(defactor on-state
  (handle :status (reply "ON") state)
  (handle :toggle (become off-state state)))

(defactor off-state
  (handle :status (reply "OFF") state)
  (handle :toggle (become on-state state)))
```

`become` atomically replaces the handler function and state.

## Architecture

pekko-clj has three layers:

1. **Java layer** (`CljActor`, `BecomeResult`, `FnWrapper`) — Bridges Clojure functions into Pekko's actor model. `CljActor` extends `UntypedAbstractActor` and implements `IDeref` for state access.

2. **Core functions** (`pekko-clj.core`) — Provides `!`, `<?>`, `<!`, `spawn`, `reply`, `self`, `sender`, `parent`, `become`, `schedule-once`, and `actor-system`.

3. **`defactor` macro** — Compiles declarative actor definitions into the core function calls and `core.match` dispatch.

The dynamic var `*current-actor*` is bound during message handling, enabling context-aware functions like `self`, `sender`, and `reply` to work without explicit actor references.

## 📚 Step-by-Step Tutorial

Ready to dive deeper? Check out our detailed tutorials documenting every major module within `pekko-clj`:

1.  **[Actors Basics](01-actors.md)** – Detailed exploration of core actors, message passing, and state transitions.
2.  **[Supervision & Error Handling](02-error-handling.md)** – How to supervise children and handle runtime exceptions robustly.
3.  **[Routing](03-routing.md)** – Grouping and pooling multiple actors seamlessly to distribute computational workloads.
4.  **[Clustering, Singletons & Sharding](04-cluster.md)** – Distributed clustered operations spanning multiple nodes efficiently.
5.  **[Reactive Streams](05-streams.md)** – Fluent manipulation and pipelining of continuous data streams utilizing backpressure.
6.  **[HTTP Setup and Routing](06-http.md)** – Setting up responsive HTTP endpoint bindings with succinct routing macros.
