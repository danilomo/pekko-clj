---
title: Actors Basics
---
# Actors Basics

Welcome to the first part of the `pekko-clj` tutorial! In this section, we'll cover the fundamental concepts of actors: how to define them, manage their state, and send messages. We will also contrast the `pekko-clj` approach with the native Scala API for Apache Pekko.

## What is an Actor?

An actor is an isolated unit of computation that:
- Has private **state** (not shared with other actors directly)
- Processes **messages** sequentially (eliminating the need for locks and making them thread-safe)
- Can **spawn** child actors to delegate work
- Can **send messages** to other actors

## Defining an Actor

In `pekko-clj`, actors are defined declaratively using the `defactor` macro. This macro provides automatic state management, implicit context bindings (like `state`, `sender`, and `self`), and pattern matching via `core.match`.

### In `pekko-clj`

```clojure
(ns my-app.core
  (:require [pekko-clj.core :refer :all]))

(defactor counter
  "A simple counter actor."

  (init [args]
    ;; Initialization clause. Called once when the actor starts.
    ;; The return value becomes the initial state.
    {:count (or (:start args) 0)})

  (handle [:inc amount]
    ;; Message handler matching a vector pattern `[:inc amount]`.
    ;; `state` is implicitly available and represents the current state.
    ;; The return value becomes the NEW state.
    (update state :count + amount))

  (handle :get
    ;; Message handler matching the exact keyword `:get`.
    ;; We use the `reply` function to send a reply back to the sender.
    (reply (:count state))
    ;; Since this is a side-effecting operation that doesn't change the state,
    ;; we need to explicitly return the current `state`.
    state)

  (on-stop
    ;; Cleanup hook called when the actor stopped.
    (println "Counter actor stopped! Final state:" state)))
```

### Contrast with Scala (Pekko Typed)

In native Scala using Pekko Typed, actor state is typically managed by recursively returning new behavior functions, and messages are strongly typed via Algebraic Data Types (ADTs).

```scala
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object Counter {
  // Define the message protocol
  sealed trait Command
  final case class Inc(amount: Int) extends Command
  final case class Get(replyTo: ActorRef[Int]) extends Command

  // Factory for the initial behavior
  def apply(start: Int = 0): Behavior[Command] = counter(start)

  private def counter(count: Int): Behavior[Command] = 
    Behaviors.receiveMessage {
      case Inc(amount) =>
        // Return a new behavior with the updated state
        counter(count + amount)
      case Get(replyTo) =>
        // Reply using the provided replyTo address
        replyTo ! count
        // Maintain the same behavior/state
        Behaviors.same
    }
}
```

**Key Differences:**
1. **Dynamic Typing vs. ADTs**: `pekko-clj` uses dynamic Clojure data structures and `core.match` for dispatch, whereas Scala uses sealed traits and case classes. This reduces boilerplate considerably in Clojure.
2. **Implicit State vs. Recursive Behaviors**: `pekko-clj` automatically manages state transitions using the return value of `handle` clauses. In Scala, you explicitly return a new behavior enclosing the updated state parameters.
3. **Implicit Sender vs. explicitly passed `replyTo`**: In `pekko-clj`, you can reply using `(reply msg)`, which uses an implicitly bound `sender` context under the hood (similar to Erlang or classic Pekko). In Pekko Typed, you must include an explicit `ActorRef` in your message protocol to know who pushed the message.

## Creating and Spawning Actors

Before you can instantiate an actor, you need an **ActorSystem** – the environment that holds and executes all actors.

### In `pekko-clj`

```clojure
;; Create the system
(def sys (actor-system "my-system"))

;; Spawn the actor and get an ActorRef back
(def my-counter (spawn sys counter {:start 10}))
```

Child actors can be easily spawned from within another actor without needing to pass the system explicitly. The implicit context of the `parent` actor will be used instead.

```clojure
(defactor supervisor
  (init [_]
    ;; Spawns a child actor named 'worker' under the supervisor
    {:child (spawn worker nil)}))
```

### Contrast with Scala

In Scala Pekko Typed, you normally bootstrap the system with a root behavior directly. Adding children dynamically is generally performed via explicit factory behaviors like `Behaviors.setup` calling `context.spawn`.

```scala
val system = ActorSystem(Counter(10), "my-system")
// In Typed, the system itself can act as the reference to the root actor
```

## Sending Messages

`pekko-clj` provides three main ways to communicate:

1. **Tell (`!`)**: Fire and forget.
2. **Blocking Ask (`<!`)**: Wait for a reply and return the value directly.
3. **Future Ask (`<?>`)**: Send a message and return a Scala `Future`.

### Tell (Fire-and-forget)

```clojure
;; Sends exactly one message and returns nil. Does not wait.
(! my-counter [:inc 5])
```

### Blocking Ask

Blocks the current thread until the actor replies or the timeout expires.

```clojure
;; Send message :get, wait for the result
(let [current-count (<! sys my-counter :get)]
  (println "The count is:" current-count))

;; With explicit timeout in milliseconds
(let [current-count (<! sys my-counter :get 5000)]
  (println "The count is:" current-count))
```

### Future Ask

If you prefer non-blocking concurrency outside of handlers, `<?>` returns a native Scala `Future` that you can map or await using interop tools like `Await/result`.

```clojure
(def future-reply (<?> my-counter :get))
```

*In the next section, we will explore how supervision and error handling work in `pekko-clj`.*
