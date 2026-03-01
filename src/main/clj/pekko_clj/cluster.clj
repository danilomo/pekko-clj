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

     ;; Get cluster state
     (cluster/members sys)
     (cluster/leader sys)
     (cluster/self-member sys)"
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.cluster Cluster Member MemberStatus ClusterEvent$ClusterDomainEvent
                                     ClusterEvent$MemberUp ClusterEvent$MemberRemoved
                                     ClusterEvent$MemberExited ClusterEvent$MemberDowned
                                     ClusterEvent$MemberWeaklyUp ClusterEvent$MemberLeft
                                     ClusterEvent$MemberJoined ClusterEvent$MemberPreparingForShutdown
                                     ClusterEvent$UnreachableMember ClusterEvent$ReachableMember
                                     ClusterEvent$LeaderChanged ClusterEvent$RoleLeaderChanged]
           [com.typesafe.config Config ConfigFactory]
           [java.util Set]))

;; ---------------------------------------------------------------------------
;; Cluster Access
;; ---------------------------------------------------------------------------

(defn cluster
  "Get the Cluster extension for an ActorSystem."
  [^ActorSystem system]
  (Cluster/get system))

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

   Example:
     (create-system \"my-app\" {:hostname \"192.168.1.10\"
                                :port 7355
                                :seed-nodes [\"pekko://my-app@192.168.1.10:7355\"
                                             \"pekko://my-app@192.168.1.11:7355\"]
                                :roles [\"backend\"]})"
  [name config]
  (let [cfg (if (instance? Config config)
              config
              (let [{:keys [hostname port seed-nodes roles]
                     :or {hostname "127.0.0.1" port 7355}} config
                    seed-nodes-str (if seed-nodes
                                     (str "["
                                          (clojure.string/join ", "
                                            (map #(str "\"" % "\"") seed-nodes))
                                          "]")
                                     "[]")
                    roles-str (if roles
                                (str "["
                                     (clojure.string/join ", "
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
                      }")]
                (ConfigFactory/parseString config-str)))]
    (ActorSystem/create name (.withFallback cfg (ConfigFactory/load)))))

;; ---------------------------------------------------------------------------
;; Cluster Membership
;; ---------------------------------------------------------------------------

(defn join
  "Join the cluster by contacting seed nodes or a specific address.

   If address is provided, joins that specific node.
   If no address is provided, joins using configured seed nodes."
  ([system]
   (.join (cluster system)))
  ([system address]
   (.join (cluster system) (org.apache.pekko.actor.AddressFromURIString/parse address))))

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
   :status (keyword (clojure.string/lower-case (str (.status m))))
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
  (let [state (.state (cluster system))
        leader-opt (.getLeader state)]
    (when (.isPresent leader-opt)
      (.get leader-opt))))

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
  (let [state (.state (cluster system))
        leader-opt (.roleLeader state role)]
    (when (.isPresent leader-opt)
      (.get leader-opt))))

(defn has-role?
  "Check if this node has a specific role."
  [system role]
  (.hasRole (.selfMember (cluster system)) role))

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

    (instance? ClusterEvent$UnreachableMember event)
    {:type :unreachable :member (member->map (.member ^ClusterEvent$UnreachableMember event))}

    (instance? ClusterEvent$ReachableMember event)
    {:type :reachable :member (member->map (.member ^ClusterEvent$ReachableMember event))}

    (instance? ClusterEvent$LeaderChanged event)
    {:type :leader-changed
     :leader (let [opt (.getLeader ^ClusterEvent$LeaderChanged event)]
               (when (.isPresent opt) (.get opt)))}

    (instance? ClusterEvent$RoleLeaderChanged event)
    {:type :role-leader-changed
     :role (.role ^ClusterEvent$RoleLeaderChanged event)
     :leader (let [opt (.getLeader ^ClusterEvent$RoleLeaderChanged event)]
               (when (.isPresent opt) (.get opt)))}

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
