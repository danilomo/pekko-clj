(ns pekko-clj.event-stream
  "Actor-system EventStream helpers: a publish-subscribe bus, keyed by message
   class, that Pekko itself uses for DeadLetter, UnhandledMessage, logging, and
   cluster events. This generalizes the internal cluster-event subscriber so you
   can observe any event class with a plain handler function.

   Example:
     ;; Observe messages that reached no actor:
     (event-stream/subscribe-dead-letters sys
       (fn [{:keys [message recipient]}]
         (println \"dead letter:\" message \"->\" recipient)))

     ;; Subscribe to your own event class and publish to it:
     (event-stream/subscribe sys MyEvent (fn [e] (handle e)))
     (event-stream/publish sys (MyEvent. ...))"
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef DeadLetter UnhandledMessage]
           [org.apache.pekko.event EventStream]))

(defn event-stream
  "The ActorSystem's EventStream."
  ^EventStream [^ActorSystem system]
  (.eventStream system))

;; ---------------------------------------------------------------------------
;; Internal handler-backed subscriber
;; ---------------------------------------------------------------------------

(core/defactor event-subscriber
  "Internal actor: calls a handler fn for each event delivered to it."
  (init [args] {:handler (:handler args)})
  (handle msg
    ((:handler state) msg)
    state))

;; ---------------------------------------------------------------------------
;; Subscribe / unsubscribe / publish
;; ---------------------------------------------------------------------------

(defn subscribe
  "Subscribe to events whose class is `event-class` (or a subclass). The final
   argument is either an ActorRef (subscribed directly) or a function of one
   argument (an internal subscriber actor is spawned that calls it per event).
   Returns the subscriber ActorRef — pass it to `unsubscribe`."
  [^ActorSystem system ^Class event-class subscriber-or-fn]
  (let [ref (if (instance? ActorRef subscriber-or-fn)
              subscriber-or-fn
              (core/spawn system event-subscriber {:handler subscriber-or-fn}))]
    (.subscribe (event-stream system) ref event-class)
    ref))

(defn unsubscribe
  "Unsubscribe `subscriber-ref` from `event-class`, or from ALL classes when no
   class is given. Returns nil."
  ([^ActorSystem system ^ActorRef subscriber-ref]
   (.unsubscribe (event-stream system) subscriber-ref)
   nil)
  ([^ActorSystem system ^ActorRef subscriber-ref ^Class event-class]
   (.unsubscribe (event-stream system) subscriber-ref event-class)
   nil))

(defn publish
  "Publish `event` to the EventStream; subscribers registered for its class (or a
   superclass) receive it. Returns nil."
  [^ActorSystem system event]
  (.publish (event-stream system) event)
  nil)

;; ---------------------------------------------------------------------------
;; Dead-letter / unhandled-message conveniences
;; ---------------------------------------------------------------------------

(defn dead-letter->map
  "A DeadLetter as a Clojure map {:message :sender :recipient}."
  [^DeadLetter dl]
  {:message (.message dl) :sender (.sender dl) :recipient (.recipient dl)})

(defn unhandled->map
  "An UnhandledMessage as a Clojure map {:message :sender :recipient}."
  [^UnhandledMessage um]
  {:message (.message um) :sender (.sender um) :recipient (.recipient um)})

(defn subscribe-dead-letters
  "Subscribe `handler` to DeadLetter events (messages that reached no actor).
   `handler` receives a map {:message :sender :recipient}. Returns the subscriber
   ActorRef."
  [system handler]
  (subscribe system DeadLetter (fn [dl] (handler (dead-letter->map dl)))))

(defn subscribe-unhandled
  "Subscribe `handler` to UnhandledMessage events (delivered but unhandled — see
   `pekko-clj.core/unhandled`). `handler` receives a map {:message :sender
   :recipient}. Returns the subscriber ActorRef."
  [system handler]
  (subscribe system UnhandledMessage (fn [um] (handler (unhandled->map um)))))
