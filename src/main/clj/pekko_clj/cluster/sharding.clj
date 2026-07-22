(ns pekko-clj.cluster.sharding
  "Cluster Sharding support for pekko-clj.

   Cluster sharding distributes actors across the cluster based on their
   entity ID. Messages are routed to the correct node automatically.

   Key concepts:
   - Entity: An actor instance identified by a unique ID
   - Shard: A group of entities managed together
   - ShardRegion: Entry point for sending messages to entities
   - EntityRef: A reference to a specific entity for direct messaging

   Example:
     ;; Define a sharded entity actor. It matches the raw message it is sent and
     ;; reads its own id with (sharding/entity-id).
     (core/defactor order-actor
       (init [_] {:items []})
       (handle [:add-item item]
         (update state :items conj item))
       (handle :get-items
         (core/reply {:order-id (sharding/entity-id) :items (:items state)})))

     ;; Start sharding
     (def orders (sharding/start sys order-actor
                   {:type-name \"Order\"
                    :role \"orders\"}))

     ;; Send messages to entities (creates them on demand)
     (sharding/tell orders \"order-123\" [:add-item {:sku \"ABC\" :qty 2}])
     (sharding/ask orders \"order-456\" :get-items)

     ;; Or use EntityRef for more direct access
     (def order-ref (sharding/entity-ref sys \"Order\" \"order-123\"))
     (sharding/tell-entity order-ref [:add-item {:sku \"XYZ\" :qty 1}])
     (sharding/ask-entity order-ref :get-items)

   Idle entities can be passivated automatically (see `start`'s :passivation and
   `passivation-settings`) or on request (`passivate`). For always-on workers that
   are not addressed by entity id, see `pekko-clj.cluster.daemon`."
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.cluster.sharding ClusterSharding ClusterShardingSettings
            ClusterShardingSettings$PassivationStrategySettings
            ClusterShardingSettings$PassivationStrategySettings$LeastRecentlyUsedSettings
            ClusterShardingSettings$PassivationStrategySettings$LeastFrequentlyUsedSettings
            ShardRegion$MessageExtractor
            ShardRegion$HashCodeMessageExtractor
            ShardRegion$Passivate
            ShardRegion$GetClusterShardingStats
            ShardRegion$ClusterShardingStats
            ShardRegion$ShardRegionStats]
           [pekko_clj.actor CljActor]
           [com.typesafe.config Config ConfigFactory]
           [java.time Duration]
           [scala.concurrent.duration FiniteDuration]
           [java.util.concurrent TimeUnit]))

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
    ;; Only EntityMessage envelopes (produced by tell/ask/entity-ref) carry an
    ;; entity id and are routed; anything else has no id and is dropped by Pekko.
    (entityId [message]
      (when (instance? EntityMessage message)
        (:entity-id message)))
    ;; Deliver the *unwrapped* payload so the entity actor matches the raw
    ;; message pattern it was written for; it reads its own id via (entity-id).
    (entityMessage [message]
      (if (instance? EntityMessage message)
        (:message message)
        message))))

(defn entity-id
  "Return the current sharded entity's id — its actor-path name, which Pekko sets
   to the entity id. Call inside an entity actor's handler or init body."
  []
  (.name (.path ^ActorRef (core/self))))

;; ---------------------------------------------------------------------------
;; Passivation strategies
;; ---------------------------------------------------------------------------

(defn- ->duration
  "Coerce milliseconds or a java.time.Duration to a java.time.Duration."
  ^Duration [d]
  (if (instance? Duration d) d (Duration/ofMillis (long d))))

(defn- lru-settings
  "LeastRecentlyUsedSettings, optionally segmented (SLRU): a number of levels, or
   a sequence of fractional proportions per level."
  [segmented]
  (let [base (ClusterShardingSettings$PassivationStrategySettings$LeastRecentlyUsedSettings/defaults)]
    (cond
      (nil? segmented) base
      (number? segmented) (.withSegmented base (int segmented))
      (sequential? segmented) (.withSegmentedProportions base (java.util.ArrayList. ^java.util.Collection
                                                               (mapv double segmented)))
      :else (throw (IllegalArgumentException.
                    (str ":segmented must be a number of levels or a sequence of "
                         "proportions, got " (class segmented)))))))

(defn passivation-settings
  "Build Pekko's PassivationStrategySettings from a Clojure map. Pass the result (or
   just the map) as `start`'s `:passivation` option.

   Options map:
   - :strategy - :idle (default when only :idle-timeout is given), :least-recently-used,
                 :most-recently-used, :least-frequently-used, or :none to disable
                 automatic passivation entirely
   - :idle-timeout  - passivate an entity idle for this long (ms or java.time.Duration)
   - :idle-interval - how often idle entities are checked (default: half the timeout)
   - :active-entity-limit - passivate when a region holds more than this many entities
                 (required for the replacement-policy strategies)
   - :segmented - :least-recently-used only — number of SLRU levels, or a sequence of
                 fractional proportions (e.g. [0.2 0.8])
   - :dynamic-aging - :least-frequently-used only — age frequency counts over time

   Returns: ClusterShardingSettings.PassivationStrategySettings

   Note: Pekko disables automatic passivation entirely when `:remember-entities` is on.

   Example:
     (passivation-settings {:strategy :least-recently-used
                            :active-entity-limit 10000
                            :segmented [0.2 0.8]})"
  ^ClusterShardingSettings$PassivationStrategySettings [opts]
  (let [{:keys [strategy idle-timeout idle-interval active-entity-limit segmented dynamic-aging]} opts
        strategy (or strategy (if idle-timeout :idle :none))]
    (if (#{:none :off} strategy)
      (ClusterShardingSettings$PassivationStrategySettings/disabled)
      (let [base (cond-> (ClusterShardingSettings$PassivationStrategySettings/defaults)
                   (and idle-timeout idle-interval)
                   (.withIdleEntityPassivation (->duration idle-timeout) (->duration idle-interval))

                   (and idle-timeout (not idle-interval))
                   (.withIdleEntityPassivation (->duration idle-timeout))

                   active-entity-limit
                   (.withActiveEntityLimit (int active-entity-limit)))]
        (case strategy
          :idle base
          :least-recently-used (.withReplacementPolicy base (lru-settings segmented))
          :most-recently-used (.withMostRecentlyUsedReplacement base)
          :least-frequently-used
          (.withReplacementPolicy
           base
           (cond-> (ClusterShardingSettings$PassivationStrategySettings$LeastFrequentlyUsedSettings/defaults)
             dynamic-aging (.withDynamicAging true)))
          (throw (IllegalArgumentException.
                  (str "Unknown passivation strategy: " strategy
                       " (expected :idle, :least-recently-used, :most-recently-used, "
                       ":least-frequently-used or :none)"))))))))

;; ---------------------------------------------------------------------------
;; Sharding Setup
;; ---------------------------------------------------------------------------

(def ^:private remember-entities-stores
  {:ddata "ddata"
   :eventsourced "eventsourced"})

(defn sharding-settings
  "Build ClusterShardingSettings from `start`'s options map (see `start` for the
   keys). Exposed so callers can inspect or further customise the settings."
  ^ClusterShardingSettings [^ActorSystem system opts]
  (let [{:keys [role remember-entities remember-entities-store journal-plugin-id
                snapshot-plugin-id passivation passivate-after]} opts
        ;; The remember-entities store mode has no `with…` setter — it is read from
        ;; config — so start from the system's own sharding section and override it.
        sharding-cfg (.getConfig (.config (.settings system)) "pekko.cluster.sharding")
        store (when remember-entities-store
                (or (remember-entities-stores remember-entities-store)
                    (throw (IllegalArgumentException.
                            (str "Unknown :remember-entities-store: " remember-entities-store
                                 " (expected :ddata or :eventsourced)")))))
        ^Config cfg (if store
                      (.withFallback (ConfigFactory/parseString
                                      (str "remember-entities-store = \"" store "\""))
                                     sharding-cfg)
                      sharding-cfg)
        passivation-opts (cond
                           passivation passivation
                           passivate-after {:strategy :idle :idle-timeout passivate-after})]
    (cond-> (ClusterShardingSettings/create cfg)
      role (.withRole ^String role)
      remember-entities (.withRememberEntities true)
      journal-plugin-id (.withJournalPluginId ^String journal-plugin-id)
      snapshot-plugin-id (.withSnapshotPluginId ^String snapshot-plugin-id)
      passivation-opts (.withPassivationStrategy
                        (if (instance? ClusterShardingSettings$PassivationStrategySettings passivation-opts)
                          passivation-opts
                          (passivation-settings passivation-opts))))))

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
     - :passivate-after - Passivate idle entities after this many ms (shorthand for
                          :passivation {:strategy :idle :idle-timeout ms})
     - :passivation - Passivation strategy: a map for `passivation-settings` (idle
                          timeouts and/or an active-entity limit with an LRU/MRU/LFU
                          replacement policy), or a PassivationStrategySettings
     - :remember-entities - Restart entities after a shard moves (default: false).
                          Turning this on disables automatic passivation.
     - :remember-entities-store - :ddata (default) or :eventsourced; how remembered
                          entity ids are stored. :eventsourced needs a journal.
     - :journal-plugin-id / :snapshot-plugin-id - Persistence plugins to use for the
                          :eventsourced remember-entities store
     - :stop-message - Message sent to an entity to stop it gracefully during shard
                          rebalance/hand-off (default: Pekko's PoisonPill)

   Returns the ShardRegion ActorRef.

   Entity actors are all created from the same Props (their init receives nil
   args); an entity reads its own id at runtime with (entity-id).

   Example:
     (sharding/start sys order-actor
       {:type-name \"Order\"
        :role \"orders\"
        :num-shards 100
        :passivation {:strategy :least-recently-used
                      :active-entity-limit 10000}
        :stop-message :stop-now})"
  [^ActorSystem system actor-def opts]
  (let [{:keys [type-name num-shards stop-message]
         :or {num-shards 100}} opts
        ;; Create a Props - entity-id will be extracted from messages
        ;; and passed via the message extractor
        props (CljActor/create ((:make-props actor-def) nil))
        settings (sharding-settings system opts)
        ^ShardRegion$MessageExtractor extractor (create-message-extractor num-shards)
        sharding (ClusterSharding/get system)]
    ;; Start the shard region with Props and MessageExtractor. A hand-off stop
    ;; message requires the overload that also takes an allocation strategy.
    (if (some? stop-message)
      (.start sharding ^String type-name props settings extractor
              (.defaultShardAllocationStrategy sharding settings)
              stop-message)
      (.start sharding ^String type-name props settings extractor))))

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

   Returns a java.util.concurrent.CompletableFuture of the response (deref with @,
   compose with .thenApply, or block with pekko-clj.core/<!).

   Arguments:
   - shard-region: The ShardRegion ActorRef
   - entity-id: The entity's unique identifier
   - message: The message to send
   - timeout-ms: Timeout in milliseconds (default: pekko-clj.core/*timeout*)"
  ([shard-region entity-id message]
   (ask shard-region entity-id message core/*timeout*))
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

;; ---------------------------------------------------------------------------
;; EntityRef - Direct Entity Access
;; ---------------------------------------------------------------------------

(deftype EntityRef [shard-region entity-id type-name]
  clojure.lang.ILookup
  (valAt [_ k]
    (case k
      :entity-id entity-id
      :shard-region shard-region
      :type-name type-name
      nil))
  (valAt [this k not-found]
    (or (.valAt this k) not-found))

  Object
  (toString [_]
    (str "EntityRef(" type-name "/" entity-id ")")))

(defn entity-ref
  "Get a reference to a specific entity.

   Returns an EntityRef that can be used to send messages directly
   without manually wrapping in an envelope each time.

   Arguments:
   - system: ActorSystem
   - type-name: The entity type name
   - entity-id: The entity's unique identifier

   Example:
     (def order-ref (entity-ref sys \"Order\" \"order-123\"))
     (tell-entity order-ref [:add-item item])
     (ask-entity order-ref :get-items)"
  [^ActorSystem system type-name entity-id]
  (let [shard-region (get-shard-region system type-name)]
    (->EntityRef shard-region (str entity-id) type-name)))

(defn tell-entity
  "Send a message to an entity via its EntityRef.

   Example:
     (tell-entity order-ref [:add-item {:sku \"ABC\" :qty 2}])"
  [^EntityRef ref message]
  (tell (.shard-region ref) (.entity-id ref) message))

(defn ask-entity
  "Send a message to an entity via its EntityRef and wait for a reply.

   Returns a java.util.concurrent.CompletableFuture of the response (deref with @,
   compose with .thenApply, or block with pekko-clj.core/<!).

   Example:
     (ask-entity order-ref :get-items)
     (ask-entity order-ref :get-items 10000)"
  ([^EntityRef ref message]
   (ask-entity ref message core/*timeout*))
  ([^EntityRef ref message timeout-ms]
   (ask (.shard-region ref) (.entity-id ref) message timeout-ms)))

;; ---------------------------------------------------------------------------
;; Cluster Sharding Statistics
;; ---------------------------------------------------------------------------

(defn cluster-sharding-stats
  "Get sharding statistics across all cluster nodes.

   Arguments:
   - system: ActorSystem
   - type-name: The entity type name
   - timeout-ms: Timeout for gathering stats (default: pekko-clj.core/*timeout*)

   Returns a future of the ClusterShardingStats object containing:
   - regions: Map of region addresses to their shard stats"
  ([system type-name]
   (cluster-sharding-stats system type-name core/*timeout*))
  ([system type-name timeout-ms]
   (let [shard-region (get-shard-region system type-name)
         timeout (FiniteDuration/create (long timeout-ms) TimeUnit/MILLISECONDS)
         msg (ShardRegion$GetClusterShardingStats. timeout)]
     (core/<?> shard-region msg timeout-ms))))

(defn stats->map
  "Convert ClusterShardingStats to a Clojure map.

   Returns:
   - :regions - Map of region address to shard stats map
     - Each shard stats map contains shard-id -> entity-count"
  [^ShardRegion$ClusterShardingStats stats]
  (let [regions (.getRegions stats)]
    {:regions
     (into {}
           (for [entry (seq regions)]
             (let [addr (key entry)
                   shard-stats ^ShardRegion$ShardRegionStats (val entry)]
               [addr (into {}
                           (for [shard-entry (seq (.getStats shard-stats))]
                             [(key shard-entry) (val shard-entry)]))])))}))

;; ---------------------------------------------------------------------------
;; Entity Passivation
;; ---------------------------------------------------------------------------

(defn passivate
  "Request passivation for an entity from within its actor.

   Call this from within an entity actor to request graceful shutdown: the shard
   buffers any further messages for this entity, sends it `stop-message`, and stops
   it once that message is handled. The entity is recreated on the next message.

   Arguments:
   - context: The actor context (defaults to the current actor's)
   - stop-message: Message the entity will receive before stopping

   To actually stop on the stop-message, handle it by stopping self — e.g. with
   (core/stop (core/self)).

   Example:
     (core/defactor my-entity
       (handle :cleanup
         (sharding/passivate :final-stop)
         state)
       (handle :final-stop
         (save-state! state)
         (core/stop (core/self))
         state))"
  ([stop-message] (passivate (core/context) stop-message))
  ([^org.apache.pekko.actor.ActorContext context stop-message]
   (let [parent (.parent context)]
     (core/! parent (ShardRegion$Passivate. stop-message)))))

;; ---------------------------------------------------------------------------
;; Health Checks
;; ---------------------------------------------------------------------------

(defn shard-region-registered?
  "Check if a shard region is registered for the given type name.

   Arguments:
   - system: ActorSystem
   - type-name: The entity type name

   Returns true if the region is registered."
  [^ActorSystem system type-name]
  (try
    (some? (get-shard-region system type-name))
    (catch Exception _
      false)))
