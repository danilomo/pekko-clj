# Spec 3: `defactor` Macro

**Prerequisites**: spec1 + spec2 must be completed first.
**Files to modify**:
- MODIFY `src/main/clj/pekko_clj/core.clj` (add the macro)

---

## Goal

Implement the `defactor` macro that compiles declarative actor definitions into
the runtime plumbing. This is the centerpiece of the DSL.

## Syntax

```clojure
(defactor name
  "Optional docstring"

  (init [args-binding]
    body...)

  (handle pattern
    body...)

  (handle pattern
    body...)

  (on-stop
    body...)

  (on-restart
    body...))
```

### Clauses

| Clause | Required | Description |
|--------|----------|-------------|
| `init` | No | Called at actor start. Receives spawn args. Returns initial state. |
| `handle` | Yes (1+) | Message handler. Pattern is matched via `core.match`. |
| `on-stop` | No | Called when actor stops. |
| `on-restart` | No | Called when actor restarts. |

### Implicit bindings available in all handler/lifecycle bodies

| Binding | Value |
|---------|-------|
| `state` | Current actor state (`@this`) — **only in `handle` clauses** |

The functions `self`, `sender`, `parent`, `!`, `reply`, `spawn`, `become` all
work inside handlers because `*current-actor*` is bound.

## 3.1 Clause parser

Write a private helper function that takes the body forms and returns a map:

```clojure
(defn- parse-actor-clauses [body]
  (let [clauses (group-by first body)]
    {:init     (first (get clauses 'init))
     :handlers (get clauses 'handle)
     :on-stop  (first (get clauses 'on-stop))
     :on-restart (first (get clauses 'on-restart))}))
```

Each clause is a list like `(handle :inc (update state :count inc))`.

For `init`: `(init [args] body...)` → params = `[args]`, body = `body...`
For `handle`: `(handle pattern body...)` → pattern = `pattern`, body = `body...`

## 3.2 The macro

```clojure
(defmacro defactor [name & body]
  (let [;; optional docstring
        docstring (when (string? (first body)) (first body))
        clauses   (if docstring (rest body) body)
        parsed    (parse-actor-clauses clauses)

        ;; destructure init clause: (init [args] body...)
        init-clause  (:init parsed)
        init-params  (when init-clause (second init-clause))   ;; [args]
        init-body    (when init-clause (drop 2 init-clause))   ;; body...

        ;; destructure handle clauses: (handle pattern body...)
        handlers (:handlers parsed)
        ;; Build match pairs: pattern1 (do body1) pattern2 (do body2) ...
        match-pairs (mapcat (fn [h]
                              (let [pattern (second h)
                                    hbody   (drop 2 h)]
                                [pattern `(do ~@hbody)]))
                            handlers)

        ;; lifecycle
        on-stop    (:on-stop parsed)
        on-restart (:on-restart parsed)

        ;; gensyms
        this-sym (gensym "this")
        msg-sym  (gensym "msg")
        args-sym (gensym "args")]

    `(def ~(vary-meta name assoc :doc (or docstring ""))
       (let [receive-fn#
             (fn [~this-sym ~msg-sym]
               (binding [*current-actor* ~this-sym]
                 (let [~'state (deref ~this-sym)]
                   (m/match ~msg-sym
                     ~@match-pairs))))]
         {:receive    receive-fn#
          :make-props (fn [~args-sym]
                        (merge
                         {:function receive-fn#}
                         ~(when init-clause
                            `{:pre-start
                              (fn [~this-sym]
                                (binding [*current-actor* ~this-sym]
                                  (let [~(first init-params) ~args-sym]
                                    ~@init-body)))})
                         ~(when on-stop
                            `{:post-stop
                              (fn [~this-sym]
                                (binding [*current-actor* ~this-sym]
                                  ~@(rest on-stop)))})))}))))
```

## 3.3 What `defactor` produces

For this input:

```clojure
(defactor counter
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
```

It generates (conceptually):

```clojure
(def counter
  (let [receive-fn
        (fn [this msg]
          (binding [*current-actor* this]
            (let [state @this]
              (m/match msg
                :inc     (do (update state :count inc))
                [:add n] (do (update state :count + n))
                :get     (do (reply (:count state)))
                :reset   (do {:count 0})))))]
    {:receive    receive-fn
     :make-props (fn [args]
                   {:function  receive-fn
                    :pre-start (fn [this]
                                 (binding [*current-actor* this]
                                   (let [args args]
                                     {:count (or (:start args) 0)})))})}))
```

## 3.4 Edge cases to handle

1. **Single-expression handler body**: `(handle :inc (update state :count inc))`
   — body is one form, wrapped in `(do ...)` which is fine.

2. **Multi-expression handler body**: `(handle :get (reply val) state)`
   — multiple forms, `(do (reply val) state)` → last expr is new state.

3. **Catch-all handler**: `(handle msg (println "unknown:" msg) state)`
   — `msg` is a symbol, core.match binds it.

4. **No init clause**: actor gets `nil` as initial state unless `spawn` provides
   args that become state via a default `:pre-start`.
   When no `init` is defined, we should set `:state` in make-props to the args
   directly:
   ```clojure
   :make-props (fn [args] {:function receive-fn :state args})
   ```

5. **Wildcard handler**: `(handle _ state)` — matches everything, ignores msg.

## 3.5 Helper: `schedule-once`

Add a convenience function for scheduling:

```clojure
(defn schedule-once
  "Schedule a function to run once after a duration (java.time.Duration)."
  [duration f]
  (.scheduleOnce *current-actor* duration f))
```

## Verification

After implementing, this should work in a REPL:

```clojure
(require '[pekko-clj.core :refer :all])

(defactor counter
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

(def sys (actor-system "test"))
(def c (spawn sys counter {:start 10}))

(! c :inc)
(! c [:add 5])

;; Ask and get reply
(def result (<! sys c :get))
;; result should be 16

(.terminate sys)
```

Run `lein compile` to verify compilation. Test interactively in a REPL if possible.
