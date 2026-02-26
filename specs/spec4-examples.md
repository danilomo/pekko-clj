# Spec 4: Examples & Polish

**Prerequisites**: spec1 + spec2 + spec3 must all be completed first.
**Files to modify**:
- MODIFY `src/main/clj/pekko_clj/test.clj` — rewrite with new DSL
- CREATE `src/main/clj/pekko_clj/examples/counter.clj` — simple counter
- CREATE `src/main/clj/pekko_clj/examples/chess.clj` — chess game (from user's app)

---

## Goal

Demonstrate the DSL with real examples. Rewrite existing test code. Prove the
chess example works cleanly.

## 4.1 Rewrite `test.clj`

Replace the current contents of `src/main/clj/pekko_clj/test.clj` with:

```clojure
(ns pekko-clj.test
  (:require [pekko-clj.core :refer :all]))

(defn seconds [val]
  (java.time.Duration/ofSeconds val))

(defactor manager
  (handle msg
    (reply (.toUpperCase msg))))

(defactor guardian
  (init [_]
    (schedule-once (seconds 1) #(println "scheduled message!"))
    {:manager (spawn manager)})

  (handle msg
    (! (:manager state) msg)
    state))

;; Usage:
;; (def sys (actor-system "test"))
;; (def a (spawn sys guardian))
;; (<! sys a "hello")  ;; => "HELLO"
;; (.terminate sys)
```

## 4.2 Counter example

Create `src/main/clj/pekko_clj/examples/counter.clj`:

```clojure
(ns pekko-clj.examples.counter
  (:require [pekko-clj.core :refer :all]))

(defactor counter
  "A simple counter actor."

  (init [args]
    {:count (or (:start args) 0)})

  (handle :inc
    (update state :count inc))

  (handle :dec
    (update state :count dec))

  (handle [:add n]
    (update state :count + n))

  (handle :get
    (reply (:count state)))

  (handle :reset
    {:count 0}))

(comment
  ;; REPL usage:
  (def sys (actor-system "counter-example"))
  (def c (spawn sys counter {:start 0}))

  (! c :inc)
  (! c :inc)
  (! c :inc)
  (! c [:add 10])
  (<! sys c :get)  ;; => 13

  (! c :reset)
  (<! sys c :get)  ;; => 0

  (.terminate sys))
```

## 4.3 Chess example

Create `src/main/clj/pekko_clj/examples/chess.clj`.

This demonstrates: child actors, `spawn` inside actors, `parent`, `sender`,
message forwarding, and state transitions.

```clojure
(ns pekko-clj.examples.chess
  (:require [pekko-clj.core :refer :all]))

;; --- Player actor ---

(defactor player
  (handle [:move move]
    (when (:my-turn state)
      (! (parent) move))
    state)

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
    (assoc state :my-turn true)))

;; --- Game actor (running state) ---

(defactor game
  (init [{:keys [white-ref black-ref white-cb black-cb]}]
    (let [white (spawn player {:color :white :my-turn true :callback white-cb})
          black (spawn player {:color :black :my-turn false :callback black-cb})]
      (! white-ref white)
      (! black-ref black)
      (! white [:game-start :white])
      (! black [:game-start :black])
      {:white white :black black :game nil #_"(new-game)"}))

  (handle move
    (let [{:keys [white black game]} state
          turn (or (:turn game) 0)
          current (if (even? turn) white black)
          next    (if (even? turn) black white)
          result  nil #_"(make-move game move)"]
      (if (nil? result)
        (do (! current [:status :not-ok]) state)
        (do (! current [:status :ok])
            (! next [:your-turn move])
            (assoc state :game result))))))

;; --- Lobby actor ---

(defactor lobby
  (init [_] :empty)

  (handle [:join callback]
    (if (= :empty state)
      {:first {:ref (sender) :cb callback}}
      (do
        (spawn game (merge state {:second {:ref (sender) :cb callback}}))
        :empty))))

(comment
  ;; The chess example requires the tenma-chess game logic to fully work.
  ;; This demonstrates the actor structure and message flow.
  (def sys (actor-system "chess"))
  (def l (spawn sys lobby))
  ;; Players would join via (<?> l [:join callback-fn])
  (.terminate sys))
```

## 4.4 Behavior switching example (become)

Add to `src/main/clj/pekko_clj/examples/become.clj`:

```clojure
(ns pekko-clj.examples.become
  (:require [pekko-clj.core :refer :all]))

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

(comment
  (def sys (actor-system "become-example"))
  (def a (spawn sys happy nil))

  (<! sys a :mood)    ;; => "I'm happy!"
  (! a :toggle)
  (<! sys a :mood)    ;; => "I'm sad..."
  (! a :toggle)
  (<! sys a :mood)    ;; => "I'm happy!"

  (.terminate sys))
```

## 4.5 Forward helper

In `core.clj`, if not already present, add:

```clojure
(defn forward
  "Forward the current message to another actor, preserving original sender."
  [target msg]
  (.forward *current-actor* target msg))
```

This enables the guardian pattern cleanly:

```clojure
(defactor guardian
  (handle msg
    (forward (:worker state) msg)
    state))
```

## Verification

1. `lein compile` succeeds
2. Load each example namespace in a REPL
3. Run the `comment` block code interactively
4. Counter: verify increment, add, get, reset
5. Become: verify behavior switching
6. Guardian: verify message forwarding with reply
