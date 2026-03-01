(ns pekko-clj.cluster.singleton
  "Cluster Singleton support for pekko-clj.

   A cluster singleton ensures that at most one instance of an actor
   is running in the cluster at any time. If the node hosting the singleton
   fails, the singleton is started on another node.

   Example:
     ;; Define a singleton actor
     (core/defactor leader-actor
       (init [_] {:tasks []})
       (handle [:assign-task task]
         (update state :tasks conj task)))

     ;; Start the singleton
     (def leader (singleton/start sys leader-actor
                   {:name \"task-leader\"
                    :role \"backend\"}))

     ;; Send messages to the singleton (from any node)
     (core/! leader [:assign-task {:id 1 :name \"Process data\"}])"
  (:refer-clojure :exclude [proxy])
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.cluster.singleton ClusterSingletonManager ClusterSingletonManagerSettings
                                               ClusterSingletonProxy ClusterSingletonProxySettings]
           [pekko_clj.actor CljActor]))

;; ---------------------------------------------------------------------------
;; Singleton Manager
;; ---------------------------------------------------------------------------

(defn start
  "Start a cluster singleton.

   The singleton will run on exactly one node in the cluster (or within a role).
   If that node fails, the singleton is restarted on another eligible node.

   Arguments:
   - system: ActorSystem
   - actor-def: Actor definition from defactor
   - opts: Options map
     - :name - Name for the singleton manager (required)
     - :role - Role constraint (only nodes with this role can host)
     - :args - Arguments passed to actor's init
     - :termination-message - Message sent to stop gracefully (default: :stop)
     - :hand-over-retry-interval - Retry interval during hand-over (ms)

   Returns the singleton manager ActorRef.

   Example:
     (singleton/start sys leader-actor
       {:name \"cluster-leader\"
        :role \"backend\"
        :args {:config config}})"
  [^ActorSystem system actor-def opts]
  (let [{:keys [name role args termination-message hand-over-retry-interval]
         :or {termination-message :stop}} opts
        props (CljActor/create ((:make-props actor-def) args))
        settings (cond-> (ClusterSingletonManagerSettings/create system)
                   role (.withRole role)
                   hand-over-retry-interval
                   (.withHandOverRetryInterval
                     (java.time.Duration/ofMillis hand-over-retry-interval)))
        manager-props (ClusterSingletonManager/props props termination-message settings)]
    (.actorOf system manager-props name)))

;; ---------------------------------------------------------------------------
;; Singleton Proxy
;; ---------------------------------------------------------------------------

(defn proxy
  "Create a proxy to communicate with a singleton.

   The proxy routes messages to the singleton, wherever it's running in the cluster.
   Use this from nodes that need to send messages to the singleton but don't
   host it themselves.

   Arguments:
   - system: ActorSystem
   - opts: Options map
     - :singleton-manager-path - Path to the singleton manager (required)
     - :role - Role where singleton runs (for faster routing)
     - :buffer-size - Buffer size for messages during hand-over (default: 1000)

   Returns a proxy ActorRef that forwards messages to the singleton.

   Example:
     (def leader-proxy (singleton/proxy sys
                         {:singleton-manager-path \"/user/cluster-leader\"
                          :role \"backend\"}))"
  [^ActorSystem system opts]
  (let [{:keys [singleton-manager-path role buffer-size]
         :or {buffer-size 1000}} opts
        ;; Singleton actor name is always "singleton" within the manager
        singleton-path (str singleton-manager-path "/singleton")
        settings (cond-> (ClusterSingletonProxySettings/create system)
                   role (.withRole role)
                   buffer-size (.withBufferSize (int buffer-size)))
        proxy-props (ClusterSingletonProxy/props singleton-path settings)]
    (.actorOf system proxy-props)))

;; ---------------------------------------------------------------------------
;; Convenience Functions
;; ---------------------------------------------------------------------------

(defn start-with-proxy
  "Start a singleton and return both the manager and a proxy.

   This is a convenience function for the common case where you want
   to both host and communicate with a singleton.

   Returns a map with:
   - :manager - The singleton manager ActorRef
   - :proxy - A proxy ActorRef for sending messages

   Example:
     (let [{:keys [manager proxy]} (singleton/start-with-proxy sys leader-actor
                                     {:name \"cluster-leader\"})]
       (core/! proxy [:do-work]))"
  [system actor-def opts]
  (let [manager (start system actor-def opts)
        proxy-ref (proxy system {:singleton-manager-path (str "/user/" (:name opts))
                                 :role (:role opts)})]
    {:manager manager
     :proxy proxy-ref}))
