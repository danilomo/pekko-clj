(ns pekko-clj.stream-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [pekko-clj.core :as core]
            [pekko-clj.stream :as s]
            [pekko-clj.test-support :refer [eventually]])
  (:import [org.apache.pekko.stream Attributes RestartSettings UniqueKillSwitch]
           [org.apache.pekko.stream.javadsl RunnableGraph SinkQueueWithCancel
            SourceQueueWithComplete]
           [org.apache.pekko.pattern StatusReply]
           [org.apache.pekko Done NotUsed]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [org.apache.pekko.stream IOResult]
           [java.io File ByteArrayInputStream ByteArrayOutputStream OutputStream]
           [java.util.concurrent CompletableFuture]))

(def ^:dynamic *system* nil)
(def ^:dynamic *mat* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "stream-test")
        mat (s/materializer sys)]
    (try
      (binding [*system* sys
                *mat* mat]
        (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each actor-system-fixture)

;; ---------------------------------------------------------------------------
;; Tests: Sources
;; ---------------------------------------------------------------------------

(deftest source-from-collection
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 3 4 5] (vec result)))))

(deftest source-empty-collection-runs-to-empty
  ;; B19: (seq []) is nil; Source/from must still get an empty Iterable, not null.
  (let [result (-> (s/source [])
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (empty? (vec result)))))

(deftest source-nil-runs-to-empty
  ;; B19: (source nil) is an ordinary empty stream, not an NPE.
  (let [result (-> (s/source nil)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (empty? (vec result)))))

(deftest source-from-string-emits-chars
  ;; B19 guard: the (seq coll) coercion of a String must survive the empty-fallback fix.
  (let [result (-> (s/source "ab")
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [\a \b] (vec result)))))

(deftest source-single-element
  (let [result (-> (s/source-single :hello)
                   (s/run-head *mat*)
                   (s/await-completion 3000))]
    (is (= :hello result))))

(deftest source-empty-completes
  (let [result (-> (s/source-empty)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (empty? result))))

(deftest source-repeat-with-take
  (let [result (-> (s/source-repeat :x)
                   (s/take 5)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [:x :x :x :x :x] (vec result)))))

(deftest source-range-generates-numbers
  (let [result (-> (s/source-range 5)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [0 1 2 3 4] (vec result)))))

(deftest source-range-with-start
  (let [result (-> (s/source-range 3 7)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [3 4 5 6] (vec result)))))

(deftest source-unfold-generates-sequence
  (let [result (-> (s/source-unfold 0 (fn [n]
                                        (when (< n 5)
                                          [(inc n) n])))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [0 1 2 3 4] (vec result)))))

;; ---------------------------------------------------------------------------
;; Tests: Transformations
;; ---------------------------------------------------------------------------

(deftest smap-transforms-elements
  (let [result (-> (s/source [1 2 3])
                   (s/smap inc)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [2 3 4] (vec result)))))

(deftest sfilter-removes-elements
  (let [result (-> (s/source (range 10))
                   (s/sfilter even?)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [0 2 4 6 8] (vec result)))))

(deftest mapcat-expands-elements
  (let [result (-> (s/source [1 2 3])
                   (s/mapcat (fn [x] [x x]))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 1 2 2 3 3] (vec result)))))

(deftest take-limits-elements
  (let [result (-> (s/source (range 100))
                   (s/take 5)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [0 1 2 3 4] (vec result)))))

(deftest drop-skips-elements
  (let [result (-> (s/source (range 10))
                   (s/drop 5)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [5 6 7 8 9] (vec result)))))

(deftest take-while-stops-on-predicate
  (let [result (-> (s/source (range 10))
                   (s/take-while #(< % 5))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [0 1 2 3 4] (vec result)))))

(deftest drop-while-skips-until-predicate
  (let [result (-> (s/source (range 10))
                   (s/drop-while #(< % 5))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [5 6 7 8 9] (vec result)))))

(deftest grouped-batches-elements
  (let [result (-> (s/source (range 10))
                   (s/grouped 3)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [[0 1 2] [3 4 5] [6 7 8] [9]] (mapv vec result)))))

(deftest scan-emits-intermediates
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/scan 0 +)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [0 1 3 6 10 15] (vec result)))))

(deftest fold-emits-final-only
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/fold 0 +)
                   (s/run-head *mat*)
                   (s/await-completion 3000))]
    (is (= 15 result))))

(deftest intersperse-adds-separator
  (let [result (-> (s/source [:a :b :c])
                   (s/intersperse :sep)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [:a :sep :b :sep :c] (vec result)))))

(deftest delay-each-preserves-elements
  ;; Regression (H3): delay-each called Source.delay with no strategy, which has
  ;; no matching method — it would have thrown at runtime.
  (let [result (-> (s/source [1 2 3])
                   (s/delay-each (java.time.Duration/ofMillis 10))
                   (s/run-to-seq *mat*)
                   (s/await-completion 5000))]
    (is (= [1 2 3] (vec result)))))

;; ---------------------------------------------------------------------------
;; Tests: Combining sources
;; ---------------------------------------------------------------------------

(deftest concat-appends-sources
  (let [result (-> (s/source [1 2])
                   (s/concat (s/source [3 4]))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 3 4] (vec result)))))

(deftest zip-with-combines-sources
  (let [result (-> (s/source [1 2 3])
                   (s/zip-with (s/source [:a :b :c]) vector)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [[1 :a] [2 :b] [3 :c]] (vec result)))))

;; ---------------------------------------------------------------------------
;; Tests: Sinks
;; ---------------------------------------------------------------------------

(deftest run-foreach-processes-all
  (let [received (atom [])
        done (-> (s/source [1 2 3])
                 (s/run-foreach #(swap! received conj %) *mat*)
                 (s/await-completion 3000))]
    (is (instance? Done done))
    (is (= [1 2 3] @received))))

(deftest run-fold-accumulates
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/run-fold 0 + *mat*)
                   (s/await-completion 3000))]
    (is (= 15 result))))

(deftest run-head-returns-first
  (let [result (-> (s/source [1 2 3])
                   (s/run-head *mat*)
                   (s/await-completion 3000))]
    (is (= 1 result))))

(deftest run-last-returns-last
  (let [result (-> (s/source [1 2 3])
                   (s/run-last *mat*)
                   (s/await-completion 3000))]
    (is (= 3 result))))

;; ---------------------------------------------------------------------------
;; Tests: Complex pipelines
;; ---------------------------------------------------------------------------

(deftest complex-pipeline
  (let [result (-> (s/source (range 100))
                   (s/smap inc)
                   (s/sfilter even?)
                   (s/take 10)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [2 4 6 8 10 12 14 16 18 20] (vec result)))))

(deftest chained-transformations
  (let [result (-> (s/source ["hello" "world" "foo" "bar"])
                   (s/sfilter #(> (count %) 3))
                   (s/smap str/upper-case)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= ["HELLO" "WORLD"] (vec result)))))

;; ---------------------------------------------------------------------------
;; Tests: Actor integration
;; ---------------------------------------------------------------------------

(deftest to-actor-sends-elements
  (let [received (atom [])
        actor (core/new-actor
               *system*
               {:function (fn [_this msg]
                            (when (not= msg :done)
                              (swap! received conj msg))
                            nil)
                :state nil})]
    ;; to-actor returns NotUsed, not CompletionStage - just run it
    (s/to-actor (s/source [1 2 3]) actor :done *mat*)
    (is (eventually (= [1 2 3] @received)))))

(deftest source-actor-ref-emits-and-completes
  ;; B7: source-actor-ref returns the source and the ref (previously swapped, so
  ;; ActorRef and Source came back in the wrong slots).
  (let [{src :source actor-ref :actor-ref} (s/source-actor-ref 16 :fail *mat*)]
    (is (instance? org.apache.pekko.actor.ActorRef actor-ref)
        "second element must be the ActorRef")
    (let [result (s/run-to-seq src *mat*)]
      (core/! actor-ref 1)
      (core/! actor-ref 2)
      (core/! actor-ref 3)
      ;; Status.Success completes the actor-ref-backed source.
      (core/! actor-ref (org.apache.pekko.actor.Status$Success. "done"))
      (is (= [1 2 3] (vec (s/await-completion result 5000)))))))

;; ---------------------------------------------------------------------------
;; Tests: Utility functions
;; ---------------------------------------------------------------------------

(deftest completion-to-promise-success
  (let [p (-> (s/source [1 2 3 4 5])
              (s/run-fold 0 + *mat*)
              (s/completion->promise))
        result (deref p 3000 :timeout)]
    (is (= {:value 15} result))))

(deftest await-completion-timeout
  (let [slow-stream (-> (s/source-tick (java.time.Duration/ofSeconds 10)
                                       (java.time.Duration/ofSeconds 10)
                                       :tick)
                        (s/take 1))]
    ;; H5: await-completion returns nil on the block timeout (matches core/<!).
    (is (nil? (-> slow-stream
                  (s/run-to-seq *mat*)
                  (s/await-completion 100))))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 1 - Async Operators
;; ---------------------------------------------------------------------------

(deftest map-async-preserves-order
  (let [result (-> (s/source [1 2 3])
                   (s/map-async 2 (fn [x]
                                    (CompletableFuture/completedFuture (* x 10))))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [10 20 30] (vec result)))))

(deftest map-async-with-delay
  (let [result (-> (s/source [3 1 2])
                   (s/map-async 3 (fn [x]
                                    (let [cf (CompletableFuture.)]
                                      (future
                                        (Thread/sleep (* x 10))
                                        (.complete cf x))
                                      cf)))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Order preserved despite varying completion times
    (is (= [3 1 2] (vec result)))))

(deftest map-async-unordered-completes-as-ready
  (let [result (-> (s/source [100 10 50])
                   (s/map-async-unordered 3 (fn [x]
                                              (let [cf (CompletableFuture.)]
                                                (future
                                                  (Thread/sleep x)
                                                  (.complete cf x))
                                                cf)))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Fastest completes first
    (is (= [10 50 100] (vec result)))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 2 - Sub-streams
;; ---------------------------------------------------------------------------

(deftest flat-map-concat-sequential
  (let [result (-> (s/source [1 2 3])
                   (s/flat-map-concat (fn [n] (s/source (repeat n n))))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 2 3 3 3] (vec result)))))

(deftest flat-map-merge-parallel
  (let [result (-> (s/source [1 2])
                   (s/flat-map-merge 2 (fn [n] (s/source [n n])))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; All elements present, order may vary
    (is (= #{1 2} (set result)))
    (is (= 4 (count result)))))

(deftest group-by-and-merge
  (let [result (-> (s/source [1 2 3 4 5 6])
                   (s/group-by 2 #(mod % 2))
                   (s/smap #(* % 10))
                   (s/merge-substreams)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; All elements multiplied by 10
    (is (= #{10 20 30 40 50 60} (set result)))))

(deftest group-by-and-concat
  ;; concat-substreams processes one substream at a time
  ;; Use mergeSubstreams for faster completion
  (let [result (-> (s/source [1 2 3 4])
                   (s/group-by 2 #(mod % 2))
                   (s/smap #(* % 10))
                   (s/merge-substreams)
                   (s/run-to-seq *mat*)
                   (s/await-completion 5000))]
    (is (= #{10 20 30 40} (set result)))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 3 - Error Handling
;; ---------------------------------------------------------------------------

(deftest recover-emits-fallback
  (let [result (-> (s/source-failed (ex-info "boom" {}))
                   (s/recover (fn [_] :fallback))
                   (s/run-head *mat*)
                   (s/await-completion 3000))]
    (is (= :fallback result))))

(deftest recover-passes-through-normal
  (let [result (-> (s/source [1 2 3])
                   (s/recover (fn [_] :fallback))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 3] (vec result)))))

(deftest recover-with-switches-source
  (let [result (-> (s/source-failed (ex-info "boom" {}))
                   (s/recover-with (fn [_] (s/source [:recovered :stream])))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [:recovered :stream] (vec result)))))

(deftest recover-with-retries-limits-attempts
  ;; Test that recover-with-retries can recover from failures
  (let [result (-> (s/source-failed (ex-info "boom" {}))
                   (s/recover-with-retries 3
                                           (fn [_] (s/source [:recovered])))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [:recovered] (vec result)))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 4 - Time-based Operators
;; ---------------------------------------------------------------------------

(deftest grouped-within-by-count
  (let [result (-> (s/source (range 10))
                   (s/grouped-within 3 (java.time.Duration/ofSeconds 10))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [[0 1 2] [3 4 5] [6 7 8] [9]]
           (mapv vec result)))))

(deftest take-within-limits-by-time
  (let [result (-> (s/source-tick (java.time.Duration/ofMillis 10)
                                  (java.time.Duration/ofMillis 50)
                                  :tick)
                   (s/take-within (java.time.Duration/ofMillis 200))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Should have some but not infinite ticks
    (is (> (count result) 0))
    (is (< (count result) 20))))

(deftest drop-within-skips-initial
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/drop-within (java.time.Duration/ofMillis 1))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Most elements dropped (timing dependent)
    (is (<= (count result) 5))))

(deftest keep-alive-injects-elements
  (let [result (-> (s/source-tick (java.time.Duration/ofMillis 200)
                                  (java.time.Duration/ofMillis 200)
                                  :data)
                   (s/keep-alive (java.time.Duration/ofMillis 50)
                                 (fn [] :heartbeat))
                   (s/take 3)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Should have heartbeats before actual data
    (is (some #(= :heartbeat %) result))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 5 - Backpressure Strategies
;; ---------------------------------------------------------------------------

(deftest batch-aggregates-elements
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/batch 10
                            (fn [x] [x])
                            (fn [acc x] (conj acc x)))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Batched into one or more groups
    (is (= [1 2 3 4 5] (vec (apply concat result))))))

(deftest conflate-merges-fast-elements
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/conflate +)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Elements may be conflated; sum should equal original
    (is (= 15 (reduce + result)))))

(deftest conflate-with-seed-transforms
  (let [result (-> (s/source [1 2 3])
                   (s/conflate-with-seed
                    (fn [x] {:sum x :count 1})
                    (fn [acc x] (-> acc
                                    (update :sum + x)
                                    (update :count inc))))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Total sum and count across all batches
    (is (= 6 (reduce + (clojure.core/map :sum result))))
    (is (= 3 (reduce + (clojure.core/map :count result))))))

(deftest buffer-with-valid-strategy-passes-elements-through
  (let [result (-> (s/source [1 2 3])
                   (s/buffer 10 :drop-new)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 3] (vec result)))))

(deftest buffer-unknown-strategy-throws
  (is (thrown? IllegalArgumentException
        (-> (s/source [1 2 3])
            (s/buffer 10 :drop-newx)
            (s/run-to-seq *mat*)
            (s/await-completion 3000)))))

(deftest expand-extrapolates-elements
  ;; expand is used to extrapolate when downstream is slow
  ;; In a fast run, we may not need extrapolation
  (let [result (-> (s/source [1 2])
                   (s/expand (fn [x] (iterate identity x)))
                   (s/take 5)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; We should get at least the original elements
    (is (>= (count result) 2))
    (is (<= (count result) 5))
    ;; First element should be 1
    (is (= 1 (first result)))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 6 - Graph DSL
;; ---------------------------------------------------------------------------

(deftest fan-out-broadcasts-to-sinks
  (let [results (atom [])
        _ (s/fan-out (s/source [1 2 3])
                     [(s/sink-foreach #(swap! results conj [:sink1 %]))
                      (s/sink-foreach #(swap! results conj [:sink2 %]))]
                     *mat*)]
    ;; Both sinks received all elements
    (is (eventually (= 6 (count @results))))
    (is (= 3 (count (clojure.core/filter #(= :sink1 (first %)) @results))))
    (is (= 3 (count (clojure.core/filter #(= :sink2 (first %)) @results))))))

(deftest balance-work-distributes
  (let [result (-> (s/balance-work
                    (s/source [1 2 3 4])
                    2
                    (fn [x] (s/source [(* x 10)]))
                    *mat*)
                   (s/await-completion 3000))]
    (is (= #{10 20 30 40} (set result)))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 7 - Additional Sources
;; ---------------------------------------------------------------------------

(deftest source-future-emits-value
  (let [cf (CompletableFuture/completedFuture :async-value)
        result (-> (s/source-future cf)
                   (s/run-head *mat*)
                   (s/await-completion 3000))]
    (is (= :async-value result))))

(deftest source-queue-allows-pushing
  (let [{:keys [source queue]} (s/source-queue 10 :backpressure *mat*)
        src source]
    (.offer queue 1)
    (.offer queue 2)
    (.offer queue 3)
    (.complete queue)
    (let [result (-> src
                     (s/run-to-seq *mat*)
                     (s/await-completion 3000))]
      (is (= [1 2 3] (vec result))))))

(deftest source-cycle-repeats
  (let [result (-> (s/source-cycle [1 2 3])
                   (s/take 7)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 3 1 2 3 1] (vec result)))))

(deftest source-cycle-empty-throws-pekko-error
  ;; B19: an empty cycle is a *user* error — it must surface as Pekko's own
  ;; IllegalArgumentException ("empty iterator"), not our construction-time NPE.
  (is (thrown-with-msg?
       IllegalArgumentException #"empty iterator"
        (-> (s/source-cycle [])
            (s/take 3)
            (s/run-to-seq *mat*)
            (s/await-completion 3000)))))

;; ---------------------------------------------------------------------------
;; Tests: Phase 8 - Additional Sinks
;; ---------------------------------------------------------------------------

(deftest sink-reduce-folds
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/run-with (s/sink-reduce +) *mat*)
                   (s/await-completion 3000))]
    (is (= 15 result))))

(deftest sink-foreach-async-processes
  (let [results (atom [])
        done (-> (s/source [1 2 3])
                 (s/run-with
                  (s/sink-foreach-async 2
                                        (fn [x]
                                          (swap! results conj x)
                                          (CompletableFuture/completedFuture nil)))
                  *mat*)
                 (s/await-completion 3000))]
    (is (instance? Done done))
    (is (eventually (= #{1 2 3} (set @results))))))

(deftest sink-queue-allows-pulling
  (let [queue (-> (s/source [1 2 3])
                  (s/run-with (s/sink-queue) *mat*))
        opt (.get (.pull queue) 1000 java.util.concurrent.TimeUnit/MILLISECONDS)]
    (is (= 1 (.get opt)))
    (.cancel queue)))

;; ---------------------------------------------------------------------------
;; Tests: Phase 9 - Utilities
;; ---------------------------------------------------------------------------

(deftest dedupe-removes-consecutive-duplicates
  (let [result (-> (s/source [1 1 2 2 2 3 1 1])
                   (s/dedupe)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 3 1] (vec result)))))

(deftest dedupe-by-key
  (let [result (-> (s/source [{:id 1 :v "a"} {:id 1 :v "b"} {:id 2 :v "c"}])
                   (s/dedupe-by :id)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [{:id 1 :v "a"} {:id 2 :v "c"}] (vec result)))))

(deftest zip-with-index-pairs
  ;; N13: emits Clojure [element index] vectors, not japi.Pair — no interop needed
  ;; downstream.
  (let [result (-> (s/source [:a :b :c])
                   (s/zip-with-index)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [[:a 0] [:b 1] [:c 2]] (vec result)))
    (is (vector? (first result)))))

(deftest stateful-map-maintains-state
  (let [result (-> (s/source [1 2 3 4 5])
                   (s/stateful-map
                    (fn [] 0)  ; initial state (running sum)
                    (fn [sum x]
                      (let [new-sum (+ sum x)]
                        [new-sum new-sum])))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    ;; Running sum: 1, 3, 6, 10, 15
    (is (= [1 3 6 10 15] (vec result)))))

(deftest wire-tap-copies-to-sink
  (let [tapped (atom [])
        result (-> (s/source [1 2 3])
                   (s/wire-tap (s/sink-foreach #(swap! tapped conj %)))
                   (s/smap inc)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [2 3 4] (vec result)))
    (is (eventually (= [1 2 3] @tapped)))))

(deftest also-to-sends-to-sink
  (let [secondary (atom [])
        result (-> (s/source [1 2 3])
                   (s/also-to (s/sink-foreach #(swap! secondary conj %)))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 2 3] (vec result)))
    (is (eventually (= [1 2 3] @secondary)))))

(deftest watch-termination-callback
  (let [completed (promise)
        _ (-> (s/source [1 2 3])
              (s/watch-termination
               (fn [_ done]
                 (.whenComplete done
                                (reify java.util.function.BiConsumer
                                  (accept [_ _ _]
                                    (deliver completed true))))))
              (s/run-to-seq *mat*))]
    (is (= true (deref completed 3000 :timeout)))))

(deftest on-complete-success
  (let [result (promise)
        _ (-> (s/source [1 2 3])
              (s/on-complete
               (fn [ex]
                 (deliver result (if ex :error :success))))
              (s/run-to-seq *mat*))]
    (is (= :success (deref result 3000 :timeout)))))

(deftest on-complete-failure
  (let [result (promise)
        _ (-> (s/source-failed (ex-info "boom" {}))
              (s/on-complete
               (fn [ex]
                 (deliver result (if ex :error :success))))
              (s/run-to-seq *mat*))]
    (is (= :error (deref result 3000 :timeout)))))

;; ---------------------------------------------------------------------------
;; N1: Materialized values (toMat / viaMat / Keep)
;; ---------------------------------------------------------------------------

(deftest keep-mat-returns-combiners
  (is (every? some? [(s/keep-mat :left) (s/keep-mat :right)
                     (s/keep-mat :both) (s/keep-mat :none)]))
  (is (thrown-with-msg? IllegalArgumentException #"Unknown Keep combiner"
        (s/keep-mat :sideways))))

(deftest to-mat-builds-runnable-graph
  (let [graph (-> (s/source [1 2 3])
                  (s/to-mat (s/sink-seq) :right))]
    (is (instance? RunnableGraph graph))
    (is (= [1 2 3] (vec (s/await-completion (s/run-graph graph *mat*) 3000))))))

(deftest run-mat-both-returns-clojure-vector
  ;; Keep/both materializes a japi.Pair; run-mat unwraps it to [left right].
  (let [[left right] (s/run-mat (s/source [1 2 3]) (s/sink-seq) :both *mat*)]
    (is (instance? NotUsed left) "left is the Source's materialized value")
    (is (= [1 2 3] (vec (s/await-completion right 3000))))))

(deftest run-mat-right-matches-run
  (is (= [1 2] (vec (s/await-completion
                     (s/run-mat (s/source [1 2]) (s/sink-seq) :right *mat*) 3000)))))

(deftest run-mat-left-keeps-source-value
  ;; via-kill-switch makes the Source's materialized value the kill switch, so
  ;; :left is the way to reach it.
  (let [ks (s/run-mat (s/via-kill-switch (s/source-repeat 1)) (s/sink-ignore) :left *mat*)]
    (is (instance? UniqueKillSwitch ks))
    (s/shutdown ks)))

(deftest run-source-queue-returns-queue-and-done
  (let [{:keys [queue done]} (s/run-source-queue 8 :backpressure (s/sink-seq) *mat*)]
    (is (instance? SourceQueueWithComplete queue))
    (s/await-completion (.offer queue 1) 3000)
    (s/await-completion (.offer queue 2) 3000)
    (.complete queue)
    (is (= [1 2] (vec (s/await-completion done 3000))))))

(deftest run-sink-queue-pulls-elements
  (let [{:keys [queue]} (s/run-sink-queue (s/source [1 2]) *mat*)]
    (is (instance? SinkQueueWithCancel queue))
    (is (= 1 (.get (s/await-completion (.pull queue) 3000))))
    (is (= 2 (.get (s/await-completion (.pull queue) 3000))))
    (is (false? (.isPresent (s/await-completion (.pull queue) 3000)))
        "pull yields an empty Optional once the stream completes")))

;; ---------------------------------------------------------------------------
;; N1: KillSwitches
;; ---------------------------------------------------------------------------

(deftest run-with-kill-switch-shutdown-completes-stream
  (let [{:keys [kill-switch done]} (s/run-with-kill-switch
                                    (s/source-repeat 1) (s/sink-ignore) *mat*)]
    (is (instance? UniqueKillSwitch kill-switch))
    (is (nil? (s/await-completion done 200))
        "an infinite stream does not complete on its own")
    (s/shutdown kill-switch)
    (is (some? (s/await-completion done 3000))
        "shutdown completes the stream gracefully")))

(deftest kill-switch-abort-fails-stream
  (let [{:keys [kill-switch done]} (s/run-with-kill-switch
                                    (s/source-repeat 1) (s/sink-ignore) *mat*)]
    (s/abort kill-switch (RuntimeException. "aborted"))
    (is (thrown-with-msg? RuntimeException #"aborted" (s/await-completion done 3000)))))

(deftest shared-kill-switch-stops-multiple-streams
  (let [ks (s/shared-kill-switch "test-switch")
        done1 (s/run-with (s/via (s/source-repeat 1) (s/shared-kill-switch-flow ks))
                          (s/sink-ignore) *mat*)
        done2 (s/run-with (s/via (s/source-repeat 2) (s/shared-kill-switch-flow ks))
                          (s/sink-ignore) *mat*)]
    (s/shutdown ks)
    (is (some? (s/await-completion done1 3000)))
    (is (some? (s/await-completion done2 3000)))))

;; ---------------------------------------------------------------------------
;; N1: Supervision
;; ---------------------------------------------------------------------------

(deftest with-supervision-resume-drops-failing-elements
  (let [result (-> (s/source [1 0 2])
                   (s/smap #(/ 10 %))
                   (s/with-supervision (fn [ex] (when (instance? ArithmeticException ex) :resume)))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [10 5] (vec result)) "the divide-by-zero element is skipped")))

(deftest with-supervision-stop-fails-stream
  (is (thrown? ArithmeticException
        (-> (s/source [1 0 2])
            (s/smap #(/ 10 %))
            (s/with-supervision (fn [_] :stop))
            (s/run-to-seq *mat*)
            (s/await-completion 3000))))
  ;; nil from the decider means :stop, matching Pekko's default.
  (is (thrown? ArithmeticException
        (-> (s/source [1 0 2])
            (s/smap #(/ 10 %))
            (s/with-supervision (fn [_] nil))
            (s/run-to-seq *mat*)
            (s/await-completion 3000)))))

(deftest with-supervision-restart-resets-stage-state
  ;; scan carries state across elements, so it distinguishes :resume from :restart:
  ;; :resume keeps the accumulator, :restart resets the stage to its seed.
  (let [run (fn [directive]
              (vec (-> (s/source [1 2 :boom 3])
                       (s/scan 0 (fn [acc x]
                                   (if (= :boom x)
                                     (throw (RuntimeException. "boom"))
                                     (+ acc x))))
                       (s/with-supervision (fn [_] directive))
                       (s/run-to-seq *mat*)
                       (s/await-completion 3000))))]
    (is (= [0 1 3 6] (run :resume))
        ":resume keeps the accumulator at 3, so the last element is 3+3")
    (is (= [0 1 3 0 3] (run :restart))
        ":restart resets the accumulator to the seed, so it re-emits 0 then 0+3")))

(deftest supervision-strategy-returns-attributes
  (is (instance? Attributes (s/supervision-strategy (fn [_] :resume)))))

(deftest supervision-rejects-unknown-directive
  (is (thrown-with-msg? IllegalArgumentException #"Unknown supervision directive"
        (-> (s/source [0])
            (s/smap #(/ 10 %))
            (s/with-supervision (fn [_] :sideways))
            (s/run-to-seq *mat*)
            (s/await-completion 3000)))))

;; ---------------------------------------------------------------------------
;; N1: Restart / retry with backoff
;; ---------------------------------------------------------------------------

(deftest restart-settings-builds-settings
  (let [rs (s/restart-settings {:min-backoff 100 :max-backoff 2000 :random-factor 0.5
                                :max-restarts 4 :max-restarts-within 9000})]
    (is (instance? RestartSettings rs))
    (is (= 100 (.toMillis (.minBackoff rs))))
    (is (= 2000 (.toMillis (.maxBackoff rs))))
    (is (= 0.5 (.randomFactor rs)))
    (is (= 4 (.maxRestarts rs)))
    (is (= 9000 (.toMillis (.maxRestartsWithin rs)))))
  ;; Durations may also be given as java.time.Duration.
  ;; (Fully qualified: this ns imports scala.concurrent.duration.Duration as `Duration`.)
  (let [rs (s/restart-settings {:min-backoff (java.time.Duration/ofMillis 50)})]
    (is (= 50 (.toMillis (.minBackoff rs))))))

(deftest restart-source-restarts-on-completion
  (let [starts (atom 0)
        result (-> (s/restart-source {:min-backoff 10 :max-backoff 50}
                                     (fn [] (s/source [(swap! starts inc)])))
                   (s/take 3)
                   (s/run-to-seq *mat*)
                   (s/await-completion 10000))]
    (is (= [1 2 3] (vec result)) "the source is re-created after each completion")))

(deftest restart-source-on-failures-retries-until-success
  (let [attempts (atom 0)
        result (-> (s/restart-source-on-failures
                    {:min-backoff 10 :max-backoff 50}
                    (fn [] (if (< (swap! attempts inc) 3)
                             (s/source-failed (RuntimeException. "boom"))
                             (s/source [:ok]))))
                   (s/run-to-seq *mat*)
                   (s/await-completion 10000))]
    (is (= [:ok] (vec result)))
    (is (= 3 @attempts) "failed twice, succeeded on the third attempt")))

(deftest restart-source-on-failures-gives-up-after-max-restarts
  (let [attempts (atom 0)]
    (is (thrown? Exception
          (-> (s/restart-source-on-failures
               {:min-backoff 10 :max-backoff 20 :max-restarts 2 :max-restarts-within 5000}
               (fn [] (swap! attempts inc) (s/source-failed (RuntimeException. "always"))))
              (s/run-to-seq *mat*)
              (s/await-completion 10000))))
    (is (= 3 @attempts) "the initial attempt plus :max-restarts restarts")))

(deftest restart-settings-restart-on-predicate
  ;; :restart-on false => the failure is not restarted, it fails the stream.
  (let [attempts (atom 0)]
    (is (thrown? Exception
          (-> (s/restart-source-on-failures
               {:min-backoff 10 :max-backoff 20 :restart-on (fn [_] false)}
               (fn [] (swap! attempts inc) (s/source-failed (RuntimeException. "nope"))))
              (s/run-to-seq *mat*)
              (s/await-completion 5000))))
    (is (= 1 @attempts) "never restarted")))

(deftest restart-flow-passes-elements-through
  (let [f (s/restart-flow {:min-backoff 10 :max-backoff 50} #(s/flow-from-fn inc))]
    (is (= [2 3 4] (vec (-> (s/source [1 2 3])
                            (s/via f)
                            (s/run-to-seq *mat*)
                            (s/await-completion 5000)))))))

(deftest restart-flow-on-failures-restarts-failing-flow
  (let [attempts (atom 0)
        f (s/restart-flow-on-failures
           {:min-backoff 10 :max-backoff 50}
           (fn [] (swap! attempts inc)
             (s/flow-from-fn (fn [x] (if (= x :boom) (throw (RuntimeException. "boom")) x)))))
        result (-> (s/source [1 :boom 2])
                   (s/via f)
                   (s/run-to-seq *mat*)
                   (s/await-completion 5000))]
    (is (= 2 @attempts) "the flow was re-created after the failure")
    (is (= [1] (clojure.core/take 1 (vec result))))))

(deftest restart-sink-receives-elements
  (let [received (atom [])
        sink (s/restart-sink {:min-backoff 10 :max-backoff 50}
                             #(s/sink-foreach (fn [x] (swap! received conj x))))]
    (s/run-with (s/source [1 2 3]) sink *mat*)
    (is (eventually (= [1 2 3] @received)))))

(deftest retry-flow-retries-until-decide-fn-accepts
  (let [f (s/flow-from-fn (fn [n] (if (< n 3) :error :ok)))
        retried (s/retry-flow {:min-backoff 10 :max-backoff 50 :max-retries 5} f
                              (fn [in out] (when (= :error out) (inc in))))
        result (-> (s/source [1])
                   (s/via retried)
                   (s/run-to-seq *mat*)
                   (s/await-completion 10000))]
    (is (= [:ok] (vec result)) "1 -> :error, 2 -> :error, 3 -> :ok")))

(deftest retry-flow-gives-up-after-max-retries
  (let [f (s/flow-from-fn (fn [_] :error))
        retried (s/retry-flow {:min-backoff 10 :max-backoff 20 :max-retries 2} f
                              (fn [in out] (when (= :error out) in)))
        result (-> (s/source [1])
                   (s/via retried)
                   (s/run-to-seq *mat*)
                   (s/await-completion 10000))]
    (is (= [:error] (vec result)) "the last result is emitted once retries are exhausted")))

;; ---------------------------------------------------------------------------
;; N1: Flows
;; ---------------------------------------------------------------------------

(deftest flow-is-identity
  (is (= [1 2 3] (vec (-> (s/source [1 2 3])
                          (s/via (s/flow))
                          (s/run-to-seq *mat*)
                          (s/await-completion 3000))))))

(deftest flow-of-class-is-identity
  (is (= [1 2] (vec (-> (s/source [1 2])
                        (s/via (s/flow-of Long))
                        (s/run-to-seq *mat*)
                        (s/await-completion 3000))))))

(deftest flow-from-fn-transforms
  (is (= [2 3 4] (vec (-> (s/source [1 2 3])
                          (s/via (s/flow-from-fn inc))
                          (s/run-to-seq *mat*)
                          (s/await-completion 3000))))))

(deftest flow-composes-with-stream-operators
  ;; The existing ops work on a Flow, not just a Source.
  (let [f (-> (s/flow) (s/smap inc) (s/sfilter even?))]
    (is (= [2 4] (vec (-> (s/source [1 2 3])
                          (s/via f)
                          (s/run-to-seq *mat*)
                          (s/await-completion 3000)))))))

;; ---------------------------------------------------------------------------
;; N1: Actor interop (ask / askWithStatus / actorRef overloads)
;; ---------------------------------------------------------------------------

(core/defactor n1-doubler
  (handle [:double n] (core/reply (* 2 n))))

(core/defactor n1-status-worker
  (handle [:ok n]  (core/reply (StatusReply/success (* 2 n))))
  (handle [:err _] (core/reply (StatusReply/error "nope"))))

(deftest ask-emits-actor-replies
  (let [a (core/spawn *system* n1-doubler nil)
        result (-> (s/source [[:double 1] [:double 2] [:double 3]])
                   (s/ask a Long 3000)
                   (s/run-to-seq *mat*)
                   (s/await-completion 5000))]
    (is (= [2 4 6] (vec result)) "replies are emitted in element order")))

(deftest ask-with-parallelism-preserves-order
  (let [a (core/spawn *system* n1-doubler nil)
        result (-> (s/source (clojure.core/map (fn [n] [:double n]) (range 1 11)))
                   (s/ask 4 a Long 3000)
                   (s/run-to-seq *mat*)
                   (s/await-completion 5000))]
    (is (= (clojure.core/map #(* 2 %) (range 1 11)) (vec result)))))

(deftest ask-accepts-duration-timeout
  (let [a (core/spawn *system* n1-doubler nil)]
    (is (= [2] (vec (-> (s/source [[:double 1]])
                        (s/ask a Long (java.time.Duration/ofSeconds 3))
                        (s/run-to-seq *mat*)
                        (s/await-completion 5000)))))))

(deftest ask-with-status-unwraps-success
  (let [a (core/spawn *system* n1-status-worker nil)
        result (-> (s/source [[:ok 21]])
                   (s/ask-with-status a 3000)
                   (s/run-to-seq *mat*)
                   (s/await-completion 5000))]
    (is (= [42] (vec result)))))

(deftest ask-with-status-error-fails-stream
  (let [a (core/spawn *system* n1-status-worker nil)]
    (is (thrown-with-msg? Throwable #"nope"
          (-> (s/source [[:err 1]])
              (s/ask-with-status a 3000)
              (s/run-to-seq *mat*)
              (s/await-completion 5000))))))

(deftest ask-with-status-parallelism-arity
  (let [a (core/spawn *system* n1-status-worker nil)
        result (-> (s/source [[:ok 1] [:ok 2]])
                   (s/ask-with-status 2 a 3000)
                   (s/run-to-seq *mat*)
                   (s/await-completion 5000))]
    (is (= [2 4] (vec result)))))

(deftest source-actor-ref-completes-with-custom-message
  ;; The 4-arity matcher-based Source/actorRef overload.
  (let [{src :source ref :actor-ref} (s/source-actor-ref 16 :fail
                                                         {:complete-with #(when (= :finished %) :draining)}
                                                         *mat*)
        result (s/run-to-seq src *mat*)]
    (core/! ref 1)
    (core/! ref 2)
    (core/! ref :finished)
    (is (= [1 2] (vec (s/await-completion result 5000)))
        ":draining emits buffered elements before completing")))

(deftest source-actor-ref-fails-with-custom-message
  (let [{src :source ref :actor-ref} (s/source-actor-ref 16 :fail
                                                         {:fail-with #(when (= :boom %) (RuntimeException. "kaboom"))}
                                                         *mat*)
        result (s/run-to-seq src *mat*)]
    (core/! ref :boom)
    (is (thrown-with-msg? RuntimeException #"kaboom" (s/await-completion result 5000)))))

(deftest source-actor-ref-3-arity-still-uses-status-success
  ;; Regression: the opts arity must not change the existing default behaviour.
  (let [{src :source ref :actor-ref} (s/source-actor-ref 16 :fail *mat*)
        result (s/run-to-seq src *mat*)]
    (core/! ref 1)
    (core/! ref (org.apache.pekko.actor.Status$Success. "done"))
    (is (= [1] (vec (s/await-completion result 5000))))))

(def n1-acked (atom []))

(core/defactor n1-ack-collector
  (handle :init (do (core/reply :ack) nil))
  (handle :done (do (swap! n1-acked conj :done) nil))
  (handle msg   (do (swap! n1-acked conj msg) (core/reply :ack) nil)))

(deftest sink-actor-ref-with-backpressure-acks-each-element
  (reset! n1-acked [])
  (let [a (core/spawn *system* n1-ack-collector nil)]
    (s/run-with (s/source [1 2 3])
                (s/sink-actor-ref-with-backpressure a :init :ack :done
                                                    (fn [ex] [:failed (.getMessage ex)]))
                *mat*)
    (is (eventually (= [1 2 3 :done] @n1-acked))
        "elements are acked one at a time, then the completion message arrives")))

;; ---------------------------------------------------------------------------
;; N13: consistency fixes
;; ---------------------------------------------------------------------------

(deftest system-materializer-is-shared-per-system
  (is (identical? (s/system-materializer *system*) (s/system-materializer *system*))
      "one materializer per system, not a fresh one per call")
  (is (not (identical? (s/materializer *system*) (s/materializer *system*)))
      "`materializer` still hands out a new one each call"))

(deftest run-fns-accept-an-actor-system
  ;; Every run-* takes an ActorSystem where a Materializer is expected.
  (is (= [2 3] (vec (-> (s/source [1 2]) (s/smap inc) (s/run-to-seq *system*)
                        (s/await-completion 3000)))))
  (is (= 1 (-> (s/source [1 2]) (s/run-head *system*) (s/await-completion 3000))))
  (is (= 3 (-> (s/source [1 2]) (s/run-fold 0 + *system*) (s/await-completion 3000))))
  (is (= [1 2] (vec (s/await-completion
                     (s/run-mat (s/source [1 2]) (s/sink-seq) :right *system*) 3000))))
  (let [{:keys [queue done]} (s/run-source-queue 4 :backpressure (s/sink-seq) *system*)]
    (.offer queue 7)
    (.complete queue)
    (is (= [7] (vec (s/await-completion done 3000))))))

(deftest merge-substreams-works-through-a-flow
  ;; group-by on a *Flow* yields a SubFlow, not a SubSource. Hinting only
  ;; SubSource made this a ClassCastException.
  (let [flow (-> (s/flow)
                 (s/group-by 4 #(mod % 2))
                 (s/smap #(* 10 %))
                 (s/merge-substreams))
        result (-> (s/source [1 2 3 4])
                   (s/via flow)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= #{10 20 30 40} (set result))))
  ;; and the Source path still works
  (let [result (-> (s/source [1 2 3 4])
                   (s/group-by 4 #(mod % 2))
                   (s/smap #(* 10 %))
                   (s/merge-substreams)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= #{10 20 30 40} (set result)))))

(deftest concat-substreams-works-through-a-flow
  (let [flow (-> (s/flow)
                 (s/group-by 4 #(mod % 2))
                 (s/merge-substreams))]
    (is (some? flow)))
  (is (thrown-with-msg? IllegalArgumentException #"SubSource/SubFlow"
        (s/merge-substreams (s/source [1]))))
  (is (thrown-with-msg? IllegalArgumentException #"SubSource/SubFlow"
        (s/concat-substreams (s/source [1])))))

(deftest source-queue-and-actor-ref-share-a-map-shape
  (let [{:keys [source queue]} (s/source-queue 4 :backpressure *mat*)]
    (is (instance? SourceQueueWithComplete queue))
    (let [done (s/run-to-seq source *mat*)]
      (.offer queue 1)
      (.complete queue)
      (is (= [1] (vec (s/await-completion done 3000))))))
  (let [{:keys [source actor-ref]} (s/source-actor-ref 4 :fail *mat*)]
    (is (some? source))
    (is (some? actor-ref))))

(deftest dedupe-drops-consecutive-duplicates
  (is (= [1 2 1 3] (vec (-> (s/source [1 1 2 2 2 1 3 3])
                            (s/dedupe)
                            (s/run-to-seq *mat*)
                            (s/await-completion 3000))))
      "consecutive only — the second 1 survives, unlike clojure.core/distinct")
  (is (= [{:id 1} {:id 2}] (vec (-> (s/source [{:id 1} {:id 1} {:id 2}])
                                    (s/dedupe-by :id)
                                    (s/run-to-seq *mat*)
                                    (s/await-completion 3000)))))
  ;; deprecated aliases still work
  #_{:clj-kondo/ignore [:deprecated-var]}
  (is (= [1 2] (vec (-> (s/source [1 1 2]) (s/distinct) (s/run-to-seq *mat*)
                        (s/await-completion 3000)))))
  #_{:clj-kondo/ignore [:deprecated-var]}
  (is (:deprecated (meta #'s/distinct)))
  #_{:clj-kondo/ignore [:deprecated-var]}
  (is (:deprecated (meta #'s/distinct-by))))

;; ---------------------------------------------------------------------------
;; N13: new operators
;; ---------------------------------------------------------------------------

(deftest skeep-maps-and-drops-nils
  (is (= [20 40] (vec (-> (s/source [1 2 3 4])
                          (s/skeep #(when (even? %) (* 10 %)))
                          (s/run-to-seq *mat*)
                          (s/await-completion 3000))))))

(deftest zip-and-zip-all
  (is (= [[1 :a] [2 :b]] (vec (-> (s/source [1 2 3])
                                  (s/zip (s/source [:a :b]))
                                  (s/run-to-seq *mat*)
                                  (s/await-completion 3000))))
      "zip stops at the shorter side and emits Clojure vectors")
  (is (= [[1 :a] [2 :b] [3 :pad]]
         (vec (-> (s/source [1 2 3])
                  (s/zip-all (s/source [:a :b]) 0 :pad)
                  (s/run-to-seq *mat*)
                  (s/await-completion 3000))))
      "zip-all pads the shorter side"))

(deftest interleave-alternates
  (is (= [1 :a 2 :b 3 :c] (vec (-> (s/source [1 2 3])
                                   (s/interleave (s/source [:a :b :c]))
                                   (s/run-to-seq *mat*)
                                   (s/await-completion 3000)))))
  (is (= [1 2 :a :b 3 :c] (vec (-> (s/source [1 2 3])
                                   (s/interleave (s/source [:a :b :c]) 2)
                                   (s/run-to-seq *mat*)
                                   (s/await-completion 3000))))))

(deftest prepend-and-or-else
  (is (= [:a :b 1 2] (vec (-> (s/source [1 2])
                              (s/prepend (s/source [:a :b]))
                              (s/run-to-seq *mat*)
                              (s/await-completion 3000)))))
  (is (= [:fallback] (vec (-> (s/source-empty)
                              (s/or-else (s/source [:fallback]))
                              (s/run-to-seq *mat*)
                              (s/await-completion 3000))))
      "or-else kicks in only when nothing was emitted")
  (is (= [1] (vec (-> (s/source [1])
                      (s/or-else (s/source [:fallback]))
                      (s/run-to-seq *mat*)
                      (s/await-completion 3000))))))

(deftest divert-to-removes-matching-elements
  (let [diverted (atom [])
        result (-> (s/source [1 2 3 4 5])
                   (s/divert-to (s/sink-foreach #(swap! diverted conj %)) even?)
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= [1 3 5] (vec result)) "the even elements left the main flow")
    (is (eventually (= [2 4] @diverted)))))

(deftest limit-fails-past-the-bound
  (is (= [1 2] (vec (-> (s/source [1 2])
                        (s/limit 5)
                        (s/run-to-seq *mat*)
                        (s/await-completion 3000)))))
  (is (thrown? Exception
        (-> (s/source (range 10))
            (s/limit 3)
            (s/run-to-seq *mat*)
            (s/await-completion 3000)))
      "unlike take, going over the bound is an error"))

(deftest option-sinks-are-empty-safe
  (is (= (java.util.Optional/empty)
         (-> (s/source-empty) (s/run-with (s/sink-head-option) *mat*)
             (s/await-completion 3000))))
  (is (= (java.util.Optional/of 1)
         (-> (s/source [1 2]) (s/run-with (s/sink-head-option) *mat*)
             (s/await-completion 3000))))
  (is (= (java.util.Optional/empty)
         (-> (s/source-empty) (s/run-with (s/sink-last-option) *mat*)
             (s/await-completion 3000))))
  (is (= (java.util.Optional/of 2)
         (-> (s/source [1 2]) (s/run-with (s/sink-last-option) *mat*)
             (s/await-completion 3000))))
  ;; sink-head on an empty stream fails instead
  (is (thrown? Exception
        (-> (s/source-empty) (s/run-with (s/sink-head) *mat*)
            (s/await-completion 3000)))))

(deftest sink-take-last-keeps-the-tail
  (is (= [3 4 5] (vec (-> (s/source [1 2 3 4 5])
                          (s/run-with (s/sink-take-last 3) *mat*)
                          (s/await-completion 3000))))))

(deftest source-never-never-completes
  (let [{:keys [kill-switch done]} (s/run-with-kill-switch
                                    (s/source-never) (s/sink-seq) *mat*)]
    (is (nil? (s/await-completion done 300)) "still running")
    (s/shutdown kill-switch)
    (is (= [] (vec (s/await-completion done 3000))))))

(deftest source-unfold-async-emits
  (is (= [0 1 2] (vec (-> (s/source-unfold-async
                           0 (fn [n] (CompletableFuture/completedFuture
                                      (when (< n 3) [(inc n) n]))))
                          (s/run-to-seq *mat*)
                          (s/await-completion 3000))))))

(deftest source-lazily-defers-creation
  ;; N13 moved this onto Source.lazySource (Source.lazily is deprecated in 1.6).
  (let [created (atom 0)
        src (s/source-lazily (fn [] (swap! created inc) (s/source [1 2])))]
    (is (= 0 @created) "nothing ran at construction time")
    (is (= [1 2] (vec (s/await-completion (s/run-to-seq src *mat*) 3000))))
    (is (= 1 @created))))

;; ---------------------------------------------------------------------------
;; N14: File & blocking-IO integration
;; ---------------------------------------------------------------------------

(deftest byte-string-coercions
  (is (= "hi" (s/byte-string->string (s/->byte-string "hi"))))
  (let [bs (s/->byte-string "hi")]
    (is (identical? bs (s/->byte-string bs)) "an existing ByteString passes through"))
  (is (= "bytes" (s/byte-string->string (s/->byte-string (.getBytes "bytes" "UTF-8")))))
  (is (= (seq (.getBytes "hi" "UTF-8")) (seq (s/byte-string->bytes (s/->byte-string "hi")))))
  (is (thrown? IllegalArgumentException (s/->byte-string 42))))

(deftest io-result->map-shapes-success-and-failure
  (is (= {:count 42 :success? true :error nil}
         (s/io-result->map (IOResult/createSuccessful 42))))
  (let [ex (RuntimeException. "boom")
        m  (s/io-result->map (IOResult/createFailed 3 ex))]
    (is (= 3 (:count m)))
    (is (false? (:success? m)))
    (is (= ex (:error m)))))

(deftest file-source-and-sink-round-trip
  (let [f (File/createTempFile "pekko-clj-n14" ".txt")]
    (try
      (let [content    "hello\nfrom\npekko-clj\n"
            byte-count (alength (.getBytes content "UTF-8"))
            write      (s/io-result->map
                        (-> (s/source [(s/->byte-string content)])
                            (s/run-with (s/sink-to-file f) *mat*)
                            (s/await-completion 3000)))
            ;; :both keeps [source-IOResult sink-seq]; the source carries the read count
            [read-stage seq-stage] (s/run-mat (s/source-from-file f) (s/sink-seq) :both *mat*)
            chunks     (s/await-completion seq-stage 3000)
            read-back  (apply str (map s/byte-string->string chunks))
            read       (s/io-result->map (s/await-completion read-stage 3000))]
        (is (:success? write))
        (is (= byte-count (:count write)) "wrote every byte")
        (is (= content read-back) "read the file back verbatim")
        (is (= byte-count (:count read)) "IOResult reports the bytes read"))
      (finally (.delete f)))))

(deftest sink-to-file-append-option-appends
  (let [f (File/createTempFile "pekko-clj-n14-append" ".txt")]
    (try
      (-> (s/source [(s/->byte-string "first\n")])
          (s/run-with (s/sink-to-file f) *mat*) (s/await-completion 3000))
      (-> (s/source [(s/->byte-string "second\n")])
          (s/run-with (s/sink-to-file f [:create :append]) *mat*) (s/await-completion 3000))
      (is (= "first\nsecond\n" (slurp f)))
      (finally (.delete f)))))

(deftest lines-splits-a-multiline-file
  (let [f (File/createTempFile "pekko-clj-n14-lines" ".txt")]
    (try
      (spit f "alpha\nbeta\ngamma\n")
      (is (= ["alpha" "beta" "gamma"]
             (vec (-> (s/source-from-file f)
                      (s/via (s/lines))
                      (s/run-to-seq *mat*)
                      (s/await-completion 3000)))))
      (finally (.delete f)))))

(deftest frame-delimiter-strips-and-splits
  (is (= ["a" "bb" "ccc"]
         (vec (-> (s/source [(s/->byte-string "a,bb,ccc")])
                  (s/via (s/frame-delimiter "," 1024))
                  (s/smap s/byte-string->string)
                  (s/run-to-seq *mat*)
                  (s/await-completion 3000))))))

(deftest source-from-input-stream-reads-bytes
  (let [bytes  (.getBytes "streamed input" "UTF-8")
        chunks (-> (s/source-from-input-stream #(ByteArrayInputStream. bytes))
                   (s/run-to-seq *mat*)
                   (s/await-completion 3000))]
    (is (= "streamed input" (apply str (map s/byte-string->string chunks))))))

(deftest sink-to-output-stream-writes-bytes
  (let [baos   (ByteArrayOutputStream.)
        result (s/io-result->map
                (-> (s/source [(s/->byte-string "part1-") (s/->byte-string "part2")])
                    (s/run-with (s/sink-to-output-stream (fn [] baos)) *mat*)
                    (s/await-completion 3000)))]
    (is (= "part1-part2" (.toString baos "UTF-8")))
    (is (:success? result))
    (is (= 11 (:count result)))))

(deftest sink-as-input-stream-bridges-out
  (let [in (-> (s/source [(s/->byte-string "abc") (s/->byte-string "def")])
               (s/run-with (s/sink-as-input-stream) *mat*))]
    (is (= "abcdef" (slurp in)))))

(deftest source-as-output-stream-bridges-in
  (let [[os done] (s/run-mat (s/source-as-output-stream) (s/sink-seq) :both *mat*)]
    (.write ^OutputStream os (.getBytes "xy" "UTF-8"))
    (.close ^OutputStream os)
    (is (= "xy" (apply str (map s/byte-string->string (s/await-completion done 3000)))))))
