---
title: Reactive Streams
---
# Reactive Streams

Pekko Streams is an implementation of Reactive Streams, allowing you to build complex, asynchronous, back-pressured data pipelines.

`pekko-clj` wraps the object-oriented Java/Scala APIs of Pekko Streams with functional sequence-style transformations, meaning you can write flow topologies using a threading macro (`->`) just like standard Clojure collection processing.

## Streams API

In a reactive stream graph:
- **Source**: Emits data elements (has exactly 1 output).
- **Flow**: Transforms data elements (1 input, 1 output).
- **Sink**: Terminates the stream and consumes data (1 input).

### In `pekko-clj`

Instead of creating separate instances for each stage and plumbing them together, `pekko-clj` provides chained operations like `smap`, `sfilter`, and `sfold`. These naturally construct a runnable graph.

```clojure
(ns my-app.streams
  (:require [pekko-clj.stream :as s]))

;; Basic sequence stream calculation
(def result-future
  (-> (s/source (range 1 6))            ;; Emits 1, 2, 3, 4, 5
      (s/smap #(* % 10))                ;; Map:   10, 20, 30, 40, 50
      (s/sfilter #(not= 30 %))          ;; Filter: 10, 20, 40, 50
      (s/sfold 0 +)                     ;; Fold into aggregate sum
      (s/run sys)))                     ;; Execute! Returns CompletionStage -> 120

;; Foreach side-effects
(-> (s/source ["A" "B" "C"])
    (s/smap clojure.string/lower-case)
    (s/run-foreach println sys))       ;; Prints "a", "b", "c"
```

If you prefer operating with custom Flow graph blocks natively, you can integrate them via `s/via`:

```clojure
(def my-flow (s/flow-map #(str "Processed: " %)))

(-> (s/source ["Data 1" "Data 2"])
    (s/via my-flow)
    (s/run-to-seq sys))
;; Awaits the stream and returns ["Processed: Data 1" "Processed: Data 2"]
```

### Contrast with Scala (Pekko Typed)

In native Scala, constructing a stream demands instantiating specific objects explicitly and attaching them.

```scala
import org.apache.pekko.stream.scaladsl.{Source, Flow, Sink, RunnableGraph}
import scala.concurrent.Future

// Emits 1, 2, 3, 4, 5
val source: Source[Int, NotUsed] = Source(1 to 5)

// Define transform logic
val flow: Flow[Int, Int, NotUsed] = Flow[Int]
  .map(_ * 10)
  .filter(_ != 30)

// Folding to single Future
val sink: Sink[Int, Future[Int]] = Sink.fold[Int, Int](0)(_ + _)

// Connect graph
val runnable: RunnableGraph[Future[Int]] = source.via(flow).toMat(sink)(Keep.right)

// Run it!
val result: Future[Int] = runnable.run()
```

**Key Differences:**
1. **The Threading Macro vs. `.via`**: `pekko-clj` models its syntax heavily on Clojure's sequence pipeline structure `(-> (source) (map) (filter) (run))`. In Scala, you wire objects using combinators explicitly `.via()` and `.toMat()`.
2. **Materialization**: In standard Pekko, `toMat(sink)(Keep.right)` specifies that when the stream completes we want the `Future` value from the sink rather than the source materializer (which is usually a "NotUsed" parameter). The `pekko-clj` API provides terminal commands like `sfold`, `run-foreach`, and `run-to-seq` which infer `Keep.right()` automatically, reducing the friction involved when just trying to read out stream outputs.
