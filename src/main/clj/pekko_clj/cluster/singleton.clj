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
     (core/! leader [:assign-task {:id 1 :name \"Process data\"}])

     ;; Start with supervision for resilience
     (singleton/start sys leader-actor
       {:name \"task-leader\"
        :supervision {:strategy :restart-with-backoff
                      :min-backoff-ms 1000
                      :max-backoff-ms 30000}})"
  (:refer-clojure :exclude [proxy])
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [org.apache.pekko.cluster.singleton ClusterSingletonManager ClusterSingletonManagerSettings
                                               ClusterSingletonProxy ClusterSingletonProxySettings]
           [org.apache.pekko.pattern BackoffSupervisor BackoffOpts]
           [pekko_clj.actor CljActor]
           [java.time Duration]
           [scala.concurrent.duration FiniteDuration]
           [java.util.concurrent TimeUnit]))

;; ---------------------------------------------------------------------------
;; Supervision Support
;; ---------------------------------------------------------------------------

(defn- wrap-with-supervision
  "Wrap actor Props with backoff supervision if configured."
  [props supervision]
  (if supervision
    (let [{:keys [strategy min-backoff-ms max-backoff-ms random-factor]
           :or {min-backoff-ms 3000
                max-backoff-ms 30000
                random-factor 0.2}} supervision]
      (case strategy
        :restart-with-backoff
        (let [backoff-opts (BackoffOpts/onFailure
                             props
                             "singleton"
                             (Duration/ofMillis min-backoff-ms)
                             (Duration/ofMillis max-backoff-ms)
                             random-factor)]
          (BackoffSupervisor/props backoff-opts))

        :restart-with-stop
        (let [backoff-opts (BackoffOpts/onStop
                             props
                             "singleton"
                             (Duration/ofMillis min-backoff-ms)
                             (Duration/ofMillis max-backoff-ms)
                             random-factor)]
          (BackoffSupervisor/props backoff-opts))

        ;; Default: no supervision wrapper
        props))
    props))

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
     - :supervision - Supervision options map
       - :strategy - :restart-with-backoff or :restart-with-stop
       - :min-backoff-ms - Min backoff delay (default: 3000)
       - :max-backoff-ms - Max backoff delay (default: 30000)
       - :random-factor - Backoff randomization 0.0-1.0 (default: 0.2)

   Returns the singleton manager ActorRef.

   Example:
     (singleton/start sys leader-actor
       {:name \"cluster-leader\"
        :role \"backend\"
        :args {:config config}
        :supervision {:strategy :restart-with-backoff
                      :min-backoff-ms 1000
                      :max-backoff-ms 30000}})"
  [^ActorSystem system actor-def opts]
  (let [{:keys [name role args termination-message hand-over-retry-interval supervision]
         :or {termination-message :stop}} opts
        base-props (CljActor/create ((:make-props actor-def) args))
        props (wrap-with-supervision base-props supervision)
        settings (cond-> (ClusterSingletonManagerSettings/create system)
                   role (.withRole role)
                   hand-over-retry-interval
                   (.withHandOverRetryInterval
                     (FiniteDuration/apply hand-over-retry-interval TimeUnit/MILLISECONDS)))
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
     - :identification-interval-ms - How often to identify singleton (default: 1000)

   Returns a proxy ActorRef that forwards messages to the singleton.

   Example:
     (def leader-proxy (singleton/proxy sys
                         {:singleton-manager-path \"/user/cluster-leader\"
                          :role \"backend\"
                          :buffer-size 2000}))"
  [^ActorSystem system opts]
  (let [{:keys [singleton-manager-path role buffer-size identification-interval-ms]
         :or {buffer-size 1000}} opts
        ;; Singleton actor name is always "singleton" within the manager
        singleton-path (str singleton-manager-path "/singleton")
        settings (cond-> (ClusterSingletonProxySettings/create system)
                   role (.withRole role)
                   buffer-size (.withBufferSize (int buffer-size))
                   identification-interval-ms
                   (.withSingletonIdentificationInterval
                     (FiniteDuration/apply identification-interval-ms TimeUnit/MILLISECONDS)))
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
                                 :role (:role opts)
                                 :buffer-size (:buffer-size opts)
                                 :identification-interval-ms (:identification-interval-ms opts)})]
    {:manager manager
     :proxy proxy-ref}))

;; ---------------------------------------------------------------------------
;; Singleton State Query
;; ---------------------------------------------------------------------------

(defn singleton-running-here?
  "Check if the singleton is currently running on this node.

   Arguments:
   - system: ActorSystem
   - manager-path: Path to the singleton manager (e.g., \"/user/cluster-leader\")

   Returns true if this node is hosting the singleton.

   Example:
     (when (singleton-running-here? sys \"/user/cluster-leader\")
       (println \"I am the leader!\"))"
  [^ActorSystem system manager-path]
  (try
    (let [singleton-path (str manager-path "/singleton")
          selection (.actorSelection system singleton-path)
          ;; Use a short timeout to check if actor exists locally
          future (.resolveOne selection (Duration/ofMillis 100))]
      ;; If we can resolve it quickly, check if it's a local actor
      (try
        (let [ref @future
              local-addr (.address (.provider (.dispatcher system)))
              actor-addr (.address (.path ref))]
          ;; Compare addresses - local actors have the same address
          (or (nil? (.host actor-addr))
              (= local-addr actor-addr)))
        (catch Exception _
          false)))
    (catch Exception _
      false)))
