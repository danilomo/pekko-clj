# Spec 2: Core Clojure API

**Prerequisites**: spec1 (Java foundation) must be completed first.
**Files to modify**:
- MODIFY `src/main/clj/pekko_clj/core.clj`

---

## Goal

Replace the current macro-based API (`!`, `reply`, `spawn` macros that capture
`this`) with **plain functions** powered by a `*current-actor*` dynamic var. This
makes the API work both inside and outside actor contexts, and removes the hidden
`this` capture magic.

## 2.1 Add imports

Add these to the `:import` in the `ns` form:

```clojure
(ns pekko-clj.core
  (:require [clojure.core.match :as m])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.pattern Patterns]
           [pekko_clj.actor CljActor BecomeResult FnWrapper]))
```

## 2.2 Dynamic var

```clojure
(def ^:dynamic *current-actor*
  "Bound to the current CljActor instance during message handling.
   Used by !, reply, sender, self, parent, spawn."
  nil)
```

## 2.3 Context accessor functions

These only work inside an actor context (when `*current-actor*` is bound):

```clojure
(defn self
  "Returns the ActorRef of the current actor."
  []
  (.selfRef *current-actor*))

(defn sender
  "Returns the ActorRef of the message sender."
  []
  (.senderRef *current-actor*))

(defn parent
  "Returns the ActorRef of the current actor's parent."
  []
  (.parentRef *current-actor*))
```

## 2.4 `!` (tell) — context-aware

```clojure
(defn !
  "Send a message to an actor. Inside an actor context, sender is self.
   Outside, sender is noSender."
  [target msg]
  (if *current-actor*
    (.tell *current-actor* target msg)
    (.tell target msg ActorRef/noSender)))
```

## 2.5 `reply`

```clojure
(defn reply
  "Reply to the sender of the current message. Returns nil (so it doesn't
   affect handler return value / state)."
  [msg]
  (.reply *current-actor* msg)
  nil)
```

Important: explicitly returns `nil` so that `(handle :get (reply val))` doesn't
accidentally set state to whatever `.reply` returns.

## 2.6 `actor-system`

```clojure
(defn actor-system
  "Create a new ActorSystem."
  ([] (ActorSystem/create))
  ([name] (ActorSystem/create name)))
```

## 2.7 `spawn` — polymorphic

```clojure
(defn- make-props
  "Given an actor-def map and args, produce a CljActor Props."
  [actor-def args]
  (CljActor/create ((:make-props actor-def) args)))

(defn spawn
  "Spawn a new actor.

   Inside actor context:
     (spawn actor-def)
     (spawn actor-def args)

   Top-level:
     (spawn system actor-def)
     (spawn system actor-def args)"
  ([actor-def]
   (spawn actor-def nil))
  ([first-arg second-arg]
   (if (instance? ActorSystem first-arg)
     ;; (spawn system actor-def) — top-level, no args
     (spawn first-arg second-arg nil)
     ;; (spawn actor-def args) — inside actor context
     (let [props (make-props first-arg second-arg)]
       (.actorOf (.getContext *current-actor*) props))))
  ([system actor-def args]
   (.actorOf system (make-props actor-def args))))
```

## 2.8 `<?>` (ask)

```clojure
(def ^:dynamic *timeout* 30000)

(defn <?>
  "Send a message and expect a reply. Returns a Scala Future.
   Use @(<?> actor msg) with a FnWrapper callback, or see <! for blocking."
  ([target msg]
   (<?> target msg *timeout*))
  ([target msg timeout]
   (Patterns/ask target msg (long timeout))))
```

## 2.9 `<!` (blocking ask)

```clojure
(defn <!
  "Send a message and block for the reply. Returns the reply value."
  ([target msg]
   (<! target msg *timeout*))
  ([target msg timeout]
   (let [result (promise)
         future (<?> target msg timeout)]
     (.onComplete
      future
      (FnWrapper/create
       (fn [try-result]
         (deliver result
                  (if (.isSuccess try-result)
                    (.get try-result)
                    (.get (.failed try-result))))))
      (.getDispatcher (.system (.getContext *current-actor*))))
     (deref result timeout nil))))
```

Note: `<!` requires an execution context. Inside an actor, use the system dispatcher.
For a simpler top-level version that doesn't need `*current-actor*`, we can add a
variant that takes the system:

```clojure
(defn <!
  "Send a message and block for the reply."
  ([target msg]
   (<! target msg *timeout*))
  ([target msg timeout]
   (let [result (promise)
         system (if *current-actor*
                  (-> *current-actor* .getContext .getSystem)
                  nil)
         future (<?> target msg timeout)]
     (if system
       (.onComplete
        future
        (FnWrapper/create #(deliver result (if (.isSuccess %) (.get %) nil)))
        (.getDispatcher system))
       ;; Fallback: spin-wait on the Scala future
       (deliver result (.. future (result (scala.concurrent.duration.Duration/apply timeout "ms"))
                                 (get))))
     (deref result timeout nil))))
```

This is tricky — keep `<!` simple for now. Can be refined later. A minimal version:

```clojure
(defn <!
  "Blocking ask. Requires an actor-system for the execution context."
  ([system target msg]
   (<! system target msg *timeout*))
  ([system target msg timeout]
   (let [result (promise)
         future (<?> target msg timeout)]
     (.onComplete
      future
      (FnWrapper/create #(deliver result (.get %)))
      (.getDispatcher system))
     (deref result timeout nil))))
```

## 2.10 `become`

```clojure
(defn become
  "Switch the current actor's behavior to another defactor's handler.
   Returns a BecomeResult that the runtime interprets."
  [actor-def new-state]
  (BecomeResult/of (:receive actor-def) new-state))
```

## 2.11 Keep backward-compat `new-actor`

Keep the existing `new-actor` function as-is for users who prefer the raw
function-based approach:

```clojure
(defn new-actor
  "Create an actor from a raw function and initial state (low-level API)."
  ([src props] (.actorOf src (CljActor/create props)))
  ([src func initial] (.actorOf src (CljActor/create initial func))))
```

## 2.12 Remove old macros and example code

Remove the old `spawn`, `!`, `reply` macros (lines 13-20 in current core.clj).
Remove the inline example code (lines 25-53: `system`, `fa`, `fb`, `actor`, etc.).
Remove the global `(def system ...)`.

## Verification

`lein compile` should succeed. The functions won't be fully testable until
`defactor` (spec3) is implemented, but the namespace should load without errors.
You can do a quick smoke test with the low-level API:

```clojure
(def sys (actor-system "test"))
(def a (new-actor sys (fn [this msg] (.reply this (str "echo: " msg)) nil) nil))
;; Verify system creates without errors
(.terminate sys)
```
