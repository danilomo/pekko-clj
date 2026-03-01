(ns pekko-clj.stream
  "Pekko Streams support for pekko-clj.

   Provides a functional API for building and running reactive streams
   with backpressure support.

   Core concepts:
   - Source: produces elements (from collections, actors, etc.)
   - Flow: transforms elements (map, filter, etc.)
   - Sink: consumes elements (foreach, fold, to actors, etc.)

   Example:
     (-> (source (range 100))
         (smap inc)
         (sfilter even?)
         (run-foreach println sys))"
  (:refer-clojure :exclude [concat drop drop-while map filter mapcat take take-while merge distinct partition group-by])
  (:import [org.apache.pekko.stream Materializer OverflowStrategy ClosedShape ClosedShape$
                                    FlowShape Graph SourceShape SinkShape]
           [org.apache.pekko.stream.javadsl Source Flow Sink Keep RunnableGraph
                                            AsPublisher SinkQueueWithCancel
                                            SourceQueueWithComplete GraphDSL GraphDSL$Builder
                                            Broadcast Balance Merge Partition SubSource]
           [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko NotUsed Done]
           [org.apache.pekko.japi Pair]
           [org.apache.pekko.japi.pf PFBuilder FI$Apply]
           [java.util.concurrent CompletionStage CompletableFuture]
           [java.util Optional]
           [java.time Duration]
           [org.reactivestreams Publisher]
           [org.apache.pekko.event Logging LoggingAdapter]))

;; ---------------------------------------------------------------------------
;; Materializer
;; ---------------------------------------------------------------------------

(defn materializer
  "Create a Materializer from an ActorSystem.
   The materializer is used to run streams."
  [^ActorSystem system]
  (Materializer/createMaterializer system))

;; ---------------------------------------------------------------------------
;; Sources
;; ---------------------------------------------------------------------------

(defn source
  "Create a Source from a Clojure collection or sequence."
  [coll]
  (Source/from (seq coll)))

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
   initial-delay: Duration before first element
   interval: Duration between elements
   element: The element to emit"
  [^Duration initial-delay ^Duration interval element]
  (Source/tick initial-delay interval element))

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
   f is a no-arg function that returns a Source."
  [f]
  (Source/lazily
   (reify org.apache.pekko.japi.function.Creator
     (create [_] (f)))))

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
  (.map src (reify org.apache.pekko.japi.function.Function
              (apply [_ x] (f x)))))

(defn sfilter
  "Filter elements using a predicate. (Named sfilter to avoid clash with clojure.core/filter)"
  [src pred]
  (.filter src (reify org.apache.pekko.japi.function.Predicate
                 (test [_ x] (boolean (pred x))))))

(defn mapcat
  "Transform each element to zero or more elements."
  [src f]
  (.mapConcat src (reify org.apache.pekko.japi.function.Function
                    (apply [_ x] (seq (f x))))))

(defn take
  "Take only the first n elements."
  [src n]
  (.take src (long n)))

(defn drop
  "Drop the first n elements."
  [src n]
  (.drop src (long n)))

(defn take-while
  "Take elements while predicate is true."
  [src pred]
  (.takeWhile src (reify org.apache.pekko.japi.function.Predicate
                    (test [_ x] (boolean (pred x))))))

(defn drop-while
  "Drop elements while predicate is true."
  [src pred]
  (.dropWhile src (reify org.apache.pekko.japi.function.Predicate
                    (test [_ x] (boolean (pred x))))))

(defn grouped
  "Group elements into vectors of n elements."
  [src n]
  (.grouped src (int n)))

(defn sliding
  "Create sliding windows of n elements."
  ([src n] (sliding src n 1))
  ([src n step]
   (.sliding src (int n) (int step))))

(defn scan
  "Fold over elements, emitting each intermediate result."
  [src initial f]
  (.scan src initial (reify org.apache.pekko.japi.function.Function2
                       (apply [_ acc x] (f acc x)))))

(defn fold
  "Fold over elements, emitting only the final result."
  [src initial f]
  (.fold src initial (reify org.apache.pekko.japi.function.Function2
                       (apply [_ acc x] (f acc x)))))

(defn intersperse
  "Insert an element between each pair of elements."
  [src separator]
  (.intersperse src separator))

(defn throttle
  "Limit the rate of elements.
   elements: number of elements
   per: Duration for the rate limit"
  [src elements ^Duration per]
  (.throttle src (int elements) per))

(defn delay-each
  "Delay each element by the given duration."
  [src ^Duration duration]
  (.delay src duration))

(defn buffer
  "Buffer elements when downstream is slower.
   size: buffer size
   strategy: :drop-head, :drop-tail, :drop-buffer, :drop-new, :fail"
  [src size strategy]
  (let [overflow-strategy
        (case strategy
          :drop-head   (OverflowStrategy/dropHead)
          :drop-tail   (OverflowStrategy/dropTail)
          :drop-buffer (OverflowStrategy/dropBuffer)
          :drop-new    (OverflowStrategy/dropNew)
          :fail        (OverflowStrategy/fail)
          (OverflowStrategy/dropNew))]
    (.buffer src (int size) overflow-strategy)))

(defn async
  "Run the previous stages asynchronously."
  [src]
  (.async src))

(defn via
  "Connect a Source to a Flow."
  [src flow]
  (.via src flow))

(defn concat
  "Concatenate another source after this one completes."
  [src other-src]
  (.concat src other-src))

(defn merge
  "Merge elements from another source."
  [src other-src]
  (.merge src other-src))

(defn zip-with
  "Zip with another source using a combining function."
  [src other-src f]
  (.zipWith src other-src
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
   Note: This sink materializes to NotUsed, not a CompletionStage."
  [^ActorRef actor-ref on-complete-msg]
  (Sink/actorRef actor-ref on-complete-msg))

;; ---------------------------------------------------------------------------
;; Running streams
;; ---------------------------------------------------------------------------

(defn run
  "Run a stream with a Sink, returning a CompletionStage of the materialized value."
  [src sink materializer]
  (.run (.toMat src sink (Keep/right)) materializer))

(defn run-with
  "Run a stream with a Sink, returning a CompletionStage of the materialized value."
  [src sink materializer]
  (.runWith src sink materializer))

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

(defn source-actor-ref
  "Create a Source backed by an actor that you can send messages to.
   Returns [source actor-ref].

   buffer-size: size of the buffer
   overflow-strategy: :drop-head, :drop-tail, :drop-buffer, :drop-new, :fail

   Send messages to the actor-ref to emit them from the source.
   Send org.apache.pekko.actor.Status$Success to complete the stream."
  [buffer-size overflow-strategy materializer]
  (let [overflow (case overflow-strategy
                   :drop-head   (OverflowStrategy/dropHead)
                   :drop-tail   (OverflowStrategy/dropTail)
                   :drop-buffer (OverflowStrategy/dropBuffer)
                   :drop-new    (OverflowStrategy/dropNew)
                   :fail        (OverflowStrategy/fail)
                   (OverflowStrategy/fail))
        source (Source/actorRef (int buffer-size) overflow)
        ;; Materialize to get the ActorRef
        [src actor-ref] (let [pair (.preMaterialize source materializer)]
                          [(.first pair) (.second pair)])]
    [src actor-ref]))

(defn to-actor
  "Connect a Source to an actor, sending each element as a message.
   complete-msg: message to send when stream completes
   Note: Returns NotUsed, not a CompletionStage. The stream runs asynchronously."
  [src ^ActorRef actor-ref complete-msg materializer]
  (.run (.to src (sink-actor-ref actor-ref complete-msg)) materializer))

;; ---------------------------------------------------------------------------
;; Utility functions
;; ---------------------------------------------------------------------------

(defn await-completion
  "Block until a CompletionStage completes, returning its value.
   timeout-ms: maximum time to wait in milliseconds"
  [^CompletionStage stage timeout-ms]
  (try
    (.get (.toCompletableFuture stage) timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    (catch java.util.concurrent.TimeoutException _
      (throw (ex-info "Stream completion timed out" {:timeout-ms timeout-ms})))))

(defn completion->promise
  "Convert a CompletionStage to a Clojure promise."
  [^CompletionStage stage]
  (let [p (promise)]
    (.whenComplete stage
                   (reify java.util.function.BiConsumer
                     (accept [_ result exception]
                       (if exception
                         (deliver p {:error exception})
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
  (.mapAsync src (int parallelism)
             (reify org.apache.pekko.japi.function.Function
               (apply [_ x] (f x)))))

(defn map-async-unordered
  "Transform elements using an async function that returns a CompletionStage.
   Results are emitted as completed, order is not preserved.
   parallelism: maximum number of concurrent async operations"
  [src parallelism f]
  (.mapAsyncUnordered src (int parallelism)
                      (reify org.apache.pekko.japi.function.Function
                        (apply [_ x] (f x)))))

;; ---------------------------------------------------------------------------
;; Phase 2: Sub-streams
;; ---------------------------------------------------------------------------

(defn flat-map-concat
  "Transform each element into a Source and flatten the resulting sources
   sequentially (one at a time)."
  [src f]
  (.flatMapConcat src (reify org.apache.pekko.japi.function.Function
                        (apply [_ x] (f x)))))

(defn flat-map-merge
  "Transform each element into a Source and flatten with parallelism.
   breadth: maximum number of concurrent sub-streams"
  [src breadth f]
  (.flatMapMerge src (int breadth)
                 (reify org.apache.pekko.japi.function.Function
                   (apply [_ x] (f x)))))

(defn group-by
  "Partition the stream into sub-streams by key.
   max-substreams: maximum number of concurrent sub-streams
   key-fn: function to extract the key from each element
   Returns a SubFlow that can be transformed and then merged."
  [src max-substreams key-fn]
  (.groupBy src (int max-substreams)
            (reify org.apache.pekko.japi.function.Function
              (apply [_ x] (key-fn x)))))

(defn merge-substreams
  "Merge sub-streams back into a single stream."
  [^SubSource subsource]
  (.mergeSubstreams subsource))

(defn concat-substreams
  "Concatenate sub-streams sequentially."
  [^SubSource subsource]
  (.concatSubstreams subsource))

;; ---------------------------------------------------------------------------
;; Phase 3: Error Handling
;; ---------------------------------------------------------------------------

(defn recover
  "Emit a fallback element on failure and complete normally.
   pf is a function that takes an exception and returns a fallback value,
   or nil if the exception should not be recovered."
  [src pf]
  (.recover src
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
  (.recoverWith src
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
  (.recoverWithRetries src (int attempts)
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
   d: maximum duration to wait"
  [src n ^Duration d]
  (.groupedWithin src (int n) d))

(defn take-within
  "Take elements for a duration from stream start."
  [src ^Duration d]
  (.takeWithin src d))

(defn drop-within
  "Drop elements for a duration from stream start."
  [src ^Duration d]
  (.dropWithin src d))

(defn keep-alive
  "Inject elements on idle to prevent timeout.
   d: maximum idle time before injecting
   inject-fn: function to create the element to inject"
  [src ^Duration d inject-fn]
  (.keepAlive src d (reify org.apache.pekko.japi.function.Creator
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
  (.batch src (long max)
          (reify org.apache.pekko.japi.function.Function
            (apply [_ x] (seed-fn x)))
          (reify org.apache.pekko.japi.function.Function2
            (apply [_ seed elem] (aggregate-fn seed elem)))))

(defn conflate
  "Merge fast elements when downstream is slower.
   aggregate-fn: function to merge two elements into one"
  [src aggregate-fn]
  (.conflate src (reify org.apache.pekko.japi.function.Function2
                   (apply [_ a b] (aggregate-fn a b)))))

(defn conflate-with-seed
  "Conflate with a seed transformation for the first element.
   seed-fn: function to transform the first element into the seed
   aggregate-fn: function to merge seed with next element"
  [src seed-fn aggregate-fn]
  (.conflateWithSeed src
                     (reify org.apache.pekko.japi.function.Function
                       (apply [_ x] (seed-fn x)))
                     (reify org.apache.pekko.japi.function.Function2
                       (apply [_ seed elem] (aggregate-fn seed elem)))))

(defn expand
  "Extrapolate elements for slow downstream.
   extrapolate-fn: function that takes an element and returns an iterator
   of elements to emit until the next upstream element arrives"
  [src extrapolate-fn]
  (.expand src (reify org.apache.pekko.japi.function.Function
                 (apply [_ x]
                   (let [result (extrapolate-fn x)]
                     (if (instance? java.util.Iterator result)
                       result
                       (.iterator ^Iterable result)))))))

;; ---------------------------------------------------------------------------
;; Phase 6: Graph DSL
;; ---------------------------------------------------------------------------

(defn broadcast
  "Create a Broadcast junction that fans out to all outputs.
   n: number of output ports"
  [n]
  (Broadcast/create (int n)))

(defn balance
  "Create a Balance junction for load-balancing fan-out.
   n: number of output ports"
  [n]
  (Balance/create (int n)))

(defn merge-n
  "Create a Merge junction that combines n inputs.
   n: number of input ports"
  [n]
  (Merge/create (int n)))

(defn partition
  "Create a Partition junction for conditional routing.
   n: number of output ports
   partition-fn: function that returns the output port index (0 to n-1)"
  [n partition-fn]
  (Partition/create (int n)
                    (reify org.apache.pekko.japi.function.Function
                      (apply [_ x] (int (partition-fn x))))))

;; ---------------------------------------------------------------------------
;; Phase 7: Additional Sources
;; ---------------------------------------------------------------------------

(defn source-future
  "Create a Source that emits a single element from a CompletionStage."
  [^CompletionStage future]
  (Source/completionStage future))

(defn source-queue
  "Create a Source backed by a queue for dynamic pushing.
   Returns [queue source] where queue is a SourceQueueWithComplete.

   buffer-size: size of the buffer
   overflow-strategy: :drop-head, :drop-tail, :drop-buffer, :drop-new, :fail, :backpressure"
  [buffer-size overflow-strategy materializer]
  (let [overflow (case overflow-strategy
                   :drop-head    (OverflowStrategy/dropHead)
                   :drop-tail    (OverflowStrategy/dropTail)
                   :drop-buffer  (OverflowStrategy/dropBuffer)
                   :drop-new     (OverflowStrategy/dropNew)
                   :fail         (OverflowStrategy/fail)
                   :backpressure (OverflowStrategy/backpressure)
                   (OverflowStrategy/backpressure))
        source (Source/queue (int buffer-size) overflow)
        pair (.preMaterialize source materializer)]
    [(.first pair) (.second pair)]))

(defn source-cycle
  "Create a Source that infinitely cycles through a collection."
  [coll]
  (Source/cycle (reify org.apache.pekko.japi.function.Creator
                  (create [_] (.iterator ^Iterable (seq coll))))))

(defn source-from-publisher
  "Create a Source from a Reactive Streams Publisher."
  [^Publisher publisher]
  (Source/fromPublisher publisher))

(defn source-maybe
  "Create a Source that can emit 0 or 1 elements.
   The materialized value is a Promise that must be completed with
   an Optional."
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
                      (AsPublisher/WITH_FANOUT)
                      (AsPublisher/WITHOUT_FANOUT))))

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
  ([src name]
   (.log src name))
  ([src name extract-fn]
   (.log src name (reify org.apache.pekko.japi.function.Function
                    (apply [_ x] (extract-fn x))))))

(defn wire-tap
  "Send a copy of each element to a secondary sink without affecting the main flow."
  [src sink]
  (.wireTap src sink))

(defn also-to
  "Send elements to a secondary sink while continuing the flow.
   Similar to wire-tap but with different backpressure semantics."
  [src sink]
  (.alsoTo src sink))

(defn distinct
  "Remove consecutive duplicate elements."
  [src]
  (.statefulMapConcat src
                      (reify org.apache.pekko.japi.function.Creator
                        (create [_]
                          (let [prev (atom ::none)]
                            (reify org.apache.pekko.japi.function.Function
                              (apply [_ x]
                                (if (= @prev x)
                                  []
                                  (do (reset! prev x)
                                      [x])))))))))

(defn distinct-by
  "Remove consecutive duplicate elements by a key function."
  [src key-fn]
  (.statefulMapConcat src
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

(defn zip-with-index
  "Pair each element with its index (starting from 0)."
  [src]
  (.zipWithIndex src))

(defn stateful-map
  "Apply a stateful transformation to each element.
   create-fn: no-arg function that returns initial state
   f: function (state, element) -> [new-state, emitted-element]"
  [src create-fn f]
  (.statefulMapConcat src
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
  (.watchTermination src
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
