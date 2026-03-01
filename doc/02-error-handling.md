---
title: Supervision & Error Handling
---
# Supervision & Error Handling

Actors embrace the **"Let It Crash"** philosophy. When an actor fails because of an exception, it is the parent actor's responsibility to figure out what to do. This logic is called **Supervision**.

In `pekko-clj`, supervision is implemented through supervision strategies attached to routers or singletons, or more generally using the functions provided in the `pekko-clj.supervision` namespace.

## Supervision Directives

When a child actor crashes, the supervisor uses a "decider" to determine which action (directive) should be taken:

- `:resume` - The child actor ignores the message that caused the failure, keeps its current state, and continues processing the next messages.
- `:restart` - The child actor is restarted. Its state is cleared and initialized afresh (via `init`).
- `:stop` - The child actor is permanently stopped.
- `:escalate` - The parent doesn't know how to handle the failure and passes it to its own supervisor.

## Supervision Strategies

There are two primary paradigms for dealing with failure among multiple children:

1. **One-For-One Strategy**: If one child crashes, the supervisor's decision is applied *only* to that failing child.
2. **All-For-One Strategy**: If one child crashes, the supervisor's decision is applied to *all* its children simultaneously. This is useful when child actors are tightly coupled and cannot function independently if one dies.

### In `pekko-clj`

You can construct a strategy by supplying a decider function (which translates an `Exception` into a directive) and optional configurations like `:max-retries` and `:within-ms`.

```clojure
(ns my-app.supervisor
  (:require [pekko-clj.supervision :as sup]))

;; A custom decider
(defn my-decider [ex]
  (cond
    (instance? ArithmeticException ex) :resume
    (instance? NullPointerException ex) :restart
    :else :escalate))

;; Create a one-for-one strategy
(def retry-strategy
  (sup/one-for-one
    {:max-retries 10   ;; Max 10 restarts
     :within-ms 60000} ;; within 1 minute
    my-decider))

;; The namespace also provides handy built-in simple deciders:
;; sup/restart-decider, sup/resume-decider, sup/stop-decider, sup/escalate-decider

(def simple-strategy
  (sup/all-for-one sup/restart-decider))
```

*Note: You would generally pass these strategies as configuration options when starting specific components (like cluster singletons or custom supervisors that deploy child routers).*

### Contrast with Scala (Pekko Typed)

With Pekko Typed in Scala, supervision is applied by wrapping the child actor's behavior in `Behaviors.supervise` rather than delegating entirely to the parent's classical untyped strategy object.

```scala
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.SupervisorStrategy
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object Worker {
  def apply(): Behavior[String] = 
    Behaviors.supervise[String] {
      Behaviors.receiveMessage { msg =>
        if (msg == "crash") throw new RuntimeException("Boom!")
        println(s"Processed $msg")
        Behaviors.same
      }
    }
    // Define the strategy wrapping the behavior
    .onFailure[RuntimeException](
      SupervisorStrategy.restart
        .withLimit(maxNrOfRetries = 10, withinTimeRange = 1.minute)
    )
}
```

**Key Differences:**
1. **Delegation vs. Self-Wrapping**: In classic Pekko (which `pekko-clj` leverages), supervision logic belongs to the *parent* managing the hierarchy. It decides how children fail. In Pekko Typed (Scala), an actor wraps *its own* behavior declaring how it should be supervised when created.
2. **Configuration API**: `pekko-clj` uses Clojure maps and standard functions like `cond` to construct deciders dynamically, preserving typical data-oriented Clojure workflows.
3. **Scope**: Scala's `SupervisorStrategy.restart` usually needs an explicit exception type `onFailure[T]` whereas in Clojure you route using `instance?` checking arbitrary base Java classes inside a single unified function.

*In the next section, we will explore routing computation across pools of actors.*
