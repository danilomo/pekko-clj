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

### Materializers

Every `run-*` above is handed the `ActorSystem` directly. That resolves to
`(s/system-materializer sys)` — the single materializer the system owns, created
on first use and shut down with the system. `(s/materializer sys)` builds a
**new** materializer each call, and each one owns actors that live until it is
explicitly shut down, so reach for it only when a stream needs its own settings
or lifetime.

### Files and blocking I/O

These streams carry Pekko `ByteString` elements. Coerce with `->byte-string`
(a String is encoded UTF-8; a byte-array or existing ByteString pass through) and
read back with `byte-string->string` / `byte-string->bytes`.

- **Files** — `(source-from-file f)` reads a file as ByteString chunks;
  `(sink-to-file f)` writes them. `f` is a String path, a `java.io.File` or a
  `java.nio.file.Path`. Their materialized value is a `CompletionStage<IOResult>`;
  pass it through `io-result->map` for `{:count :success? :error}`. `sink-to-file`
  takes an optional open-option collection — keywords like `:append`, `:create`,
  `:truncate-existing` (or `java.nio.file.OpenOption` values).

- **InputStream / OutputStream** — `source-from-input-stream` /
  `sink-to-output-stream` bridge a stream to a blocking `java.io.*Stream` you
  create (each takes a no-arg factory fn and materializes to an `IOResult`).
  `sink-as-input-stream` and `source-as-output-stream` go the other way: their
  materialized value *is* a blocking stream you read from / write to.

- **Framing** — `(frame-delimiter delim max-len)` is a Flow that splits a byte
  stream on a delimiter (stripping it); `(lines)` is the newline-framing +
  UTF-8-decoding shorthand, emitting `String`s.

```clojure
;; Count the non-blank lines in a file.
(-> (s/source-from-file "names.txt")
    (s/via (s/lines))
    (s/sfilter (complement clojure.string/blank?))
    (s/run-fold 0 (fn [n _] (inc n)) sys)
    (s/await-completion))

;; Write a stream to disk, then inspect the IOResult.
(let [result (-> (s/source [(s/->byte-string "line 1\n") (s/->byte-string "line 2\n")])
                 (s/run-with (s/sink-to-file "out.txt") sys)
                 (s/await-completion))]
  (s/io-result->map result)) ;; => {:count 14 :success? true :error nil}
```

A `source-from-file` composes directly with `pekko-clj.http.response/stream` for
serving a file as a streaming HTTP entity (see the HTTP guide's static content).

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
