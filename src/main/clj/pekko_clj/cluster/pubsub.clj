(ns pekko-clj.cluster.pubsub
  "Distributed Publish-Subscribe for pekko-clj.

   A thin wrapper over Pekko's `DistributedPubSub` mediator (from
   `pekko-cluster-tools`). It offers two messaging patterns across a cluster:

   1. Topic pub-sub — many subscribers register interest in a named topic;
      `publish` delivers a message to all of them (at-most-once, best-effort).
   2. Point-to-point / broadcast by path — actors register themselves with `put`
      under their actor path; `send` routes to one registered actor at a path and
      `send-to-all` to every registered actor at that path.

   The mediator is a cluster extension: registrations gossip to the mediators on
   the other nodes, so subscribe/put on one node become visible cluster-wide (with
   a small propagation delay). All functions take either an ActorSystem or a
   mediator ActorRef as their first argument, so they work both at the top level
   and from inside an actor handler (cache the mediator ref there).

   Example:
     ;; A subscriber that prints every message published to \"news\":
     (pubsub/subscribe sys \"news\" (fn [msg] (println \"got:\" msg)))

     ;; Publish from anywhere in the cluster:
     (pubsub/publish sys \"news\" {:headline \"Pekko 1.6\"})

     ;; Subscribe an existing defactor actor directly:
     (def worker (core/spawn sys worker-actor))
     (pubsub/subscribe sys \"jobs\" worker)"
  (:refer-clojure :exclude [send remove])
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorRef ActorSystem]
           [org.apache.pekko.cluster.pubsub DistributedPubSub
            DistributedPubSubMediator$Subscribe
            DistributedPubSubMediator$Unsubscribe
            DistributedPubSubMediator$Publish
            DistributedPubSubMediator$Send
            DistributedPubSubMediator$SendToAll
            DistributedPubSubMediator$Put
            DistributedPubSubMediator$Remove
            DistributedPubSubMediator$SubscribeAck
            DistributedPubSubMediator$UnsubscribeAck]))

;; ---------------------------------------------------------------------------
;; Mediator access
;; ---------------------------------------------------------------------------

(defn mediator
  "The DistributedPubSub mediator ActorRef for `system` (starts the extension on
   first use). Requires a cluster ActorSystem (provider = cluster)."
  ^ActorRef [^ActorSystem system]
  (.mediator (DistributedPubSub/get system)))

(defn- ->mediator
  "Coerce `system-or-mediator` to a mediator ActorRef: an ActorSystem is resolved
   to its mediator; an ActorRef is returned as-is (already a mediator)."
  ^ActorRef [system-or-mediator]
  (if (instance? ActorSystem system-or-mediator)
    (mediator system-or-mediator)
    system-or-mediator))

;; ---------------------------------------------------------------------------
;; Acknowledgements (mediator replies to the subscribing actor)
;; ---------------------------------------------------------------------------

(defn subscribe-ack?
  "True if `msg` is a SubscribeAck the mediator sends to confirm a subscription."
  [msg]
  (instance? DistributedPubSubMediator$SubscribeAck msg))

(defn unsubscribe-ack?
  "True if `msg` is an UnsubscribeAck the mediator sends to confirm an unsubscribe."
  [msg]
  (instance? DistributedPubSubMediator$UnsubscribeAck msg))

;; ---------------------------------------------------------------------------
;; Internal handler-backed subscriber
;; ---------------------------------------------------------------------------

(core/defactor topic-subscriber
  "Internal actor: subscribes itself to a topic on start and invokes a handler fn
   for each published message. SubscribeAck/UnsubscribeAck replies are swallowed."
  (init [args]
    (let [{:keys [mediator topic group]} args]
      (core/! mediator (if group
                         (DistributedPubSubMediator$Subscribe. ^String topic ^String group (core/self))
                         (DistributedPubSubMediator$Subscribe. ^String topic (core/self))))
      args))
  (handle msg
    (when-not (or (subscribe-ack? msg) (unsubscribe-ack? msg))
      ((:handler state) msg))
    state))

;; ---------------------------------------------------------------------------
;; Topic pub-sub
;; ---------------------------------------------------------------------------

(defn subscribe
  "Subscribe to `topic`. The final argument is either:
   - an ActorRef: subscribed directly; it will receive raw published messages (and
     a SubscribeAck reply — match it with `subscribe-ack?` or let it fall through
     to `unhandled`);
   - a function of one argument: an internal subscriber actor is spawned that calls
     (f message) for each published message (acks are swallowed).

   With `group`, the subscriber joins that group of the topic; a `publish` with
   :one-per-group true then delivers one copy per group (consumer-group semantics).

   Requires an ActorSystem (a subscriber actor may need to be spawned). Returns the
   subscriber ActorRef — pass it to `unsubscribe`."
  ([^ActorSystem system topic subscriber-or-fn]
   (subscribe system topic nil subscriber-or-fn))
  ([^ActorSystem system topic group subscriber-or-fn]
   (if (instance? ActorRef subscriber-or-fn)
     (let [ref subscriber-or-fn
           msg (if group
                 (DistributedPubSubMediator$Subscribe. ^String topic ^String group ^ActorRef ref)
                 (DistributedPubSubMediator$Subscribe. ^String topic ^ActorRef ref))]
       (core/! (mediator system) msg)
       ref)
     (core/spawn system topic-subscriber {:mediator (mediator system)
                                          :topic topic
                                          :group group
                                          :handler subscriber-or-fn}))))

(defn unsubscribe
  "Remove `subscriber-ref`'s subscription to `topic` (optionally within `group`).
   `system-or-mediator` is an ActorSystem or a mediator ActorRef."
  ([system-or-mediator topic ^ActorRef subscriber-ref]
   (unsubscribe system-or-mediator topic nil subscriber-ref))
  ([system-or-mediator topic group ^ActorRef subscriber-ref]
   (core/! (->mediator system-or-mediator)
           (if group
             (DistributedPubSubMediator$Unsubscribe. ^String topic ^String group subscriber-ref)
             (DistributedPubSubMediator$Unsubscribe. ^String topic subscriber-ref)))
   nil))

(defn publish
  "Publish `msg` to every subscriber of `topic`. With `one-per-group?` true, deliver
   one copy to each group (see `subscribe`'s :group). `system-or-mediator` is an
   ActorSystem or a mediator ActorRef. Returns nil."
  ([system-or-mediator topic msg]
   (core/! (->mediator system-or-mediator)
           (DistributedPubSubMediator$Publish. ^String topic msg))
   nil)
  ([system-or-mediator topic msg one-per-group?]
   (core/! (->mediator system-or-mediator)
           (DistributedPubSubMediator$Publish. ^String topic msg (boolean one-per-group?)))
   nil))

;; ---------------------------------------------------------------------------
;; Point-to-point / broadcast by actor path
;; ---------------------------------------------------------------------------

(defn put
  "Register `actor-ref` with the mediator under its actor path so it can receive
   `send`/`send-to-all` messages addressed to that path. `system-or-mediator` is an
   ActorSystem or a mediator ActorRef. Returns nil."
  [system-or-mediator ^ActorRef actor-ref]
  (core/! (->mediator system-or-mediator) (DistributedPubSubMediator$Put. actor-ref))
  nil)

(defn remove
  "Remove the registration at `path` (previously registered with `put`).
   `system-or-mediator` is an ActorSystem or a mediator ActorRef. Returns nil."
  [system-or-mediator ^String path]
  (core/! (->mediator system-or-mediator) (DistributedPubSubMediator$Remove. path))
  nil)

(defn send
  "Send `msg` to ONE actor registered (via `put`) at `path`. With `local-affinity?`
   true (the default) a local registration is preferred when several exist across
   the cluster. `system-or-mediator` is an ActorSystem or a mediator ActorRef.
   Returns nil."
  ([system-or-mediator path msg]
   (core/! (->mediator system-or-mediator)
           (DistributedPubSubMediator$Send. ^String path msg))
   nil)
  ([system-or-mediator path msg local-affinity?]
   (core/! (->mediator system-or-mediator)
           (DistributedPubSubMediator$Send. ^String path msg (boolean local-affinity?)))
   nil))

(defn send-to-all
  "Send `msg` to EVERY actor registered (via `put`) at `path` across the cluster.
   With `all-but-self?` true, skip a registration on the sending node.
   `system-or-mediator` is an ActorSystem or a mediator ActorRef. Returns nil."
  ([system-or-mediator path msg]
   (core/! (->mediator system-or-mediator)
           (DistributedPubSubMediator$SendToAll. ^String path msg))
   nil)
  ([system-or-mediator path msg all-but-self?]
   (core/! (->mediator system-or-mediator)
           (DistributedPubSubMediator$SendToAll. ^String path msg (boolean all-but-self?)))
   nil))
