(ns pekko-clj.cluster
  "Pekko Cluster support for pekko-clj.

   Provides cluster membership, event subscription, and cluster-aware features.

   Example:
     ;; Create a cluster-enabled system
     (def sys (cluster/create-system \"my-app\" cluster-config))

     ;; Subscribe to cluster events
     (cluster/subscribe sys (fn [event] (println \"Cluster event:\" event)))

     ;; Join a cluster
     (cluster/join sys \"pekko://my-app@127.0.0.1:7355\")

     ;; Join using seed nodes for dynamic discovery
     (cluster/join-seed-nodes sys [\"pekko://my-app@host1:7355\"
                                   \"pekko://my-app@host2:7355\"])

     ;; Get cluster state
     (cluster/members sys)
     (cluster/leader sys)
     (cluster/self-member sys)
     (cluster/state-snapshot sys)"
  (:require [pekko-clj.core :as core]
            [pekko-clj.serialization :as serialization]
            [clojure.string :as str])
  (:import [org.apache.pekko Done]
           [org.apache.pekko.actor ActorSystem ActorRef Address AddressFromURIString
                                   CoordinatedShutdown CoordinatedShutdown$Reason]
           [org.apache.pekko.cluster Cluster Member MemberStatus ClusterEvent$ClusterDomainEvent
                                     ClusterEvent$MemberUp ClusterEvent$MemberRemoved
                                     ClusterEvent$MemberExited ClusterEvent$MemberDowned
                                     ClusterEvent$MemberWeaklyUp ClusterEvent$MemberLeft
                                     ClusterEvent$MemberJoined ClusterEvent$MemberPreparingForShutdown
                                     ClusterEvent$UnreachableMember ClusterEvent$ReachableMember
                                     ClusterEvent$LeaderChanged ClusterEvent$RoleLeaderChanged]
           [com.typesafe.config Config ConfigFactory]
           [java.util Set List Optional]
           [java.util.function Supplier]
           [java.util.concurrent CompletionStage CompletableFuture]))

;; ---------------------------------------------------------------------------
;; Cluster Access
;; ---------------------------------------------------------------------------

(defn cluster
  "Get the Cluster extension for an ActorSystem."
  [^ActorSystem system]
  (Cluster/get system))

;; ---------------------------------------------------------------------------
;; Split Brain Resolver (SBR) configuration
;; ---------------------------------------------------------------------------

(def ^:private sbr-provider-class
  "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider")

(def ^:private sbr-strategies
  "Map of strategy keyword → Pekko active-strategy config name."
  {:keep-majority "keep-majority"
   :static-quorum "static-quorum"
   :keep-oldest   "keep-oldest"
   :down-all      "down-all"
   :lease-majority "lease-majority"})

(defn- sbr-duration
  "Render a duration for HOCON: a number is milliseconds; a string is used as-is."
  [d]
  (cond
    (number? d) (str (long d) "ms")
    (string? d) d
    :else (throw (IllegalArgumentException.
                  (str "Duration must be a number (ms) or HOCON string, got " (class d))))))

(defn- sbr-down-all-when-unstable
  "Render :down-all-when-unstable: true→on, false→off, else a duration."
  [v]
  (cond
    (true? v) "on"
    (false? v) "off"
    :else (sbr-duration v)))

(defn- hocon-string
  "Quote a value as a HOCON string literal."
  [s]
  (str \" (str/replace (str s) "\"" "\\\"") \"))

(defn split-brain-resolver-config
  "Build a com.typesafe.config.Config configuring Pekko's Split Brain Resolver
   (the recommended downing provider). Pass the result as `:extra-config` to
   `create-system`, or use `create-system`'s `:split-brain-resolver` key which
   calls this for you.

   Options map:
   - :active-strategy - :keep-majority (default), :static-quorum, :keep-oldest,
                        :down-all, or :lease-majority
   - :stable-after    - quiet period before the SBR decides; a number of
                        milliseconds or a HOCON duration string (default 20s)
   - :down-all-when-unstable - true (on), false (off), or a duration; downs all
                        nodes if no decision is reached in time
   - :role            - restrict the decision to members with this role
                        (keep-majority / static-quorum / keep-oldest / lease-majority)
   - :quorum-size     - required for :static-quorum
   - :down-if-alone   - :keep-oldest only (default on)
   - :lease-implementation, :lease-name, :acquire-lease-delay, :release-after
                        - :lease-majority tuning

   Example:
     (split-brain-resolver-config {:active-strategy :static-quorum
                                   :quorum-size 3
                                   :role \"backend\"
                                   :stable-after 15000})"
  ^Config [opts]
  (let [{:keys [active-strategy stable-after down-all-when-unstable role
                quorum-size down-if-alone
                lease-implementation lease-name acquire-lease-delay release-after]
         :or {active-strategy :keep-majority}} opts
        strategy-name (or (sbr-strategies active-strategy)
                          (throw (IllegalArgumentException.
                                  (str "Unknown SBR strategy: " active-strategy
                                       " (expected one of " (keys sbr-strategies) ")"))))
        base "pekko.cluster.split-brain-resolver."
        lines (cond-> [(str "pekko.cluster.downing-provider-class = " (hocon-string sbr-provider-class))
                       (str base "active-strategy = " strategy-name)]
                (some? stable-after)
                (conj (str base "stable-after = " (sbr-duration stable-after)))
                (some? down-all-when-unstable)
                (conj (str base "down-all-when-unstable = " (sbr-down-all-when-unstable down-all-when-unstable))))
        strat (case active-strategy
                :keep-majority
                (cond-> [] role (conj (str base "keep-majority.role = " (hocon-string role))))
                :static-quorum
                (cond-> []
                  (some? quorum-size) (conj (str base "static-quorum.quorum-size = " (long quorum-size)))
                  role (conj (str base "static-quorum.role = " (hocon-string role))))
                :keep-oldest
                (cond-> []
                  (some? down-if-alone) (conj (str base "keep-oldest.down-if-alone = " (if down-if-alone "on" "off")))
                  role (conj (str base "keep-oldest.role = " (hocon-string role))))
                :down-all []
                :lease-majority
                (cond-> []
                  lease-implementation (conj (str base "lease-majority.lease-implementation = " (hocon-string lease-implementation)))
                  lease-name (conj (str base "lease-majority.lease-name = " (hocon-string lease-name)))
                  (some? acquire-lease-delay) (conj (str base "lease-majority.acquire-lease-delay-for-minority = " (sbr-duration acquire-lease-delay)))
                  (some? release-after) (conj (str base "lease-majority.release-after = " (sbr-duration release-after)))
                  role (conj (str base "lease-majority.role = " (hocon-string role)))))]
    (ConfigFactory/parseString (str/join "\n" (concat lines strat)))))

;; ---------------------------------------------------------------------------
;; System Creation with Cluster Config
;; ---------------------------------------------------------------------------

(defn create-system
  "Create an ActorSystem with cluster configuration.

   Arguments:
   - name: System name (must be same across cluster nodes)
   - config: Either a Config object or a map with cluster settings

   Config map keys:
   - :hostname - This node's hostname (default: \"127.0.0.1\")
   - :port - This node's port (default: 7355)
   - :seed-nodes - Vector of seed node addresses
   - :roles - Vector of roles for this node
   - :split-brain-resolver - A map of Split Brain Resolver options (see
                     `split-brain-resolver-config`); tunes the downing strategy.
                     Overrides the generated defaults, but :extra-config wins over it.
   - :transit-serialization - `true`, or a map of options for
                     `pekko-clj.serialization/transit-config`, to serialize Clojure
                     data with Transit instead of Java serialization (which it turns
                     off unless `:allow-java-serialization true` is passed).
                     Overridden by :split-brain-resolver and :extra-config.
   - :extra-config - A HOCON string or a com.typesafe.config.Config whose settings
                     are merged with higher precedence over the generated defaults
                     (e.g. to override the default SplitBrainResolver or
                     allow-java-serialization). Takes precedence over everything the
                     map generates (including :split-brain-resolver), but still
                     falls back to reference.conf.

   Example:
     (create-system \"my-app\" {:hostname \"192.168.1.10\"
                                :port 7355
                                :seed-nodes [\"pekko://my-app@192.168.1.10:7355\"
                                             \"pekko://my-app@192.168.1.11:7355\"]
                                :roles [\"backend\"]
                                :split-brain-resolver {:active-strategy :keep-majority
                                                       :stable-after 15000}
                                :extra-config \"pekko.cluster.min-nr-of-members = 2\"})"
  [name config]
  (let [cfg (if (instance? Config config)
              config
              (let [{:keys [hostname port seed-nodes roles extra-config split-brain-resolver
                            transit-serialization]
                     :or {hostname "127.0.0.1" port 7355}} config
                    seed-nodes-str (if seed-nodes
                                     (str "["
                                          (str/join ", "
                                            (map #(str "\"" % "\"") seed-nodes))
                                          "]")
                                     "[]")
                    roles-str (if roles
                                (str "["
                                     (str/join ", "
                                       (map #(str "\"" % "\"") roles))
                                     "]")
                                "[]")
                    config-str (str "
                      pekko {
                        actor {
                          provider = cluster
                          allow-java-serialization = on
                          warn-about-java-serializer-usage = off
                        }
                        remote.artery {
                          canonical.hostname = \"" hostname "\"
                          canonical.port = " port "
                        }
                        cluster {
                          seed-nodes = " seed-nodes-str "
                          roles = " roles-str "
                          downing-provider-class = \"org.apache.pekko.cluster.sbr.SplitBrainResolverProvider\"
                        }
                      }")
                    base-cfg (ConfigFactory/parseString config-str)
                    transit-cfg (when transit-serialization
                                  (serialization/transit-config
                                   (if (map? transit-serialization) transit-serialization {})))
                    sbr-cfg (when split-brain-resolver
                              (split-brain-resolver-config split-brain-resolver))
                    extra-cfg (cond
                                (nil? extra-config) nil
                                (instance? Config extra-config) extra-config
                                (string? extra-config) (ConfigFactory/parseString extra-config)
                                :else (throw (IllegalArgumentException.
                                              (str ":extra-config must be a HOCON string or a "
                                                   "com.typesafe.config.Config, got "
                                                   (class extra-config)))))]
                ;; Precedence (highest first): extra-config > split-brain-resolver >
                ;; transit-serialization > generated defaults > reference.conf (below).
                (cond-> base-cfg
                  transit-cfg (as-> c (.withFallback transit-cfg c))
                  sbr-cfg     (as-> c (.withFallback sbr-cfg c))
                  extra-cfg   (as-> c (.withFallback extra-cfg c)))))]
    (ActorSystem/create name (.withFallback cfg (ConfigFactory/load)))))

;; ---------------------------------------------------------------------------
;; Cluster Membership
;; ---------------------------------------------------------------------------

(declare join-seed-nodes)

(defn join
  "Join the cluster by contacting seed nodes or a specific address.

   If address is provided, joins that specific node.
   If no address is provided, joins using the seed nodes configured under
   `pekko.cluster.seed-nodes` (a no-op if none are configured)."
  ([system]
   (let [cfg (.config (.settings ^ActorSystem system))
         seeds (when (.hasPath cfg "pekko.cluster.seed-nodes")
                 (seq (.getStringList cfg "pekko.cluster.seed-nodes")))]
     (when (seq seeds)
       (join-seed-nodes system seeds))))
  ([system address]
   (.join (cluster system) (AddressFromURIString/parse address))))

(defn join-seed-nodes
  "Join the cluster using a list of seed node addresses.

   This is useful for dynamic cluster discovery where seed nodes
   are determined at runtime (e.g., from a service registry).

   Arguments:
   - system: ActorSystem
   - seed-nodes: Collection of address strings

   Example:
     (join-seed-nodes sys [\"pekko://my-app@host1:7355\"
                           \"pekko://my-app@host2:7355\"])"
  [system seed-nodes]
  (let [addresses (java.util.ArrayList.
                    (map #(AddressFromURIString/parse %) seed-nodes))]
    (.joinSeedNodes (cluster system) addresses)))

(defn leave
  "Leave the cluster gracefully.
   The node will be marked as Leaving and then Exited."
  ([system]
   (.leave (cluster system) (.selfAddress (cluster system))))
  ([system address]
   (.leave (cluster system) address)))

(defn down
  "Mark a node as Down (removed from cluster).
   Use this for unreachable nodes that won't recover."
  [system address]
  (.down (cluster system) address))

;; ---------------------------------------------------------------------------
;; Cluster State
;; ---------------------------------------------------------------------------

(defn self-member
  "Get this node's Member object."
  [system]
  (.selfMember (cluster system)))

(defn self-address
  "Get this node's Address."
  [system]
  (.selfAddress (cluster system)))

(defn- member->map
  "Convert a Member to a Clojure map."
  [^Member m]
  {:address (.address m)
   :status (keyword (str/lower-case (str (.status m))))
   :roles (set (seq (.getRoles m)))
   :unique-address (.uniqueAddress m)
   :upNumber (.upNumber m)})

(defn members
  "Get all current cluster members as a sequence of maps."
  [system]
  (let [state (.state (cluster system))
        member-set (.getMembers state)]
    (map member->map (seq member-set))))

(defn leader
  "Get the current cluster leader's address, or nil if none."
  [system]
  (.getLeader (.state (cluster system))))

(defn is-leader?
  "Check if this node is the cluster leader."
  [system]
  (let [c (cluster system)
        self-addr (.selfAddress c)
        leader-addr (.getLeader (.state c))]
    (= self-addr leader-addr)))

(defn unreachable-members
  "Get members that are currently unreachable."
  [system]
  (let [state (.state (cluster system))]
    (map member->map (seq (.getUnreachable state)))))

(defn role-leader
  "Get the leader for a specific role."
  [system role]
  (.roleLeader (.state (cluster system)) role))

(defn has-role?
  "Check if this node has a specific role."
  [system role]
  (.hasRole (.selfMember (cluster system)) role))

(defn is-terminated?
  "Check if the cluster extension has been terminated."
  [system]
  (.isTerminated (cluster system)))

(defn members-by-age
  "Get cluster members sorted by age (oldest first).

   This is useful for singleton-like patterns where the oldest
   node should take responsibility. Members are sorted by their
   upNumber (join order), with the oldest member having the lowest number.

   Returns a sequence of member maps."
  [system]
  (let [state (.state (cluster system))
        members (seq (.getMembers state))]
    (->> members
         (sort-by #(.upNumber ^Member %))
         (map member->map))))

(defn state-snapshot
  "Get the current cluster state as a map.

   Returns a map with:
   - :members - All cluster members as a sequence of member maps
   - :unreachable - Unreachable members as a sequence of member maps
   - :leader - Current leader address (or nil)
   - :seen-by - Set of addresses that have seen this state"
  [system]
  (let [state (.state (cluster system))]
    {:members (map member->map (seq (.getMembers state)))
     :unreachable (map member->map (seq (.getUnreachable state)))
     :leader (.getLeader state)
     :seen-by (set (seq (.getSeenBy state)))}))

;; ---------------------------------------------------------------------------
;; Cluster Event Subscription
;; ---------------------------------------------------------------------------

(defn- event->map
  "Convert a cluster event to a Clojure map."
  [event]
  (cond
    (instance? ClusterEvent$MemberUp event)
    {:type :member-up :member (member->map (.member ^ClusterEvent$MemberUp event))}

    (instance? ClusterEvent$MemberJoined event)
    {:type :member-joined :member (member->map (.member ^ClusterEvent$MemberJoined event))}

    (instance? ClusterEvent$MemberLeft event)
    {:type :member-left :member (member->map (.member ^ClusterEvent$MemberLeft event))}

    (instance? ClusterEvent$MemberExited event)
    {:type :member-exited :member (member->map (.member ^ClusterEvent$MemberExited event))}

    (instance? ClusterEvent$MemberRemoved event)
    {:type :member-removed
     :member (member->map (.member ^ClusterEvent$MemberRemoved event))
     :previous-status (keyword (str (.previousStatus ^ClusterEvent$MemberRemoved event)))}

    (instance? ClusterEvent$MemberDowned event)
    {:type :member-downed :member (member->map (.member ^ClusterEvent$MemberDowned event))}

    (instance? ClusterEvent$MemberWeaklyUp event)
    {:type :member-weakly-up :member (member->map (.member ^ClusterEvent$MemberWeaklyUp event))}

    (instance? ClusterEvent$MemberPreparingForShutdown event)
    {:type :member-preparing-for-shutdown :member (member->map (.member ^ClusterEvent$MemberPreparingForShutdown event))}

    (instance? ClusterEvent$UnreachableMember event)
    {:type :unreachable :member (member->map (.member ^ClusterEvent$UnreachableMember event))}

    (instance? ClusterEvent$ReachableMember event)
    {:type :reachable :member (member->map (.member ^ClusterEvent$ReachableMember event))}

    (instance? ClusterEvent$LeaderChanged event)
    {:type :leader-changed
     :leader (.getLeader ^ClusterEvent$LeaderChanged event)}

    (instance? ClusterEvent$RoleLeaderChanged event)
    {:type :role-leader-changed
     :role (.role ^ClusterEvent$RoleLeaderChanged event)
     :leader (.getLeader ^ClusterEvent$RoleLeaderChanged event)}

    ;; ClusterShuttingDown is a Scala object, check by class name
    (= "ClusterShuttingDown" (.getSimpleName (class event)))
    {:type :cluster-shutting-down}

    :else
    {:type :unknown :event event}))

(core/defactor cluster-event-subscriber
  "Internal actor for receiving cluster events"
  (init [args]
    {:handler (:handler args)})
  (handle msg
    (when-let [handler (:handler state)]
      (handler (event->map msg)))
    state))

(defn subscribe
  "Subscribe to cluster events.

   handler is a function that receives event maps with keys:
   - :type - Event type keyword (:member-up, :member-removed, etc.)
   - :member - Member map (for member events)
   - :leader - Leader address (for leader events)

   Returns the subscriber ActorRef (can be used to unsubscribe).

   Event types:
   - :member-joined, :member-up, :member-weakly-up
   - :member-left, :member-exited, :member-removed, :member-downed
   - :unreachable, :reachable
   - :leader-changed, :role-leader-changed
   - :cluster-shutting-down"
  [system handler]
  (let [subscriber (core/spawn system cluster-event-subscriber {:handler handler})
        event-classes (into-array Class [ClusterEvent$ClusterDomainEvent])]
    (.subscribe (cluster system) subscriber event-classes)
    subscriber))

(defn unsubscribe
  "Unsubscribe an actor from cluster events."
  [system subscriber]
  (.unsubscribe (cluster system) subscriber))

;; ---------------------------------------------------------------------------
;; Cluster Utilities
;; ---------------------------------------------------------------------------

(defn register-on-member-up
  "Register a callback to run when this node becomes Up in the cluster."
  [system callback]
  (.registerOnMemberUp (cluster system) callback))

(defn register-on-member-removed
  "Register a callback to run when this node is removed from the cluster."
  [system callback]
  (.registerOnMemberRemoved (cluster system) callback))

(defn prepare-for-shutdown
  "Prepare the cluster for a full coordinated shutdown.

   All nodes in the cluster will be marked as PreparingForShutdown.
   This enables graceful shutdown where all nodes coordinate their exit."
  [system]
  (.prepareForFullClusterShutdown (cluster system)))

;; ---------------------------------------------------------------------------
;; Coordinated Shutdown
;; ---------------------------------------------------------------------------

(defn coordinated-shutdown
  "The CoordinatedShutdown extension for `system`. Coordinated shutdown runs
   registered tasks phase by phase when the ActorSystem terminates (or when
   `run-coordinated-shutdown` is called), so resources drain in a safe order.
   Works on any ActorSystem, not only cluster systems."
  ^CoordinatedShutdown [^ActorSystem system]
  (CoordinatedShutdown/get system))

(def shutdown-phases
  "Map of keyword → Pekko CoordinatedShutdown phase name, in execution order.
   Pass a keyword (or a raw phase string) to `add-shutdown-task`."
  {:before-service-unbind             (CoordinatedShutdown/PhaseBeforeServiceUnbind)
   :service-unbind                    (CoordinatedShutdown/PhaseServiceUnbind)
   :service-requests-done             (CoordinatedShutdown/PhaseServiceRequestsDone)
   :service-stop                      (CoordinatedShutdown/PhaseServiceStop)
   :before-cluster-shutdown           (CoordinatedShutdown/PhaseBeforeClusterShutdown)
   :cluster-sharding-shutdown-region  (CoordinatedShutdown/PhaseClusterShardingShutdownRegion)
   :cluster-leave                     (CoordinatedShutdown/PhaseClusterLeave)
   :cluster-exiting                   (CoordinatedShutdown/PhaseClusterExiting)
   :cluster-exiting-done              (CoordinatedShutdown/PhaseClusterExitingDone)
   :cluster-shutdown                  (CoordinatedShutdown/PhaseClusterShutdown)
   :before-actor-system-terminate     (CoordinatedShutdown/PhaseBeforeActorSystemTerminate)
   :actor-system-terminate            (CoordinatedShutdown/PhaseActorSystemTerminate)})

(def shutdown-reasons
  "Map of keyword → CoordinatedShutdown.Reason. Pass a keyword (or a Reason, or
   nil for :unknown) to `run-coordinated-shutdown`."
  {:unknown                             (CoordinatedShutdown/unknownReason)
   :actor-system-terminate              (CoordinatedShutdown/actorSystemTerminateReason)
   :cluster-downing                     (CoordinatedShutdown/clusterDowningReason)
   :cluster-leaving                     (CoordinatedShutdown/clusterLeavingReason)
   :cluster-join-unsuccessful           (CoordinatedShutdown/clusterJoinUnsuccessfulReason)
   :jvm-exit                            (CoordinatedShutdown/jvmExitReason)
   :incompatible-configuration-detected (CoordinatedShutdown/incompatibleConfigurationDetectedReason)})

(defn- ->phase ^String [phase]
  (cond
    (string? phase)  phase
    (keyword? phase) (or (shutdown-phases phase)
                         (throw (IllegalArgumentException.
                                 (str "Unknown shutdown phase " phase " (known: "
                                      (keys shutdown-phases) ")"))))
    :else (throw (IllegalArgumentException.
                  (str "Phase must be a keyword or string, got " (class phase))))))

(defn- ->reason ^CoordinatedShutdown$Reason [reason]
  (cond
    (nil? reason)                              (CoordinatedShutdown/unknownReason)
    (instance? CoordinatedShutdown$Reason reason) reason
    (keyword? reason) (or (shutdown-reasons reason)
                          (throw (IllegalArgumentException.
                                  (str "Unknown shutdown reason " reason " (known: "
                                       (keys shutdown-reasons) ")"))))
    :else (throw (IllegalArgumentException.
                  (str "Reason must be a keyword, CoordinatedShutdown.Reason or nil, got "
                       (class reason))))))

(defn- done-supplier
  "Adapt a 0-arg `task-fn` to a Supplier<CompletionStage<Done>>. If the fn returns
   a CompletionStage the phase awaits it; otherwise the task completes immediately."
  ^Supplier [task-fn]
  (reify Supplier
    (get [_]
      (let [r (task-fn)]
        (if (instance? CompletionStage r)
          r
          (CompletableFuture/completedFuture (Done/done)))))))

(defn add-shutdown-task
  "Register a task to run during coordinated shutdown.

   - phase: a keyword from `shutdown-phases` (e.g. :before-actor-system-terminate)
     or a raw phase name string.
   - task-name: a unique name for the task within the phase (string/keyword).
   - task-fn: a 0-arg fn. If it returns a CompletionStage the phase waits for it to
     complete; any other return value completes the task immediately.

   Tasks in the same phase run in parallel; phases run in order. Returns nil."
  [system phase task-name task-fn]
  (.addTask (coordinated-shutdown system) (->phase phase) (str (name task-name))
            (done-supplier task-fn))
  nil)

(defn add-cancellable-shutdown-task
  "Like `add-shutdown-task`, but returns a Cancellable so the task can be removed
   (via `.cancel`) before shutdown runs."
  [system phase task-name task-fn]
  (.addCancellableTask (coordinated-shutdown system) (->phase phase) (str (name task-name))
                       (done-supplier task-fn)))

(defn add-jvm-shutdown-hook
  "Run `hook-fn` (0-arg) from a JVM shutdown hook coordinated with Pekko's own
   shutdown hooks. Returns nil."
  [system hook-fn]
  (.addJvmShutdownHook (coordinated-shutdown system)
                       ^Runnable (reify Runnable (run [_] (hook-fn))))
  nil)

(defn run-coordinated-shutdown
  "Trigger coordinated shutdown: run all registered tasks phase by phase (the final
   phases also terminate the ActorSystem). `reason` is a keyword from
   `shutdown-reasons`, a CoordinatedShutdown.Reason, or nil (:unknown). Idempotent —
   calling it again returns the same completion. Returns a CompletableFuture that
   completes with Done when shutdown finishes."
  (^CompletableFuture [system] (run-coordinated-shutdown system nil))
  (^CompletableFuture [system reason]
   (.toCompletableFuture
    ^CompletionStage (.run (coordinated-shutdown system) (->reason reason) (Optional/empty)))))
