(ns pekko-clj.cluster.sharding
  "Cluster Sharding support for pekko-clj.

   Cluster sharding distributes actors across the cluster based on their
   entity ID. Messages are routed to the correct node automatically.

   Key concepts:
   - Entity: An actor instance identified by a unique ID
   - Shard: A group of entities managed together
   - ShardRegion: Entry point for sending messages to entities

   Example:
     ;; Define a sharded entity actor
     (core/defactor order-actor
       (init [args] {:order-id (:entity-id args) :items []})
       (handle [:add-item item]
         (update state :items conj item))
       (handle :get-items
         (core/reply (:items state))))

     ;; Start sharding
     (def orders (sharding/start sys order-actor
                   {:type-name \"Order\"
                    :role \"orders\"}))

     ;; Send messages to entities (creates them on demand)
     (sharding/tell orders \"order-123\" [:add-item {:sku \"ABC\" :qty 2}])
     (sharding/ask orders \"order-456\" :get-items)"
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef Props]
           [org.apache.pekko.cluster.sharding ClusterSharding ClusterShardingSettings
                                              ShardRegion$MessageExtractor
                                              ShardRegion$HashCodeMessageExtractor]
           [pekko_clj.actor CljActor]))

;; ---------------------------------------------------------------------------
;; Message Envelope
;; ---------------------------------------------------------------------------

(defrecord EntityMessage [entity-id message])

(defn entity-message
  "Create a message envelope for a specific entity."
  [entity-id message]
  (->EntityMessage entity-id message))

;; ---------------------------------------------------------------------------
;; Message Extractor
;; ---------------------------------------------------------------------------

(defn- create-message-extractor
  "Create a message extractor for sharding.

   The extractor determines:
   - entity-id: Which entity should receive the message
   - shard-id: Which shard the entity belongs to"
  [num-shards]
  (proxy [ShardRegion$HashCodeMessageExtractor] [(int num-shards)]
    (entityId [message]
      (cond
        (instance? EntityMessage message) (:entity-id message)
        (and (vector? message) (>= (count message) 2))
        (str (first message))  ; First element as entity ID
        :else nil))
    (entityMessage [message]
      (cond
        (instance? EntityMessage message)
        ;; Wrap with entity-id so entity knows its ID
        [:entity-message (:entity-id message) (:message message)]
        (and (vector? message) (>= (count message) 2))
        (subvec message 1)  ; Rest as the actual message
        :else message))))

;; ---------------------------------------------------------------------------
;; Sharding Setup
;; ---------------------------------------------------------------------------

(defn start
  "Start cluster sharding for an entity type.

   Creates a ShardRegion that routes messages to entity actors based on
   entity ID. Entity actors are created on-demand when they receive their
   first message.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition from defactor
   - opts: Options map
     - :type-name - Name for this entity type (required)
     - :role - Role constraint (only nodes with this role host entities)
     - :num-shards - Number of shards (default: 100)
     - :passivate-after - Passivate idle entities after duration (ms)
     - :remember-entities - Remember entity IDs across restarts (default: false)

   Returns the ShardRegion ActorRef.

   The entity actor's init function receives {:entity-id <id>} as args.

   Example:
     (sharding/start sys order-actor
       {:type-name \"Order\"
        :role \"orders\"
        :num-shards 100
        :passivate-after 300000})"
  [^ActorSystem system actor-def opts]
  (let [{:keys [type-name role num-shards passivate-after remember-entities]
         :or {num-shards 100 remember-entities false}} opts
        ;; Create a Props - entity-id will be extracted from messages
        ;; and passed via the message extractor
        props (CljActor/create ((:make-props actor-def) nil))
        settings (cond-> (ClusterShardingSettings/create system)
                   role (.withRole role)
                   passivate-after
                   (.withPassivateIdleEntityAfter
                     (java.time.Duration/ofMillis passivate-after))
                   remember-entities (.withRememberEntities true))
        extractor (create-message-extractor num-shards)
        sharding (ClusterSharding/get system)]
    ;; Start the shard region with Props and MessageExtractor
    (.start sharding type-name props settings extractor)))

(defn start-proxy
  "Start a proxy-only shard region.

   Use this on nodes that need to send messages to sharded entities
   but don't host any entities themselves.

   Arguments:
   - system: ActorSystem
   - opts: Options map
     - :type-name - Name of the entity type (required)
     - :role - Role where entities run
     - :num-shards - Number of shards (must match the hosting region)

   Returns the ShardRegion proxy ActorRef."
  [^ActorSystem system opts]
  (let [{:keys [type-name role num-shards]
         :or {num-shards 100}} opts
        sharding (ClusterSharding/get system)
        extractor (create-message-extractor num-shards)]
    (.startProxy sharding type-name
                 (java.util.Optional/ofNullable role)
                 extractor)))

;; ---------------------------------------------------------------------------
;; Sending Messages
;; ---------------------------------------------------------------------------

(defn tell
  "Send a message to a sharded entity.

   Arguments:
   - shard-region: The ShardRegion ActorRef
   - entity-id: The entity's unique identifier
   - message: The message to send"
  [shard-region entity-id message]
  (core/! shard-region (entity-message entity-id message)))

(defn ask
  "Send a message to a sharded entity and wait for a reply.

   Returns a Scala Future of the response.

   Arguments:
   - shard-region: The ShardRegion ActorRef
   - entity-id: The entity's unique identifier
   - message: The message to send
   - timeout-ms: Timeout in milliseconds (default: 5000)"
  ([shard-region entity-id message]
   (ask shard-region entity-id message 5000))
  ([shard-region entity-id message timeout-ms]
   (core/<?> shard-region (entity-message entity-id message) timeout-ms)))

;; ---------------------------------------------------------------------------
;; Shard Region Info
;; ---------------------------------------------------------------------------

(defn get-shard-region
  "Get an existing shard region by type name."
  [^ActorSystem system type-name]
  (.shardRegion (ClusterSharding/get system) type-name))

(defn shard-region-state
  "Get the current state of a shard region.
   Sends GetShardRegionState message and returns a future."
  [shard-region]
  (core/<?> shard-region (org.apache.pekko.cluster.sharding.ShardRegion/getShardRegionStateInstance)))
