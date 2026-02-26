# Plan: Ergonomic Actor DSL for pekko-clj

## Vision

Make actors feel **native to Clojure** — the way CLOS makes objects feel native to
Common Lisp, or the way Elixir's GenServer makes actors feel native to Elixir.

The key insight: actors are fundamentally about **receiving messages and managing
state**. The DSL should make these two things trivially easy, hiding all the Pekko
machinery.

---

## Before / After Comparison

### BEFORE (current API)

```clojure
;; Defining an actor — bare function, manual state threading, Java interop visible
(defn counter [this msg]
  (m/match msg
    :inc       (+ @this 1)
    [:add n]   (+ @this n)
    :get       (do (.reply this @this) @this)
    :reset     0))

(def c (new-actor system counter 0))

;; Sending messages — Java interop leaking through
(.tell c :inc nil)
(.tell c [:add 5] nil)

;; Asking — requires Patterns, FnWrapper, dispatcher boilerplate
(.onComplete
  (Patterns/ask c :get 1000)
  (FnWrapper/create #(println (.get %)))
  (.getDispatcher system))
```

### AFTER (proposed DSL)

```clojure
;; Defining an actor — declarative, clean, pattern-matched handlers
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

;; Spawning — one clean function
(def sys (actor-system "myapp"))
(def c (spawn sys counter {:start 10}))

;; Sending — Erlang-style operators
(! c :inc)
(! c [:add 5])

;; Asking — clean, returns a future
@(<? c :get)   ;; => 16
```

### The chess example rewritten

```clojure
(defactor player
  (handle [:status status]
    ((:callback state) [:status status])
    (if (= :ok status)
      (assoc state :my-turn false)
      state))

  (handle [:game-start color]
    ((:callback state) [:game-start color])
    state)

  (handle [:your-turn move]
    ((:callback state) [:your-turn move])
    (assoc state :my-turn true))

  (handle [:move move]
    (when (:my-turn state)
      (! (parent) move))
    state))

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

---

## Design Principles

1. **`state` is implicitly bound** in all handlers — no `@this` everywhere
2. **`self`, `sender`, `parent`** are functions, not Java method calls
3. **`!` (tell)** works both inside and outside actors
4. **`reply`** is a side-effect function (returns nil so it doesn't affect state)
5. **`spawn`** is polymorphic — takes a system (top-level) or uses current actor context
6. **`become`** cleanly switches behavior using a dedicated type (not vector overloading)
7. **`defactor`** compiles `handle` clauses into `core.match` pattern matching

---

## Implementation Plan

### Phase 1: Java Foundation

#### 1.1 Create `BecomeResult` class
- New file: `src/main/java/pekko_clj/actor/BecomeResult.java`
- Fields: `IFn function`, `Object state`
- Static factory: `BecomeResult.of(IFn, Object)`

#### 1.2 Update `CljActor.handleState()`
- Add check for `BecomeResult` before the vector check
- When detected: update `this.function` and `this.state` from BecomeResult fields

#### 1.3 Add convenience methods to `CljActor`
- `parent()` → `getContext().getParent()`
- `senderRef()` → `getSender()` (renamed to avoid clash)
- `selfRef()` → `getSelf()` (renamed to avoid clash)

#### 1.4 Add `spawn(ILookup props)` overload
- Creates a child actor from a Clojure map of props (like the top-level `create(ILookup)`)
- Delegates to `getContext().actorOf(create(props))`

#### 1.5 Update `onReceive` to bind dynamic var
- Before calling `function.invoke(this, message)`, push a binding frame for `*current-actor*`
- This can be done in Java by calling the Clojure `binding` mechanism, OR
- Simpler: wrap the function call in Clojure-side (the generated defactor code handles binding)
- **Decision**: handle binding in Clojure (in the generated defactor code), not in Java. Simpler.

### Phase 2: Core Clojure Functions

#### 2.1 Dynamic var `*current-actor*`
```clojure
(def ^:dynamic *current-actor* nil)
```

#### 2.2 Context-aware `!` (tell)
```clojure
(defn ! [target msg]
  (if-let [actor *current-actor*]
    (.tell actor target msg)          ;; CljActor.tell sets sender=self
    (.tell target msg ActorRef/noSender)))
```
- Inside actor: sender is `self`
- Outside actor: sender is `noSender`

#### 2.3 Context-aware `reply`
```clojure
(defn reply [msg]
  (.reply *current-actor* msg))
```
- Only works inside actor context (throws if `*current-actor*` is nil)

#### 2.4 Accessor functions
```clojure
(defn sender [] (.getSender *current-actor*))
(defn self   [] (.getSelf *current-actor*))
(defn parent [] (-> *current-actor* .getContext .getParent))
```

#### 2.5 Polymorphic `spawn`
```clojure
(defn spawn
  ([actor-def]
   (spawn actor-def nil))
  ([first-arg second-arg]
   (if (instance? ActorSystem first-arg)
     (spawn first-arg second-arg nil)               ;; (spawn system actor-def)
     (let [props (make-props first-arg second-arg)]  ;; (spawn actor-def args)
       (.actorOf (.getContext *current-actor*) props))))
  ([system actor-def args]                           ;; (spawn system actor-def args)
   (.actorOf system (make-props actor-def args))))
```
Where `make-props` calls the actor-def's factory to produce a CljActor Props.

#### 2.6 `<?>` (ask)
```clojure
(def ^:dynamic *timeout* 30000)

(defn <? [target msg & [timeout]]
  (Patterns/ask target msg (or timeout *timeout*)))
```
Returns a Scala Future. We can add a helper to convert to CompletableFuture or a Clojure promise.

#### 2.7 `become`
```clojure
(defn become [actor-def new-state]
  (BecomeResult/of (:receive actor-def) new-state))
```

#### 2.8 `actor-system`
```clojure
(defn actor-system
  ([] (ActorSystem/create))
  ([name] (ActorSystem/create name)))
```

### Phase 3: `defactor` Macro

This is the centerpiece. The macro parses declarative clauses and generates the
actor plumbing.

#### 3.1 Clause parsing

The macro body contains forms like:
- `(init [params] body...)` — initialization, receives spawn args, returns initial state
- `(handle pattern body...)` — message handler
- `(on-stop body...)` — post-stop lifecycle hook
- `(on-restart body...)` — pre-restart lifecycle hook

The parser separates these into categorized lists.

#### 3.2 Code generation

`defactor` generates a `def` binding to a map:

```clojure
(def actor-name
  (let [receive-fn (fn [this msg]
                     (binding [*current-actor* this]
                       (let [state @this]
                         (m/match msg
                           pattern1 (do body1...)
                           pattern2 (do body2...)
                           ...))))]
    {:receive receive-fn
     :make-props (fn [args]
                   {:function receive-fn
                    :pre-start (fn [this]
                                 (binding [*current-actor* this]
                                   (let [init-params args]
                                     init-body...)))
                    :post-stop ...})}))
```

Key details:
- `state` is `let`-bound to `@this` in every handler
- `*current-actor*` is `binding`-set in every handler
- Handler bodies are spliced into `core.match` match clauses
- Init body is wrapped in the `:pre-start` function
- The var holds a map with `:receive` (for `become`) and `:make-props` (for `spawn`)

#### 3.3 Pattern support

Patterns in `handle` clauses map directly to `core.match`:
- `:keyword` — matches a keyword
- `[:tag arg1 arg2]` — matches a vector, binds destructured elements
- `{:key val}` — matches a map
- `symbol` — catch-all, binds the message to symbol
- `_` — wildcard, ignore

#### 3.4 Docstring support

Optional docstring as first body form (like `defn`):
```clojure
(defactor counter
  "A simple counter."
  (init ...) ...)
```

### Phase 4: Polish

#### 4.1 Backward compatibility
- Keep `new-actor` working for raw function-based actors
- Keep the `!` and `reply` macros available (renamed or in a legacy ns)

#### 4.2 Rewrite examples
- Counter example (simple)
- Guardian/forwarder example (from test.clj)
- Chess example (complex, demonstrates spawn, become, state management)

#### 4.3 Add `schedule-once` helper
```clojure
(defn schedule-once [duration f]
  (.scheduleOnce *current-actor* duration f))
```

---

## File Changes Summary

| File | Action | Description |
|------|--------|-------------|
| `src/main/java/.../BecomeResult.java` | CREATE | New class for behavior switching |
| `src/main/java/.../CljActor.java` | MODIFY | Add BecomeResult handling, convenience methods, spawn overload |
| `src/main/clj/pekko_clj/core.clj` | MODIFY | New DSL functions + defactor macro |
| `src/main/clj/pekko_clj/test.clj` | MODIFY | Rewrite with new DSL |

---

## Open Questions / Risks

1. **Scala Future ergonomics**: `<?>` returns a Scala Future. May want to add conversion
   to CompletableFuture or a blocking `<!` variant.
2. **Error messages**: If `*current-actor*` is nil when calling `reply`, `sender`, etc.,
   error messages should be clear ("called outside actor context").
3. **core.match compilation**: Ensure the macro-generated `m/match` form compiles
   correctly with all supported pattern types.
4. **Thread safety of dynamic bindings**: Pekko guarantees single-threaded actor
   execution, so `binding` within `onReceive` is safe.
