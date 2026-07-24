(ns pekko-clj.stream
  "Pekko Streams support for pekko-clj.

   Provides a functional API for building and running reactive streams
   with backpressure support.

   Core concepts:
   - Source: produces elements (from collections, actors, etc.)
   - Flow: transforms elements (map, filter, etc.)
   - Sink: consumes elements (foreach, fold, to actors, etc.)

   Most operators take the stream as their first argument and are polymorphic over
   a Source and a Flow, so they thread with `->`. The `run-*` family takes a
   Materializer (see `materializer`) as its LAST argument and returns a
   CompletionStage; `await-completion` blocks for its value.

   Beyond the basics:
   - Materialized values: `to-mat`/`via-mat`/`run-mat` with `keep-mat`
     (:left/:right/:both/:none), plus `run-source-queue` / `run-sink-queue`.
   - Lifecycle: `run-with-kill-switch` and `shared-kill-switch` to stop streams
     from outside; `shutdown` / `abort`.
   - Failure handling: `with-supervision` (:stop/:resume/:restart) for
     per-element failures, `recover*` for fallbacks, and `restart-*` /
     `retry-flow` for backoff.
   - Actor interop: `ask` / `ask-with-status`, `source-actor-ref`, `to-actor`,
     `sink-actor-ref-with-backpressure`.

   Naming convention: `map` and `filter` clash with clojure.core and are used so
   pervasively that they are renamed here with an `s` prefix — `smap`/`sfilter` —
   rather than shadowed. Less commonly-confused ops (`concat`, `drop`, `take`,
   `merge`, `mapcat`, `distinct`, `partition`, `group-by`, `take-while`,
   `drop-while`) DO shadow clojure.core (see the ns :refer-clojure :exclude), so
   qualify them (`stream/take`) or use the aliased require in call sites.

   Example:
     (let [mat (materializer sys)]
       (-> (source (range 100))
           (smap inc)
           (sfilter even?)
           (run-foreach println mat)))"
  (:refer-clojure :exclude [concat dedupe drop drop-while interleave mapcat take take-while merge distinct group-by])
  (:import [org.apache.pekko.stream Materializer OverflowStrategy
            ActorAttributes Attributes CompletionStrategy IOResult KillSwitch
            KillSwitches RestartSettings SharedKillSwitch Supervision
            SystemMaterializer]
           [org.apache.pekko.stream.javadsl Source Flow Sink Keep RunnableGraph
            AsPublisher
            SubSource SubFlow
            FileIO StreamConverters Framing FramingTruncation
            RestartSource RestartFlow RestartSink RetryFlow]
           [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.japi Pair]
           [org.apache.pekko.japi.pf PFBuilder FI$Apply]
           [org.apache.pekko.pattern StatusReply]
           [org.apache.pekko.util ByteString Timeout]
           [java.util.concurrent CompletionStage]
           [java.util Optional]
           [java.time Duration]
           [java.io File]
           [java.nio.file Path StandardOpenOption]
           [clojure.lang Reflector]
           [org.reactivestreams Publisher]))

;; ---------------------------------------------------------------------------
;; Operator dispatch
;; ---------------------------------------------------------------------------

(defmacro ^:private op
  "Call instance method `method` on a stream stage, without reflection.

   The javadsl `Source` and `Flow` declare the same operator names but share no
   supertype that declares them — only `Graph`, which declares none of them — so
   there is no single type hint that resolves a bare `(.map src f)`. Testing the two
   concrete types lets the compiler emit a direct invocation on each branch.

   `group-by` hands back a `SubSource`/`SubFlow`, which callers may pipe through
   these same operators before merging. Those take the last branch and are
   dispatched reflectively at runtime — the same thing an un-hinted call did, so
   behaviour is unchanged; only the compile-time warning goes away."
  [src method & args]
  `(let [s# ~src]
     (cond
       (instance? Source s#) (. ^Source s# ~method ~@args)
       (instance? Flow s#)   (. ^Flow s# ~method ~@args)
       :else (Reflector/invokeInstanceMethod s# ~(name method) (object-array [~@args])))))

;; ---------------------------------------------------------------------------
;; Coercion helpers
;; ---------------------------------------------------------------------------

(defn- ->duration
  "Coerce a java.time.Duration or a number of milliseconds to a Duration."
  ^Duration [d]
  (if (instance? Duration d) d (Duration/ofMillis (long d))))

(defn- ->overflow-strategy
  "Coerce an overflow-strategy keyword to an OverflowStrategy. `nil` selects
   `default` (itself a keyword); any other unrecognized keyword throws.

   Note: not every operator accepts every strategy — `Source/actorRef` rejects
   :backpressure, for instance. Pekko validates at materialization."
  [strategy default]
  (case strategy
    :drop-head    (OverflowStrategy/dropHead)
    :drop-tail    (OverflowStrategy/dropTail)
    :drop-buffer  (OverflowStrategy/dropBuffer)
    :drop-new     (OverflowStrategy/dropNew)
    :fail         (OverflowStrategy/fail)
    :backpressure (OverflowStrategy/backpressure)
    nil (->overflow-strategy default nil)
    (throw (IllegalArgumentException.
            (str "Unknown overflow strategy: " (pr-str strategy)
                 ". Valid options: :drop-head, :drop-tail, :drop-buffer, "
                 ":drop-new, :fail, :backpressure (or nil for the default).")))))

;; ---------------------------------------------------------------------------
;; Materializer
;; ---------------------------------------------------------------------------

(defn system-materializer
  "The ActorSystem's own Materializer — one per system, created on first use and
   shut down with the system.

   This is the one to use. `materializer` creates a *fresh* materializer every
   call, and each one owns actors that live until it is explicitly shut down, so
   calling it per stream leaks.

   Every `run-*` function also accepts an ActorSystem directly in place of a
   Materializer, which resolves to this."
  ^Materializer [^ActorSystem system]
  (.materializer (SystemMaterializer/get system)))

(defn materializer
  "Create a **new** Materializer from an ActorSystem.

   Prefer `system-materializer` (or just pass the ActorSystem to the `run-*`
   functions): each materializer created here owns actors that are only released
   when it is shut down, so one per stream leaks them. Use this only when a stream
   needs materializer settings or a lifetime of its own."
  ^Materializer [^ActorSystem system]
  (Materializer/createMaterializer system))

(defn- ->materializer
  "Coerce an ActorSystem to its Materializer; pass a Materializer through.

   Lets every `run-*` take either, so the common case never has to name a
   materializer at all."
  ^Materializer [m]
  (if (instance? ActorSystem m)
    (system-materializer m)
    m))

;; ---------------------------------------------------------------------------
;; Sources
;; ---------------------------------------------------------------------------

(defn source
  "Create a Source from a Clojure collection or sequence.

   An empty (or nil) collection yields an empty Source that completes
   immediately. The `seq` call also coerces Strings/arrays into an Iterable."
  [coll]
  (Source/from (or (seq coll) [])))

(defn source-single
  "Create a Source that emits a single element."
  [element]
  (Source/single element))

(defn source-empty
  "Create an empty Source that completes immediately."
  []
  (Source/empty))

(defn source-failed
  "Create a Source that fails immediately with the given exception."
  [^Throwable ex]
  (Source/failed ex))

(defn source-repeat
  "Create a Source that repeats the given element indefinitely."
  [element]
  (Source/repeat element))

(defn source-tick
  "Create a Source that emits elements at regular intervals.
   initial-delay: ms or Duration before first element
   interval: ms or Duration between elements
   element: The element to emit"
  [initial-delay interval element]
  (Source/tick (->duration initial-delay) (->duration interval) element))

(defn source-unfold
  "Create a Source by repeatedly applying a function.
   f takes a state and returns [next-state element] or nil to complete."
  [initial-state f]
  (Source/unfold
   initial-state
   (reify org.apache.pekko.japi.function.Function
     (apply [_ state]
       (if-let [[next-state element] (f state)]
         (Optional/of (org.apache.pekko.japi.Pair. next-state element))
         (Optional/empty))))))

(defn source-lazily
  "Create a Source that is lazily created when the stream is run.
   f is a no-arg function that returns a Source.

   Built on `Source.lazySource`; `Source.lazily` carries a Deprecated attribute in
   Pekko 1.6 (checked with javap -v) and is only an alias for it."
  [f]
  (Source/lazySource
   (reify org.apache.pekko.japi.function.Creator
     (create [_] (f)))))

(defn source-never
  "Create a Source that never emits and never completes.

   Useful as a placeholder branch, or to keep a merge open."
  []
  (Source/never))

(defn source-unfold-async
  "Like `source-unfold`, but `f` returns a CompletionStage of [next-state element]
   (or of nil to complete) — so each step can do asynchronous work.

   Example:
     (source-unfold-async 0 (fn [n] (CompletableFuture/completedFuture
                                      (when (< n 3) [(inc n) n]))))"
  [initial-state f]
  (Source/unfoldAsync
   initial-state
   (reify org.apache.pekko.japi.function.Function
     (apply [_ state]
       (.thenApply ^CompletionStage (f state)
                   (reify java.util.function.Function
                     (apply [_ result]
                       (if-let [[next-state element] result]
                         (Optional/of (Pair. next-state element))
                         (Optional/empty)))))))))

(defn source-range
  "Create a Source that emits integers from start (inclusive) to end (exclusive)."
  ([end] (source-range 0 end))
  ([start end]
   (source (range start end))))

;; ---------------------------------------------------------------------------
;; Flows (transformations)
;; ---------------------------------------------------------------------------

(defn smap
  "Transform elements using a function. (Named smap to avoid clash with clojure.core/map)"
  [src f]
  (op src map (reify org.apache.pekko.japi.function.Function
                (apply [_ x] (f x)))))

(defn sfilter
  "Filter elements using a predicate. (Named sfilter to avoid clash with clojure.core/filter)"
  [src pred]
  (op src filter (reify org.apache.pekko.japi.function.Predicate
                   (test [_ x] (boolean (pred x))))))

(defn mapcat
  "Transform each element to zero or more elements."
  [src f]
  (op src mapConcat (reify org.apache.pekko.japi.function.Function
                      (apply [_ x] (seq (f x))))))

(defn take
  "Take only the first n elements."
  [src n]
  (op src take (long n)))

(defn drop
  "Drop the first n elements."
  [src n]
  (op src drop (long n)))

(defn take-while
  "Take elements while predicate is true."
  [src pred]
  (op src takeWhile (reify org.apache.pekko.japi.function.Predicate
                      (test [_ x] (boolean (pred x))))))

(defn drop-while
  "Drop elements while predicate is true."
  [src pred]
  (op src dropWhile (reify org.apache.pekko.japi.function.Predicate
                      (test [_ x] (boolean (pred x))))))

(defn grouped
  "Group elements into vectors of n elements."
  [src n]
  (op src grouped (int n)))

(defn sliding
  "Create sliding windows of n elements."
  ([src n] (sliding src n 1))
  ([src n step]
   (op src sliding (int n) (int step))))

(defn scan
  "Fold over elements, emitting each intermediate result."
  [src initial f]
  (op src scan initial (reify org.apache.pekko.japi.function.Function2
                         (apply [_ acc x] (f acc x)))))

(defn fold
  "Fold over elements, emitting only the final result."
  [src initial f]
  (op src fold initial (reify org.apache.pekko.japi.function.Function2
                         (apply [_ acc x] (f acc x)))))

(defn intersperse
  "Insert an element between each pair of elements."
  [src separator]
  (op src intersperse separator))

(defn throttle
  "Limit the rate of elements.
   elements: number of elements
   per: ms or Duration for the rate limit"
  [src elements per]
  (op src throttle (int elements) (->duration per)))

(defn delay-each
  "Delay each element by the given duration, ms or a java.time.Duration
   (backpressuring upstream while waiting)."
  [src duration]
  (op src delay (->duration duration) (org.apache.pekko.stream.DelayOverflowStrategy/backpressure)))

(defn buffer
  "Buffer elements when downstream is slower.
   size: buffer size
   strategy: :drop-head, :drop-tail, :drop-buffer, :drop-new, :fail"
  [src size strategy]
  (op src buffer (int size) (->overflow-strategy strategy :drop-new)))

(defn async
  "Run the previous stages asynchronously."
  [src]
  (op src async))

(defn via
  "Connect a Source to a Flow."
  [src flow]
  (op src via flow))

(defn concat
  "Concatenate another source after this one completes."
  [src other-src]
  (op src concat other-src))

(defn merge
  "Merge elements from another source."
  [src other-src]
  (op src merge other-src))

(defn zip-with
  "Zip with another source using a combining function."
  [src other-src f]
  (op src zipWith other-src
      (reify org.apache.pekko.japi.function.Function2
        (apply [_ a b] (f a b)))))

;; ---------------------------------------------------------------------------
;; Sinks
;; ---------------------------------------------------------------------------

(defn sink-foreach
  "Create a Sink that runs a side-effecting function for each element."
  [f]
  (Sink/foreach (reify org.apache.pekko.japi.function.Procedure
                  (apply [_ x] (f x)))))

(defn sink-fold
  "Create a Sink that folds over elements."
  [initial f]
  (Sink/fold initial (reify org.apache.pekko.japi.function.Function2
                       (apply [_ acc x] (f acc x)))))

(defn sink-head
  "Create a Sink that returns the first element."
  []
  (Sink/head))

(defn sink-last
  "Create a Sink that returns the last element."
  []
  (Sink/last))

(defn sink-head-option
  "Create a Sink returning a CompletionStage<Optional> of the first element —
   empty rather than failed when the stream had no elements, unlike `sink-head`."
  []
  (Sink/headOption))

(defn sink-last-option
  "Create a Sink returning a CompletionStage<Optional> of the last element —
   empty rather than failed when the stream had no elements, unlike `sink-last`."
  []
  (Sink/lastOption))

(defn sink-take-last
  "Create a Sink collecting the last `n` elements into a List.

   This lives on Sink, not on Source/Flow: keeping the tail needs to know where the
   stream ends, so there is no streaming `take-last` operator."
  [n]
  (Sink/takeLast (int n)))

(defn sink-seq
  "Create a Sink that collects all elements into a sequence."
  []
  (Sink/seq))

(defn sink-ignore
  "Create a Sink that ignores all elements."
  []
  (Sink/ignore))

(defn sink-cancelled
  "Create a Sink that cancels immediately."
  []
  (Sink/cancelled))

(defn sink-actor-ref
  "Create a Sink that sends elements to an actor.
   on-complete-msg: message to send when stream completes
   Note: This sink materializes to NotUsed, not a CompletionStage.
   This sink does not backpressure — a slow actor's mailbox will grow unbounded.
   Use sink-actor-ref-with-backpressure when the actor must pace the stream."
  [^ActorRef actor-ref on-complete-msg]
  (Sink/actorRef actor-ref on-complete-msg))

(defn sink-actor-ref-with-backpressure
  "Create a Sink that sends elements to an actor, using an ack protocol so the
   actor backpressures the stream.

   The actor receives on-init-msg first and must reply with ack-msg; thereafter it
   must reply with ack-msg for each element before the next is sent. on-complete-msg
   is sent when the stream completes; on-failure-fn (a fn of Throwable -> message)
   builds the message sent when it fails.

   Materializes to NotUsed.

   Example:
     (sink-actor-ref-with-backpressure worker :init :ack :done
                                       (fn [ex] [:failed (.getMessage ex)]))"
  [^ActorRef actor-ref on-init-msg ack-msg on-complete-msg on-failure-fn]
  (Sink/actorRefWithBackpressure actor-ref on-init-msg ack-msg on-complete-msg
                                 (reify org.apache.pekko.japi.function.Function
                                   (apply [_ ex] (on-failure-fn ex)))))

;; ---------------------------------------------------------------------------
;; Running streams
;; ---------------------------------------------------------------------------

(defn run
  "Run a stream with a Sink, returning a CompletionStage of the materialized value."
  [src ^Sink sink materializer]
  ;; Bound to a hinted local rather than hinting the form: `op` expands to a
  ;; `cond`, and the hint would not survive the expansion.
  (let [^RunnableGraph graph (op src toMat sink (Keep/right))]
    (.run graph (->materializer materializer))))

(defn run-with
  "Run a stream with a Sink, returning a CompletionStage of the materialized value."
  [^Source src ^Sink sink materializer]
  (.runWith src sink (->materializer materializer)))

(defn run-foreach
  "Run a stream, applying f to each element. Returns a CompletionStage<Done>."
  [src f materializer]
  (run-with src (sink-foreach f) materializer))

(defn run-fold
  "Run a stream, folding over elements. Returns a CompletionStage of the result."
  [src initial f materializer]
  (run-with src (sink-fold initial f) materializer))

(defn run-to-seq
  "Run a stream, collecting all elements into a sequence.
   Returns a CompletionStage<List>."
  [src materializer]
  (run-with src (sink-seq) materializer))

(defn run-head
  "Run a stream, returning the first element."
  [src materializer]
  (run-with src (sink-head) materializer))

(defn run-last
  "Run a stream, returning the last element."
  [src materializer]
  (run-with src (sink-last) materializer))

;; ---------------------------------------------------------------------------
;; Actor integration
;; ---------------------------------------------------------------------------

(defn- ->completion-strategy
  "Coerce :immediately / :draining (or a CompletionStrategy) to a CompletionStrategy."
  [v]
  (cond
    (instance? CompletionStrategy v) v
    (= :immediately v)               (CompletionStrategy/immediately)
    :else                            (CompletionStrategy/draining)))

(defn source-actor-ref
  "Create a Source backed by an actor that you can send messages to.

   Returns {:source Source, :actor-ref ActorRef} — the same map shape
   `source-queue` and `run-source-queue` use.

   buffer-size: size of the buffer
   overflow-strategy: :drop-head, :drop-tail, :drop-buffer, :drop-new, :fail
     (:backpressure is NOT supported here — use source-queue instead)

   Send messages to the actor-ref to emit them from the source. With the 3-arity,
   completion/failure use Pekko's defaults: send org.apache.pekko.actor.Status$Success
   to complete the stream and Status$Failure to fail it.

   The 4-arity takes an opts map and uses the matcher-based Source/actorRef overload
   for explicit control over which messages terminate the stream:
   - :complete-with  fn of message -> :immediately, :draining, a CompletionStrategy,
                     or nil to not complete
   - :fail-with      fn of message -> a Throwable, or nil to not fail

   Example:
     (let [{:keys [source actor-ref]}
           (source-actor-ref 8 :fail {:complete-with #(when (= :done %) :immediately)
                                      :fail-with     #(when (= :boom %) (RuntimeException. \"boom\"))}
                             sys)]
       ...)"
  ([buffer-size overflow-strategy materializer]
   (source-actor-ref buffer-size overflow-strategy nil materializer))
  ([buffer-size overflow-strategy opts materializer]
   (let [overflow (->overflow-strategy overflow-strategy :fail)
         {:keys [complete-with fail-with]} opts
         source (if opts
                  (Source/actorRef
                   (reify org.apache.pekko.japi.function.Function
                     (apply [_ msg]
                       (if-let [v (when complete-with (complete-with msg))]
                         (Optional/of (->completion-strategy v))
                         (Optional/empty))))
                   (reify org.apache.pekko.japi.function.Function
                     (apply [_ msg]
                       (if-let [^Throwable ex (when fail-with (fail-with msg))]
                         (Optional/of ex)
                         (Optional/empty))))
                   (int buffer-size)
                   overflow)
                  (Source/actorRef (int buffer-size) overflow))
         ;; preMaterialize returns a Pair (materialized-value, source): .first is
         ;; the ActorRef, .second is the reusable Source.
         ^Pair pair (.preMaterialize ^Source source (->materializer materializer))]
     {:source (.second pair) :actor-ref (.first pair)})))

(defn to-actor
  "Connect a Source to an actor, sending each element as a message.
   complete-msg: message to send when stream completes
   Note: Returns NotUsed, not a CompletionStage. The stream runs asynchronously."
  [src ^ActorRef actor-ref complete-msg materializer]
  (let [^RunnableGraph graph (op src to (sink-actor-ref actor-ref complete-msg))]
    (.run graph (->materializer materializer))))

;; ---------------------------------------------------------------------------
;; Utility functions
;; ---------------------------------------------------------------------------

(defn await-completion
  "Block for a CompletionStage's value (up to timeout-ms, default 30000). Rethrows
   the unwrapped failure if the stage completed exceptionally; returns nil on the
   block timeout — the same nil-vs-throw convention as pekko-clj.core/<!."
  ([stage] (await-completion stage 30000))
  ([^CompletionStage stage timeout-ms]
   (try
     (.get (.toCompletableFuture stage) (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
     (catch java.util.concurrent.ExecutionException e
       (throw (or (.getCause e) e)))
     (catch java.util.concurrent.TimeoutException _ nil))))

(defn completion->promise
  "Convert a CompletionStage to a Clojure promise, delivering {:value result} on
   success or {:error cause} on failure. The failure is unwrapped from its
   CompletionException wrapper (`.getCause`), matching `await-completion`'s
   convention, so `:error` is the exception the stage actually failed with."
  [^CompletionStage stage]
  (let [p (promise)]
    (.whenComplete stage
                   (reify java.util.function.BiConsumer
                     (accept [_ result exception]
                       (if exception
                         (deliver p {:error (or (.getCause ^Throwable exception) exception)})
                         (deliver p {:value result})))))
    p))

;; ---------------------------------------------------------------------------
;; Phase 1: Async Operators (Critical for I/O)
;; ---------------------------------------------------------------------------

(defn map-async
  "Transform elements using an async function that returns a CompletionStage.
   Preserves order of elements.
   parallelism: maximum number of concurrent async operations"
  [src parallelism f]
  (op src mapAsync (int parallelism)
      (reify org.apache.pekko.japi.function.Function
        (apply [_ x] (f x)))))

(defn map-async-unordered
  "Transform elements using an async function that returns a CompletionStage.
   Results are emitted as completed, order is not preserved.
   parallelism: maximum number of concurrent async operations"
  [src parallelism f]
  (op src mapAsyncUnordered (int parallelism)
      (reify org.apache.pekko.japi.function.Function
        (apply [_ x] (f x)))))

;; ---------------------------------------------------------------------------
;; Phase 2: Sub-streams
;; ---------------------------------------------------------------------------

(defn flat-map-concat
  "Transform each element into a Source and flatten the resulting sources
   sequentially (one at a time)."
  [src f]
  (op src flatMapConcat (reify org.apache.pekko.japi.function.Function
                          (apply [_ x] (f x)))))

(defn flat-map-merge
  "Transform each element into a Source and flatten with parallelism.
   breadth: maximum number of concurrent sub-streams"
  [src breadth f]
  (op src flatMapMerge (int breadth)
      (reify org.apache.pekko.japi.function.Function
        (apply [_ x] (f x)))))

(defn group-by
  "Partition the stream into sub-streams by key.
   max-substreams: maximum number of concurrent sub-streams
   key-fn: function to extract the key from each element
   Returns a SubFlow that can be transformed and then merged."
  [src max-substreams key-fn]
  (op src groupBy (int max-substreams)
      (reify org.apache.pekko.japi.function.Function
        (apply [_ x] (key-fn x)))))

(defn merge-substreams
  "Merge sub-streams back into a single stream.

   Takes whatever `group-by` returned: a SubSource (from a Source) or a SubFlow
   (from a Flow). Hinting only SubSource would ClassCastException on the Flow
   case, so this dispatches the same way the `op` macro does."
  [sub]
  (cond
    (instance? SubSource sub) (.mergeSubstreams ^SubSource sub)
    (instance? SubFlow sub)   (.mergeSubstreams ^SubFlow sub)
    :else (throw (IllegalArgumentException.
                  (str "merge-substreams expects the SubSource/SubFlow group-by returns, got "
                       (class sub))))))

(defn concat-substreams
  "Concatenate sub-streams sequentially. Takes a SubSource or a SubFlow — see
   `merge-substreams`."
  [sub]
  (cond
    (instance? SubSource sub) (.concatSubstreams ^SubSource sub)
    (instance? SubFlow sub)   (.concatSubstreams ^SubFlow sub)
    :else (throw (IllegalArgumentException.
                  (str "concat-substreams expects the SubSource/SubFlow group-by returns, got "
                       (class sub))))))

;; ---------------------------------------------------------------------------
;; Phase 3: Error Handling
;; ---------------------------------------------------------------------------

(defn recover
  "Emit a fallback element on failure and complete normally.
   pf is a function that takes an exception and returns a fallback value,
   or nil if the exception should not be recovered."
  [src pf]
  (op src recover
      (-> (PFBuilder.)
          (.match Throwable
                  (reify FI$Apply
                    (apply [_ ex]
                      (if-let [result (pf ex)]
                        result
                        (throw ex)))))
          (.build))))

(defn recover-with
  "Switch to an alternative source on failure.
   pf is a function that takes an exception and returns an alternative Source,
   or nil if the exception should not be recovered."
  [src pf]
  (op src recoverWith
      (-> (PFBuilder.)
          (.match Throwable
                  (reify FI$Apply
                    (apply [_ ex]
                      (if-let [result (pf ex)]
                        result
                        (throw ex)))))
          (.build))))

(defn recover-with-retries
  "Switch to an alternative source on failure with retry limit.
   attempts: maximum number of recovery attempts (-1 for infinite)
   pf is a function that takes an exception and returns an alternative Source."
  [src attempts pf]
  (op src recoverWithRetries (int attempts)
      (-> (PFBuilder.)
          (.match Throwable
                  (reify FI$Apply
                    (apply [_ ex]
                      (if-let [result (pf ex)]
                        result
                        (throw ex)))))
          (.build))))

;; ---------------------------------------------------------------------------
;; Phase 4: Time-based Operators
;; ---------------------------------------------------------------------------

(defn grouped-within
  "Batch elements by count OR time, whichever comes first.
   n: maximum batch size
   d: maximum time to wait, ms or a java.time.Duration"
  [src n d]
  (op src groupedWithin (int n) (->duration d)))

(defn take-within
  "Take elements for a duration (ms or a java.time.Duration) from stream start."
  [src d]
  (op src takeWithin (->duration d)))

(defn drop-within
  "Drop elements for a duration (ms or a java.time.Duration) from stream start."
  [src d]
  (op src dropWithin (->duration d)))

(defn keep-alive
  "Inject elements on idle to prevent timeout.
   d: maximum idle time before injecting, ms or a java.time.Duration
   inject-fn: function to create the element to inject"
  [src d inject-fn]
  (op src keepAlive (->duration d) (reify org.apache.pekko.japi.function.Creator
                                     (create [_] (inject-fn)))))

;; ---------------------------------------------------------------------------
;; Phase 5: Backpressure Strategies
;; ---------------------------------------------------------------------------

(defn batch
  "Aggregate fast upstream elements when downstream is slower.
   max: maximum number of elements to batch
   seed-fn: function to create the seed from the first element
   aggregate-fn: function to combine seed with next element"
  [src max seed-fn aggregate-fn]
  (op src batch (long max)
      (reify org.apache.pekko.japi.function.Function
        (apply [_ x] (seed-fn x)))
      (reify org.apache.pekko.japi.function.Function2
        (apply [_ seed elem] (aggregate-fn seed elem)))))

(defn conflate
  "Merge fast elements when downstream is slower.
   aggregate-fn: function to merge two elements into one"
  [src aggregate-fn]
  (op src conflate (reify org.apache.pekko.japi.function.Function2
                     (apply [_ a b] (aggregate-fn a b)))))

(defn conflate-with-seed
  "Conflate with a seed transformation for the first element.
   seed-fn: function to transform the first element into the seed
   aggregate-fn: function to merge seed with next element"
  [src seed-fn aggregate-fn]
  (op src conflateWithSeed
      (reify org.apache.pekko.japi.function.Function
        (apply [_ x] (seed-fn x)))
      (reify org.apache.pekko.japi.function.Function2
        (apply [_ seed elem] (aggregate-fn seed elem)))))

(defn expand
  "Extrapolate elements for slow downstream.
   extrapolate-fn: function that takes an element and returns an iterator
   of elements to emit until the next upstream element arrives"
  [src extrapolate-fn]
  (op src expand (reify org.apache.pekko.japi.function.Function
                   (apply [_ x]
                     (let [result (extrapolate-fn x)]
                       (if (instance? java.util.Iterator result)
                         result
                         (.iterator ^Iterable result)))))))

;; ---------------------------------------------------------------------------
;; Additional Sources
;; ---------------------------------------------------------------------------
;;
;; (The former "Phase 6: Graph DSL" junction builders — broadcast/balance/merge-n/
;; partition — were removed in H18: they returned raw GraphDSL junctions but this
;; ns exposes no GraphDSL to wire them into, so they were unusable as shipped.
;; `fan-out` and `balance-work` cover the common fan-out cases.)

(defn source-future
  "Create a Source that emits a single element from a CompletionStage."
  [^CompletionStage future]
  (Source/completionStage future))

(defn source-queue
  "Create a Source backed by a queue for dynamic pushing.

   Returns {:source Source, :queue SourceQueueWithComplete} — the same map shape
   `run-source-queue` and `source-actor-ref` use. Offer elements with
   (.offer queue x), finish with (.complete queue) or (.fail queue ex).

   buffer-size: size of the buffer
   overflow-strategy: :drop-head, :drop-tail, :drop-buffer, :drop-new, :fail, :backpressure

   Example:
     (let [{:keys [source queue]} (source-queue 8 :backpressure sys)] ...)"
  [buffer-size overflow-strategy materializer]
  (let [source (Source/queue (int buffer-size) (->overflow-strategy overflow-strategy :backpressure))
        ^Pair pair (.preMaterialize ^Source source (->materializer materializer))]
    {:source (.second pair) :queue (.first pair)}))

(defn source-cycle
  "Create a Source that infinitely cycles through a collection.

   An empty collection is a user error: the stream fails at run time with
   Pekko's own IllegalArgumentException (\"empty iterator\"), not an NPE."
  [coll]
  (Source/cycle (reify org.apache.pekko.japi.function.Creator
                  (create [_]
                    (let [^Iterable it (or (seq coll) [])]
                      (.iterator it))))))

(defn source-from-publisher
  "Create a Source from a Reactive Streams Publisher."
  [^Publisher publisher]
  (Source/fromPublisher publisher))

(defn source-maybe
  "Create a Source that can emit 0 or 1 elements.
   In javadsl the materialized value is a `CompletableFuture<Optional<T>>`:
   complete it with a present Optional to emit that one element, or an empty
   Optional to complete the stream with no element."
  []
  (Source/maybe))

;; ---------------------------------------------------------------------------
;; Phase 8: Additional Sinks
;; ---------------------------------------------------------------------------

(defn sink-reduce
  "Create a Sink that reduces elements without an initial value.
   Returns the final reduced value (or fails if empty)."
  [f]
  (Sink/reduce (reify org.apache.pekko.japi.function.Function2
                 (apply [_ a b] (f a b)))))

(defn sink-foreach-async
  "Create a Sink that runs an async side-effect for each element.
   parallelism: maximum number of concurrent async operations
   f: function that takes an element and returns a CompletionStage"
  [parallelism f]
  (Sink/foreachAsync (int parallelism)
                     (reify org.apache.pekko.japi.function.Function
                       (apply [_ x] (f x)))))

(defn sink-as-publisher
  "Create a Sink that exposes a Reactive Streams Publisher.
   fan-out: if true, allows multiple subscribers"
  [fan-out]
  (Sink/asPublisher (if fan-out
                      AsPublisher/WITH_FANOUT
                      AsPublisher/WITHOUT_FANOUT)))

(defn sink-queue
  "Create a Sink backed by a queue for pull-based consumption.
   Returns a SinkQueueWithCancel when materialized."
  []
  (Sink/queue))

;; ---------------------------------------------------------------------------
;; Phase 9: Utilities
;; ---------------------------------------------------------------------------

(defn log
  "Add logging to the stream for debugging.
   name: identifier for log messages"
  ([src ^String name]
   (op src log name))
  ([src ^String name extract-fn]
   (op src log name (reify org.apache.pekko.japi.function.Function
                      (apply [_ x] (extract-fn x))))))

(defn wire-tap
  "Send a copy of each element to a secondary sink without affecting the main flow."
  [src ^Sink sink]
  (op src wireTap sink))

(defn also-to
  "Send elements to a secondary sink while continuing the flow.
   Similar to wire-tap but with different backpressure semantics."
  [src sink]
  (op src alsoTo sink))

(defn dedupe
  "Drop **consecutive** duplicate elements, like clojure.core/dedupe.

   (Named `distinct` before N13, which was a misnomer: clojure.core/distinct drops
   every repeat, not just adjacent ones. `distinct`/`distinct-by` remain as
   deprecated aliases.)"
  [src]
  (op src statefulMapConcat
      (reify org.apache.pekko.japi.function.Creator
        (create [_]
          (let [prev (atom ::none)]
            (reify org.apache.pekko.japi.function.Function
              (apply [_ x]
                (if (= @prev x)
                  []
                  (do (reset! prev x)
                      [x])))))))))

(defn dedupe-by
  "Drop consecutive elements with the same key. See `dedupe`."
  [src key-fn]
  (op src statefulMapConcat
      (reify org.apache.pekko.japi.function.Creator
        (create [_]
          (let [prev-key (atom ::none)]
            (reify org.apache.pekko.japi.function.Function
              (apply [_ x]
                (let [k (key-fn x)]
                  (if (= @prev-key k)
                    []
                    (do (reset! prev-key k)
                        [x]))))))))))

(def ^{:deprecated "N13"
       :doc "Deprecated alias for `dedupe`. The name was wrong: this drops only
   *consecutive* duplicates, where clojure.core/distinct drops every repeat."
       :arglists '([src])}
  distinct dedupe)

(def ^{:deprecated "N13"
       :doc "Deprecated alias for `dedupe-by`. See `distinct`."
       :arglists '([src key-fn])}
  distinct-by dedupe-by)

(defn skeep
  "Map each element through `f`, dropping the elements `f` returns nil for —
   clojure.core/keep for streams.

   This is the Clojure-shaped version of Pekko's `collect`, which takes a Scala
   PartialFunction. One stage, not a map followed by a filter.

   Example:
     (skeep src #(when (even? %) (* 10 %)))"
  [src f]
  (op src mapConcat
      (reify org.apache.pekko.japi.function.Function
        (apply [_ x] (if-some [v (f x)] [v] [])))))

(defn zip
  "Combine with another Source element-by-element, emitting [a b] vectors.

   Completes as soon as either side does. Pekko emits a `japi.Pair`; this maps it
   to a Clojure vector, as `zip-with-index` does."
  [src other]
  (-> (op src zip other)
      (smap (fn [^Pair p] [(.first p) (.second p)]))))

(defn zip-all
  "Like `zip`, but runs until *both* sides complete, padding the shorter one.

   this-elem / that-elem are the padding values for this stream and for `other`."
  [src other this-elem that-elem]
  (-> (op src zipAll other this-elem that-elem)
      (smap (fn [^Pair p] [(.first p) (.second p)]))))

(defn interleave
  "Emit `segment-size` elements from this stream, then `segment-size` from
   `other`, and so on. Completes when both do."
  ([src other] (interleave src other 1))
  ([src other segment-size]
   (op src interleave other (int segment-size))))

(defn prepend
  "Emit every element of `other` first, then this stream's."
  [src other]
  (op src prepend other))

(defn or-else
  "Fall back to `other` if this stream completes without emitting anything.

   If this stream emits at least one element, `other` is never used."
  [src other]
  (op src orElse other))

(defn divert-to
  "Send the elements matching `pred` to `sink` instead of downstream.

   Unlike `wire-tap`/`also-to`, which copy, this *removes* the matching elements
   from the main flow — the usual shape for routing failures aside."
  [src sink pred]
  (op src divertTo sink
      (reify org.apache.pekko.japi.function.Predicate
        (test [_ x] (boolean (pred x))))))

(defn limit
  "Pass elements through, but fail the stream with a StreamLimitReachedException if
   there turn out to be more than `n`.

   Not `take`: `take` truncates quietly, this treats the overflow as an error. Use
   it as a guard before a collecting sink."
  [src n]
  (op src limit (int n)))

(defn zip-with-index
  "Pair each element with its index (starting from 0), as a Clojure vector
   [element index].

   Pekko emits a `japi.Pair` here; leaking that into a Clojure pipeline would make
   every downstream step do Java interop just to read the element."
  [src]
  (-> (op src zipWithIndex)
      (smap (fn [^Pair p] [(.first p) (.second p)]))))

(defn stateful-map
  "Apply a stateful transformation to each element.
   create-fn: no-arg function that returns initial state
   f: function (state, element) -> [new-state, emitted-element]"
  [src create-fn f]
  (op src statefulMapConcat
      (reify org.apache.pekko.japi.function.Creator
        (create [_]
          (let [state (atom (create-fn))]
            (reify org.apache.pekko.japi.function.Function
              (apply [_ x]
                (let [[new-state result] (f @state x)]
                  (reset! state new-state)
                  [result]))))))))

(defn watch-termination
  "Add a callback for when the stream terminates.
   f: function called with (materialized-value, completion-stage)"
  [src f]
  (op src watchTermination
      (reify org.apache.pekko.japi.function.Function2
        (apply [_ mat-value done]
          (f mat-value done)
          mat-value))))

(defn on-complete
  "Add a callback for when the stream completes (success or failure).
   f: function called with the completion (nil for success, exception for failure)"
  [src f]
  (-> src
      (watch-termination
       (fn [mat done]
         (.whenComplete ^CompletionStage done
                        (reify java.util.function.BiConsumer
                          (accept [_ _ ex]
                            (f ex))))
         mat))))

;; ---------------------------------------------------------------------------
;; High-level helpers (defined after utilities they depend on)
;; ---------------------------------------------------------------------------

(defn fan-out
  "High-level helper to fan-out a source to multiple sinks.
   Runs the source through a broadcast and connects to all sinks.
   Returns the materialized value of the last sink."
  [src sinks materializer]
  (let [n (count sinks)]
    (if (= n 1)
      ;; Single sink - just run directly
      (run-with src (first sinks) materializer)
      ;; Multiple sinks - use alsoTo chain
      (let [final-sink (last sinks)
            secondary-sinks (butlast sinks)]
        (-> (reduce (fn [s sink] (also-to s sink)) src secondary-sinks)
            (run-with final-sink materializer))))))

(defn balance-work
  "Worker pool pattern: distribute work across n workers.
   src: source of work items
   n: number of workers
   worker-fn: function to process each item (returns a Source)
   materializer: the materializer to use"
  [src n worker-fn materializer]
  (-> src
      (flat-map-merge n worker-fn)
      (run-to-seq materializer)))


;; ---------------------------------------------------------------------------
;; N1: Materialized values (toMat / viaMat / Keep)
;; ---------------------------------------------------------------------------

(defn keep-mat
  "Return the Keep combiner for a keyword: :left, :right, :both or :none.

   Used by via-mat / to-mat / run-mat to choose which materialized value(s) to
   retain. (Named keep-mat rather than `keep` to avoid shadowing clojure.core/keep.)"
  [which]
  (case which
    :left  (Keep/left)
    :right (Keep/right)
    :both  (Keep/both)
    :none  (Keep/none)
    (throw (IllegalArgumentException.
            (str "Unknown Keep combiner: " (pr-str which)
                 " — expected :left, :right, :both or :none")))))

(defn- mat-value
  "Unwrap a materialized value: a japi.Pair (from Keep/both) becomes [left right],
   anything else passes through."
  [v]
  (if (instance? org.apache.pekko.japi.Pair v)
    [(.first ^org.apache.pekko.japi.Pair v) (.second ^org.apache.pekko.japi.Pair v)]
    v))

(defn via-mat
  "Connect a Source to a Flow, combining their materialized values with `which`
   (:left, :right, :both or :none — see keep-mat).

   Unlike `via` (which always keeps the Source's value), this lets a Flow's
   materialized value — a KillSwitch, say — survive downstream.

   Example:
     (-> (source (range 100)) (via-mat (kill-switch-single) :right))"
  [src flow which]
  (op src viaMat flow (keep-mat which)))

(defn to-mat
  "Connect a Source to a Sink, combining their materialized values with `which`
   (:left, :right, :both or :none — see keep-mat). Returns a RunnableGraph;
   run it with run-graph.

   Example:
     (-> (source [1 2 3])
         (to-mat (sink-seq) :right)
         (run-graph mat)
         (await-completion))"
  [src sink which]
  (op src toMat sink (keep-mat which)))

(defn run-graph
  "Run a RunnableGraph (from to-mat), returning its materialized value.
   A Keep/both pair is returned as a Clojure vector [left right]."
  [^RunnableGraph graph materializer]
  (mat-value (.run graph (->materializer materializer))))

(defn run-mat
  "Run a Source into a Sink, keeping the materialized value(s) selected by `which`
   (:left, :right, :both or :none — see keep-mat). With :both the result is a
   Clojure vector [source-mat sink-mat] rather than a japi.Pair.

   `run` is the same as (run-mat src sink :right materializer).

   Example:
     (let [[queue done] (run-mat queued-src (sink-seq) :both mat)] ...)"
  [src sink which materializer]
  (mat-value (.run ^RunnableGraph (to-mat src sink which) (->materializer materializer))))

(defn run-source-queue
  "Materialize a queue-backed Source into `sink` in one step.

   Returns {:queue SourceQueueWithComplete, :done <sink's materialized value>} —
   for the usual sinks :done is a CompletionStage. Offer elements with
   (.offer queue x), finish with (.complete queue) or (.fail queue ex).

   buffer-size: size of the buffer
   overflow-strategy: :backpressure (default), :drop-head, :drop-tail,
                      :drop-buffer, :drop-new, :fail

   Example:
     (let [{:keys [queue done]} (run-source-queue 8 :backpressure (sink-seq) mat)]
       (.offer queue 1)
       (.complete queue)
       (await-completion done))"
  [buffer-size overflow-strategy sink materializer]
  (let [[queue done] (-> (Source/queue (int buffer-size)
                                       (->overflow-strategy overflow-strategy :backpressure))
                         (run-mat sink :both materializer))]
    {:queue queue :done done}))

(defn run-sink-queue
  "Run a Source into a queue-backed Sink for pull-based consumption.

   Returns {:queue SinkQueueWithCancel}. Pull elements with (.pull queue), which
   yields a CompletionStage<Optional> — empty once the stream completes. Stop
   early with (.cancel queue).

   Example:
     (let [{:keys [queue]} (run-sink-queue (source [1 2]) mat)]
       (.get (await-completion (.pull queue))))"
  [src materializer]
  {:queue (run-with src (sink-queue) materializer)})

;; ---------------------------------------------------------------------------
;; N1: KillSwitches
;; ---------------------------------------------------------------------------

(defn kill-switch-single
  "A Graph that materializes a UniqueKillSwitch controlling a single stream.
   Insert it with via-mat (keeping :right or :both) — or use via-kill-switch."
  []
  (KillSwitches/single))

(defn via-kill-switch
  "Insert a UniqueKillSwitch into a Source, making the kill switch the Source's
   materialized value (the upstream value is dropped).

   Combine with to-mat/run-mat to also keep the sink's value:
     (run-mat (via-kill-switch src) (sink-seq) :both mat)
     ;; => [kill-switch done]
   or use run-with-kill-switch, which does exactly that."
  [src]
  (via-mat src (kill-switch-single) :right))

(defn run-with-kill-switch
  "Run a Source into a Sink through a UniqueKillSwitch.

   Returns {:kill-switch UniqueKillSwitch, :done <sink's materialized value>}.
   Call (shutdown kill-switch) to complete the stream gracefully, or
   (abort kill-switch ex) to fail it.

   Example:
     (let [{:keys [kill-switch done]} (run-with-kill-switch (source-repeat 1)
                                                            (sink-ignore) mat)]
       (shutdown kill-switch)
       (await-completion done))"
  [src sink materializer]
  (let [[ks done] (run-mat (via-kill-switch src) sink :both materializer)]
    {:kill-switch ks :done done}))

(defn shared-kill-switch
  "Create a SharedKillSwitch that can control many streams at once.
   Insert it into each stream with shared-kill-switch-flow."
  ^SharedKillSwitch [^String name]
  (KillSwitches/shared name))

(defn shared-kill-switch-flow
  "The Flow to insert into a stream to place it under a SharedKillSwitch.
   Use with plain `via` — the switch is shared, so there is no per-stream
   materialized value worth keeping.

   Example:
     (let [ks (shared-kill-switch \"batch\")]
       (run-with (via src (shared-kill-switch-flow ks)) (sink-ignore) mat)
       (run-with (via src2 (shared-kill-switch-flow ks)) (sink-ignore) mat)
       (shutdown ks)) ;; stops both"
  [^SharedKillSwitch kill-switch]
  (.flow kill-switch))

(defn shutdown
  "Complete the stream(s) controlled by a KillSwitch gracefully (downstream sees
   normal completion). Works on both a UniqueKillSwitch and a SharedKillSwitch."
  [^KillSwitch kill-switch]
  (.shutdown kill-switch))

(defn abort
  "Fail the stream(s) controlled by a KillSwitch with the given exception.
   Works on both a UniqueKillSwitch and a SharedKillSwitch."
  [^KillSwitch kill-switch ^Throwable ex]
  (.abort kill-switch ex))

;; ---------------------------------------------------------------------------
;; N1: Supervision
;; ---------------------------------------------------------------------------

(defn- ->directive
  "Coerce :stop / :resume / :restart (or a Supervision.Directive) to a Directive."
  [v]
  (case v
    :stop    (Supervision/stop)
    :resume  (Supervision/resume)
    :restart (Supervision/restart)
    (if (instance? org.apache.pekko.stream.Supervision$Directive v)
      v
      (throw (IllegalArgumentException.
              (str "Unknown supervision directive: " (pr-str v)
                   " — expected :stop, :resume or :restart"))))))

(defn supervision-strategy
  "Build stream Attributes from a decider fn of Throwable -> :stop, :resume or
   :restart (nil means :stop, matching Pekko's default).

   :stop    fail the stream (the default)
   :resume  drop the offending element and continue
   :restart drop the element and reset the stage's state

   Apply with with-attributes, or use with-supervision."
  [decider-fn]
  (ActorAttributes/withSupervisionStrategy
   (reify org.apache.pekko.japi.function.Function
     (apply [_ ex]
       (->directive (or (decider-fn ex) :stop))))))

(defn with-attributes
  "Apply Attributes to a Source, Flow or Sink."
  [src ^Attributes attributes]
  (op src withAttributes attributes))

(defn with-supervision
  "Supervise a Source or Flow with a decider fn of Throwable -> :stop, :resume or
   :restart (see supervision-strategy).

   The decider applies to the stages it wraps, so place it after the operators it
   should cover.

   Example — skip elements that throw, instead of failing the stream:
     (-> (source [1 0 2])
         (smap #(/ 10 %))
         (with-supervision (fn [ex] (when (instance? ArithmeticException ex) :resume)))
         (run-to-seq mat))
     ;; => [10 5]"
  [src decider-fn]
  (with-attributes src (supervision-strategy decider-fn)))

(defn restart-settings
  "Build RestartSettings for the restart-* wrappers.

   Options (durations are java.time.Duration or milliseconds):
   - :min-backoff         delay before the first restart (default 100ms)
   - :max-backoff         cap on the exponential backoff (default 5000ms)
   - :random-factor       jitter, 0.0-1.0 (default 0.2)
   - :max-restarts        give up after this many restarts; requires
                          :max-restarts-within
   - :max-restarts-within window over which :max-restarts is counted
   - :restart-on          fn of Throwable -> truthy to restart (default: all)"
  ^RestartSettings [{:keys [min-backoff max-backoff random-factor
                            max-restarts max-restarts-within restart-on]
                     :or   {min-backoff 100 max-backoff 5000 random-factor 0.2}}]
  (cond-> (RestartSettings/create (->duration min-backoff)
                                  (->duration max-backoff)
                                  (double random-factor))
    max-restarts (.withMaxRestarts (int max-restarts)
                                   (->duration (or max-restarts-within max-backoff)))
    restart-on   (.withRestartOn (reify java.util.function.Predicate
                                   (test [_ ex] (boolean (restart-on ex)))))))

(defn restart-source
  "A Source that restarts the wrapped Source with exponential backoff when it
   completes OR fails. `f` is a no-arg fn returning a Source; it is called again
   on each restart. Materializes to NotUsed.

   opts: see restart-settings.

   Example:
     (restart-source {:min-backoff 100 :max-restarts 3 :max-restarts-within 5000}
                     #(source (fetch-page!)))"
  [opts f]
  (RestartSource/withBackoff (restart-settings opts)
                             (reify org.apache.pekko.japi.function.Creator
                               (create [_] (f)))))

(defn restart-source-on-failures
  "Like restart-source, but only restarts on failure — normal completion of the
   wrapped Source completes the stream."
  [opts f]
  (RestartSource/onFailuresWithBackoff (restart-settings opts)
                                       (reify org.apache.pekko.japi.function.Creator
                                         (create [_] (f)))))

(defn restart-flow
  "A Flow that restarts the wrapped Flow with exponential backoff when it
   completes OR fails. `f` is a no-arg fn returning a Flow. Materializes to NotUsed.

   opts: see restart-settings."
  [opts f]
  (RestartFlow/withBackoff (restart-settings opts)
                           (reify org.apache.pekko.japi.function.Creator
                             (create [_] (f)))))

(defn restart-flow-on-failures
  "Like restart-flow, but only restarts on failure."
  [opts f]
  (RestartFlow/onFailuresWithBackoff (restart-settings opts)
                                     (reify org.apache.pekko.japi.function.Creator
                                       (create [_] (f)))))

(defn restart-sink
  "A Sink that restarts the wrapped Sink with exponential backoff when it
   completes or cancels. `f` is a no-arg fn returning a Sink. Materializes to NotUsed.

   opts: see restart-settings."
  [opts f]
  (RestartSink/withBackoff (restart-settings opts)
                           (reify org.apache.pekko.japi.function.Creator
                             (create [_] (f)))))

(defn retry-flow
  "Wrap a Flow so failed results are retried with exponential backoff.

   Unlike restart-flow (which restarts the whole stage on stream failure),
   retry-flow retries individual elements based on their *result*: decide-fn is
   called with [in out] and returns the next input to retry with, or nil to accept
   `out` and move on.

   opts (durations are java.time.Duration or milliseconds):
   - :min-backoff   delay before the first retry (default 100ms)
   - :max-backoff   cap on the exponential backoff (default 5000ms)
   - :random-factor jitter, 0.0-1.0 (default 0.2)
   - :max-retries   give up after this many retries (default 3)

   The wrapped flow must emit exactly one output per input.

   Example — retry until the call stops returning :error:
     (retry-flow {:max-retries 3} call-flow
                 (fn [in out] (when (= :error out) in)))"
  [{:keys [min-backoff max-backoff random-factor max-retries]
    :or   {min-backoff 100 max-backoff 5000 random-factor 0.2 max-retries 3}}
   flow decide-fn]
  (RetryFlow/withBackoff (->duration min-backoff)
                         (->duration max-backoff)
                         (double random-factor)
                         (int max-retries)
                         flow
                         (reify org.apache.pekko.japi.function.Function2
                           (apply [_ in out]
                             (if-let [retry (decide-fn in out)]
                               (Optional/of retry)
                               (Optional/empty))))))

;; ---------------------------------------------------------------------------
;; N1: Flows and actor interop
;; ---------------------------------------------------------------------------

(defn flow
  "An identity Flow — the starting point for building a standalone Flow to pass to
   `via`, restart-flow or retry-flow.

   Example:
     (-> (flow) (smap inc) (sfilter even?))"
  []
  (Flow/create))

(defn flow-of
  "An identity Flow declared over a specific element Class.
   Only needed where Pekko's Java DSL requires the element type."
  [^Class klass]
  (Flow/of klass))

(defn flow-from-fn
  "A single-operation Flow that applies f to each element."
  [f]
  (Flow/fromFunction (reify org.apache.pekko.japi.function.Function
                       (apply [_ x] (f x)))))

(defn ask
  "Ask an actor per element, emitting its reply — the streaming equivalent of
   pekko-clj.core/<?>, with backpressure.

   Replies must be instances of reply-class or the stream fails. The actor must
   reply via (core/reply ...) / sender; a timeout fails the stream.

   parallelism: elements in flight at once (default 1). Order is always preserved.
   timeout: java.time.Duration or milliseconds.

   Works on a Source or a Flow.

   Example:
     (-> (source [1 2 3])
         (ask worker Long 3000)
         (run-to-seq mat))"
  ([src actor-ref reply-class timeout]
   (op src ask actor-ref reply-class (Timeout/create (->duration timeout))))
  ([src parallelism actor-ref reply-class timeout]
   (op src ask (int parallelism) actor-ref reply-class (Timeout/create (->duration timeout)))))

(defn- unwrap-status-reply
  [^StatusReply reply]
  (if (.isError reply)
    (throw (.getError reply))
    (.getValue reply)))

(defn ask-with-status
  "Like `ask`, but the actor replies with an org.apache.pekko.pattern.StatusReply:
   a success reply is unwrapped to its value, an error reply fails the stream.

   Note: Pekko 1.6.0 has no Flow.askWithStatus (it is an Akka-only API), so this
   asks for a StatusReply and unwraps it — same semantics, one extra stage.

   Example — actor replies (core/reply (StatusReply/success 42))
             or             (core/reply (StatusReply/error \"nope\")):
     (-> (source [1]) (ask-with-status worker 3000) (run-to-seq mat))"
  ([src actor-ref timeout]
   (-> (ask src actor-ref StatusReply timeout)
       (smap unwrap-status-reply)))
  ([src parallelism actor-ref timeout]
   (-> (ask src parallelism actor-ref StatusReply timeout)
       (smap unwrap-status-reply))))

;; ---------------------------------------------------------------------------
;; N14: File & blocking-IO integration (FileIO, StreamConverters, framing)
;; ---------------------------------------------------------------------------

(defn ->byte-string
  "Coerce to a Pekko ByteString — the element type of the file/IO streams below.

   Accepts a String (encoded UTF-8), a byte-array, or an existing ByteString
   (returned unchanged). Throws on anything else."
  ^ByteString [x]
  (cond
    (instance? ByteString x) x
    (string? x)              (ByteString/fromString ^String x)
    (bytes? x)               (ByteString/fromArray ^bytes x)
    :else (throw (IllegalArgumentException.
                  (str "Cannot coerce to ByteString: " (pr-str x)
                       " — expected a String, a byte-array or a ByteString.")))))

(defn byte-string->string
  "Decode a ByteString to a String (UTF-8) — the inverse of `->byte-string` on a
   String."
  ^String [^ByteString bs]
  (.utf8String bs))

(defn byte-string->bytes
  "The raw contents of a ByteString as a byte-array."
  ^bytes [^ByteString bs]
  (.toArray bs))

(defn io-result->map
  "Convert an IOResult — the materialized value of the FileIO / StreamConverters
   streams — to a map {:count <bytes>, :success? bool, :error <Throwable or nil>}.

   `count` is the number of bytes read or written; `error` is nil on success."
  [^IOResult result]
  (let [ok (.wasSuccessful result)]
    {:count    (.getCount result)
     :success? ok
     :error    (when-not ok (.getError result))}))

(defn- ->path
  "Coerce a String, java.io.File or java.nio.file.Path to a Path."
  ^Path [p]
  (cond
    (instance? Path p) p
    (instance? File p) (.toPath ^File p)
    (string? p)        (.toPath (File. ^String p))
    :else (throw (IllegalArgumentException.
                  (str "Cannot coerce to a file Path: " (pr-str p)
                       " — expected a String, java.io.File or java.nio.file.Path.")))))

(defn- ->open-option
  "Coerce a keyword or a java.nio.file.OpenOption to a StandardOpenOption."
  [o]
  (if (instance? java.nio.file.OpenOption o)
    o
    (case o
      :append            StandardOpenOption/APPEND
      :create            StandardOpenOption/CREATE
      :create-new        StandardOpenOption/CREATE_NEW
      :truncate-existing StandardOpenOption/TRUNCATE_EXISTING
      :write             StandardOpenOption/WRITE
      :read              StandardOpenOption/READ
      :sync              StandardOpenOption/SYNC
      :dsync             StandardOpenOption/DSYNC
      (throw (IllegalArgumentException.
              (str "Unknown open option: " (pr-str o)
                   ". Valid options: :append, :create, :create-new, "
                   ":truncate-existing, :write, :read, :sync, :dsync "
                   "(or a java.nio.file.OpenOption)."))))))

(defn source-from-file
  "A Source that reads a file as a stream of ByteString chunks.

   `file` is a String path, a java.io.File or a java.nio.file.Path. The stream's
   materialized value is a CompletionStage<IOResult> (see `io-result->map`) that
   completes with the number of bytes read; keep it with `run-mat`/`to-mat` and a
   :left/:both Keep to observe it.

   chunk-size: bytes per emitted ByteString (default 8192).

   Example:
     (-> (source-from-file \"/etc/hosts\")
         (via (lines))
         (run-to-seq sys))"
  ([file] (FileIO/fromPath (->path file)))
  ([file chunk-size] (FileIO/fromPath (->path file) (int chunk-size))))

(defn sink-to-file
  "A Sink that writes a stream of ByteString to a file, materializing to a
   CompletionStage<IOResult> (see `io-result->map`) with the number of bytes
   written.

   `file` is a String path, a java.io.File or a java.nio.file.Path. With one
   argument the file is created (or truncated) and written; the 2-arity takes a
   collection of open options — keywords (:append, :create, :create-new,
   :truncate-existing, :write, :read, :sync, :dsync) or java.nio.file.OpenOption
   values — e.g. [:create :append] to append.

   Example:
     (run-with (source [(->byte-string \"line1\\n\") (->byte-string \"line2\\n\")])
               (sink-to-file \"/tmp/out.txt\")
               sys)"
  ([file] (FileIO/toPath (->path file)))
  ([file open-options]
   (FileIO/toPath (->path file)
                  ^java.util.Set (into #{} (map ->open-option) open-options))))

(defn source-from-input-stream
  "A Source of ByteString reading from a java.io.InputStream produced by `factory-fn`
   (a no-arg fn, called once when the stream runs). Blocking reads run on the
   dedicated blocking-IO dispatcher. Materializes to a CompletionStage<IOResult>.

   chunk-size: bytes per emitted ByteString (default 8192).

   Example:
     (source-from-input-stream #(io/input-stream (io/resource \"data.bin\")))"
  ([factory-fn]
   (StreamConverters/fromInputStream
    (reify org.apache.pekko.japi.function.Creator (create [_] (factory-fn)))))
  ([factory-fn chunk-size]
   (StreamConverters/fromInputStream
    (reify org.apache.pekko.japi.function.Creator (create [_] (factory-fn)))
    (int chunk-size))))

(defn sink-to-output-stream
  "A Sink writing ByteString elements to a java.io.OutputStream produced by
   `factory-fn` (a no-arg fn, called once when the stream runs). Materializes to a
   CompletionStage<IOResult>.

   auto-flush?: flush the stream after each element (default false)."
  ([factory-fn]
   (StreamConverters/fromOutputStream
    (reify org.apache.pekko.japi.function.Creator (create [_] (factory-fn)))))
  ([factory-fn auto-flush?]
   (StreamConverters/fromOutputStream
    (reify org.apache.pekko.japi.function.Creator (create [_] (factory-fn)))
    (boolean auto-flush?))))

(defn sink-as-input-stream
  "A Sink whose materialized value is a java.io.InputStream that pulls ByteString
   elements from the stream as bytes — a blocking bridge OUT of a stream.

   The optional read timeout is a java.time.Duration or milliseconds (default 5s);
   a read blocks up to that long for the next element before failing."
  ([] (StreamConverters/asInputStream))
  ([read-timeout] (StreamConverters/asInputStream (->duration read-timeout))))

(defn source-as-output-stream
  "A Source whose materialized value is a java.io.OutputStream; bytes written to it
   are emitted from the stream as ByteString — a blocking bridge INTO a stream.

   The optional write timeout is a java.time.Duration or milliseconds (default 5s);
   a write blocks up to that long for downstream demand before failing."
  ([] (StreamConverters/asOutputStream))
  ([write-timeout] (StreamConverters/asOutputStream (->duration write-timeout))))

(defn frame-delimiter
  "A framing Flow<ByteString, ByteString> that chunks a byte stream into frames
   separated by `delimiter` (a String or ByteString), emitting each frame with the
   delimiter stripped.

   max-frame-length bounds a single frame; a longer one fails the stream.
   allow-truncation? (default true) decides whether a final frame with no trailing
   delimiter is emitted (true) or dropped as truncated (false)."
  ([delimiter max-frame-length] (frame-delimiter delimiter max-frame-length true))
  ([delimiter max-frame-length allow-truncation?]
   (Framing/delimiter (->byte-string delimiter) (int max-frame-length)
                      (if allow-truncation? FramingTruncation/ALLOW FramingTruncation/DISALLOW))))

(defn lines
  "A Flow<ByteString, String> that splits a byte stream into lines: it frames on
   \\n (the newline is stripped) and decodes each frame as UTF-8.

   Note: only \\n is treated as the separator, so a CRLF file leaves a trailing
   \\r on each line — use `frame-delimiter` with \"\\r\\n\" for those.

   max-line-length bounds a single line (default 65536).

   Example:
     (-> (source-from-file \"names.txt\") (via (lines)) (run-to-seq sys))"
  ([] (lines 65536))
  ([max-line-length]
   (smap (frame-delimiter "\n" max-line-length) byte-string->string)))
