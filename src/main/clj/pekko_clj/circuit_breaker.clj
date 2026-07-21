(ns pekko-clj.circuit-breaker
  "A wrapper over Pekko's `CircuitBreaker` pattern. A circuit breaker protects a
   fragile call site: after `:max-failures` consecutive failures (or timeouts) it
   trips OPEN and fails calls fast for `:reset-timeout`, then goes HALF-OPEN to let
   a single trial call decide whether to CLOSE again.

   Example:
     (def cb (cb/circuit-breaker sys {:max-failures 5
                                      :call-timeout 2000
                                      :reset-timeout 30000}))
     ;; Guard a synchronous call (throws CircuitBreakerOpenException while open):
     (cb/call cb (fn [] (do-fragile-thing)))
     ;; Or an async one returning a CompletionStage:
     (cb/call-async cb (fn [] (fetch-async)))"
  (:refer-clojure :exclude [fail])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.pattern CircuitBreaker]
           [java.time Duration]
           [java.util.concurrent Callable CompletionStage]))

(defn- ->duration
  ^Duration [d]
  (if (instance? Duration d) d (Duration/ofMillis (long d))))

(defn circuit-breaker
  "Create a CircuitBreaker scheduled on `system`.

   Options:
   - :max-failures  - consecutive failures before the breaker opens (default 5)
   - :call-timeout  - per-call timeout; a number of ms or a java.time.Duration
                      (default 10000). A call exceeding it counts as a failure.
   - :reset-timeout - how long the breaker stays open before going half-open;
                      ms or Duration (default 60000)"
  (^CircuitBreaker [^ActorSystem system] (circuit-breaker system {}))
  (^CircuitBreaker [^ActorSystem system {:keys [max-failures call-timeout reset-timeout]
                                         :or {max-failures 5
                                              call-timeout 10000
                                              reset-timeout 60000}}]
   (CircuitBreaker/create (.scheduler system) (int max-failures)
                          (->duration call-timeout) (->duration reset-timeout))))

(defn call
  "Run 0-arg `f` through `breaker` SYNCHRONOUSLY (blocks the calling thread). While
   the breaker is open it throws `CircuitBreakerOpenException` immediately; an
   exception or timeout from `f` counts as a failure and is rethrown. Returns f's
   value on success."
  [^CircuitBreaker breaker f]
  (.callWithSyncCircuitBreaker breaker (reify Callable (call [_] (f)))))

(defn call-async
  "Run `f` (0-arg, returning a CompletionStage) through `breaker`; returns a
   CompletionStage that fails with `CircuitBreakerOpenException` when open, and
   whose failure/timeout counts toward opening the breaker."
  ^CompletionStage [^CircuitBreaker breaker f]
  (.callWithCircuitBreakerCS breaker (reify Callable (call [_] (f)))))

(defn succeed
  "Manually record a success (for use with externally-managed calls). Returns nil."
  [^CircuitBreaker breaker]
  (.succeed breaker)
  nil)

(defn fail
  "Manually record a failure (for use with externally-managed calls). Returns nil."
  [^CircuitBreaker breaker]
  (.fail breaker)
  nil)

(defn open?
  "True if the breaker is currently open (failing fast)."
  [^CircuitBreaker breaker]
  (.isOpen breaker))

(defn closed?
  "True if the breaker is currently closed (calls flow normally)."
  [^CircuitBreaker breaker]
  (.isClosed breaker))

(defn on-open
  "Register 0-arg `f` to run when the breaker transitions to OPEN. Returns breaker."
  [^CircuitBreaker breaker f]
  (.addOnOpenListener breaker (reify Runnable (run [_] (f)))))

(defn on-close
  "Register 0-arg `f` to run when the breaker transitions to CLOSED. Returns breaker."
  [^CircuitBreaker breaker f]
  (.addOnCloseListener breaker (reify Runnable (run [_] (f)))))

(defn on-half-open
  "Register 0-arg `f` to run when the breaker transitions to HALF-OPEN. Returns breaker."
  [^CircuitBreaker breaker f]
  (.addOnHalfOpenListener breaker (reify Runnable (run [_] (f)))))
