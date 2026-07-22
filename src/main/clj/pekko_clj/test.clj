(ns pekko-clj.test
  "Testing companion for pekko-clj, built on Apache Pekko TestKit.

   Provides idiomatic Clojure wrappers for:
   - TestProbe (via javadsl.TestKit): probe, expect-msg, expect-msg-type,
     expect-no-message, receive-n, await-assert, within, fish-for-message,
     watch/expect-terminated, ...
   - test-actor-ref: a synchronous (CallingThreadDispatcher) actor whose message
     handling runs on the calling thread, for deterministic unit tests.
   - test-source / test-sink: Pekko Streams probes for driving and asserting.

   Backed by the bundled pekko-testkit / pekko-stream-testkit dependencies.

   Example:
     (let [sys (core/actor-system \"t\")
           p   (t/probe sys)
           echo (core/spawn sys echo-actor)]
       (t/send-to p echo :ping)
       (t/expect-msg p :ping)
       (t/shutdown p))"
  (:import [org.apache.pekko.actor ActorRef ActorSystem]
           [org.apache.pekko.testkit TestActorRef]
           [org.apache.pekko.testkit.javadsl TestKit]
           [org.apache.pekko.stream.testkit.javadsl TestSource TestSink]
           [pekko_clj.actor CljActor]
           [java.time Duration]
           [java.util.function Supplier Function]))

;; ---------------------------------------------------------------------------
;; Durations
;; ---------------------------------------------------------------------------

(defn seconds
  "A java.time.Duration of n seconds."
  ^Duration [n]
  (Duration/ofSeconds (long n)))

(defn millis
  "A java.time.Duration of n milliseconds."
  ^Duration [n]
  (Duration/ofMillis (long n)))

;; ---------------------------------------------------------------------------
;; TestProbe (backed by javadsl.TestKit)
;; ---------------------------------------------------------------------------

(defn probe
  "Create a test probe on the given ActorSystem. A probe is an actor you can send
   messages to (see `probe-ref`) and make expectations against."
  ^TestKit [^ActorSystem system]
  (TestKit. system))

(defn probe-ref
  "The probe's ActorRef — send messages here to have the probe receive them."
  ^ActorRef [^TestKit probe]
  (.getRef probe))

(defn last-sender
  "The sender of the last message the probe received."
  ^ActorRef [^TestKit probe]
  (.getLastSender probe))

(defn send-to
  "Send `msg` to `target` as if from the probe, so replies come back to the probe."
  [^TestKit probe ^ActorRef target msg]
  (.send probe target msg))

(defn expect-msg
  "Assert the probe receives a message equal to `msg` (optionally within a
   Duration); returns the message."
  ([^TestKit probe msg] (.expectMsg probe msg))
  ([^TestKit probe ^Duration timeout msg] (.expectMsg probe timeout msg)))

(defn expect-msg-type
  "Assert the probe receives a message that is an instance of `klass` (optionally
   within a Duration); returns the message."
  ([^TestKit probe ^Class klass] (.expectMsgClass probe klass))
  ([^TestKit probe ^Duration timeout ^Class klass] (.expectMsgClass probe timeout klass)))

(defn expect-no-message
  "Assert the probe receives no message (optionally within a Duration)."
  ([^TestKit probe] (.expectNoMessage probe))
  ([^TestKit probe ^Duration timeout] (.expectNoMessage probe timeout)))

(defn receive-n
  "Receive exactly `n` messages (optionally within a Duration); returns a vector."
  ([^TestKit probe n] (vec (.receiveN probe (int n))))
  ([^TestKit probe n ^Duration timeout] (vec (.receiveN probe (int n) timeout))))

(defn await-assert
  "Run 0-arg `assert-fn` repeatedly until it returns without throwing, or `max`
   elapses (default from testkit config). Returns assert-fn's value."
  ([^TestKit probe assert-fn]
   (.awaitAssert probe (reify Supplier (get [_] (assert-fn)))))
  ([^TestKit probe ^Duration max assert-fn]
   (.awaitAssert probe max (reify Supplier (get [_] (assert-fn))))))

(defn within
  "Run 0-arg `body-fn` and assert it completes within [min] max. Returns its value."
  ([^TestKit probe ^Duration max body-fn]
   (.within probe max (reify Supplier (get [_] (body-fn)))))
  ([^TestKit probe ^Duration min ^Duration max body-fn]
   (.within probe min max (reify Supplier (get [_] (body-fn))))))

(defn fish-for-message
  "Receive messages until `pred` returns truthy for one (which is returned) or
   `max` elapses. `hint` appears in the failure message."
  ([^TestKit probe ^Duration max pred]
   (fish-for-message probe max "" pred))
  ([^TestKit probe ^Duration max ^String hint pred]
   (.fishForMessage probe max hint
                    (reify Function (apply [_ m] (boolean (pred m)))))))

(defn watch
  "Have the probe death-watch `target`."
  [^TestKit probe ^ActorRef target]
  (.watch probe target))

(defn unwatch
  "Stop the probe death-watching `target`."
  [^TestKit probe ^ActorRef target]
  (.unwatch probe target))

(defn expect-terminated
  "Assert the probe (which must be watching `target`) sees `target` terminate."
  ([^TestKit probe ^ActorRef target] (.expectTerminated probe target))
  ([^TestKit probe ^Duration timeout ^ActorRef target]
   (.expectTerminated probe timeout target)))

(defn shutdown
  "Shut down the probe's actor system (convenience for test teardown)."
  [^TestKit probe]
  (TestKit/shutdownActorSystem (.getSystem probe)))

;; ---------------------------------------------------------------------------
;; Synchronous actor (TestActorRef)
;; ---------------------------------------------------------------------------

(defn test-actor-ref
  "Create a synchronous actor from a defactor def. It runs on the calling thread
   (CallingThreadDispatcher), so a `!`/`.tell` is fully processed before it
   returns — handy for deterministic unit tests. Use `underlying` to reach the
   CljActor (deref it for state)."
  ([^ActorSystem system actor-def] (test-actor-ref system actor-def nil))
  ([^ActorSystem system actor-def args]
   (TestActorRef/create system (CljActor/create ((:make-props actor-def) args)))))

(defn underlying
  "The underlying CljActor of a test-actor-ref (deref it to read its state)."
  [^TestActorRef ref]
  (.underlyingActor ref))

;; ---------------------------------------------------------------------------
;; Stream probes
;; ---------------------------------------------------------------------------

(defn test-source
  "A Source whose materialized value is a TestPublisher probe you drive manually
   (sendNext / sendComplete / sendError). Materialize it to obtain the probe."
  [^ActorSystem system]
  (TestSource/probe system))

(defn test-sink
  "A Sink whose materialized value is a TestSubscriber probe you assert against
   (request / expectNext / expectComplete). Materialize it to obtain the probe."
  [^ActorSystem system]
  (TestSink/probe system))
