(ns pekko-clj.cluster.daemon
  "Sharded Daemon Process: run a fixed number of always-on worker actors, spread
   across the cluster and kept alive (restarted, moved on rebalance) by Pekko.

   Unlike sharded entities, daemon workers are not addressed by id and are not
   created on demand — Pekko starts exactly `n` of them and keeps them running.
   Typical uses: consuming N partitions of an external queue, running periodic
   jobs, projections over an event-sourced journal (see `pekko-clj.persistence.query`).

   `ShardedDaemonProcess` exists only in Pekko's typed API, so each worker runs
   inside a thin typed wrapper (`pekko_clj.actor.CljDaemonProcess`) that spawns the
   classic `defactor` actor as its child, forwards messages to it, and stops when it
   stops. This is the only typed shim in pekko-clj; everything you write stays classic.

   Example:
     (core/defactor partition-worker
       ;; init receives the worker's index: 0, 1, ... n-1
       (init [i] {:partition i})
       (handle [:poll] (consume! (:partition state)) state))

     (daemon/start sys \"partition-workers\" 4 partition-worker)

   Requires a cluster (a single self-joined node is enough)."
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.actor.typed.javadsl Adapter]
           [org.apache.pekko.cluster.sharding.typed ShardedDaemonProcessSettings]
           [org.apache.pekko.cluster.sharding.typed.javadsl ShardedDaemonProcess]
           [pekko_clj.actor CljDaemonProcess]
           [java.time Duration]
           [java.util Optional]
           [java.util.function IntFunction]))

(set! *warn-on-reflection* true)

(defn- ->duration
  ^Duration [d]
  (if (instance? Duration d) d (Duration/ofMillis (long d))))

(defn settings
  "Build ShardedDaemonProcessSettings for `start`.

   Options map:
   - :keep-alive-interval - how often Pekko pings the workers to keep them alive
                            (ms or java.time.Duration; default 10s)
   - :role                - only run workers on nodes with this role

   Returns: ShardedDaemonProcessSettings"
  ^ShardedDaemonProcessSettings [^ActorSystem system opts]
  (let [{:keys [keep-alive-interval role]} opts]
    (cond-> (ShardedDaemonProcessSettings/create (Adapter/toTyped system))
      keep-alive-interval (.withKeepAliveInterval (->duration keep-alive-interval))
      role (.withRole ^String role))))

(defn start
  "Start `n` always-on instances of `actor-def`, distributed across the cluster.

   Each instance is created with its index (0 … n-1) as its init args, so an
   (init [i] …) clause can use it to pick a partition, offset or shard of work.

   Arguments:
   - system: ActorSystem (must be cluster-enabled)
   - name: Unique name for this daemon process
   - n: Number of instances
   - actor-def: Actor definition from defactor
   - opts: Options map (optional)
     - :keep-alive-interval - keep-alive ping interval (ms or java.time.Duration)
     - :role                - only run workers on nodes with this role
     - :stop-message        - message sent to a worker to stop it gracefully when it
                              is rebalanced or the process is shut down. Handle it by
                              stopping self, e.g. (core/stop (core/self)).
     - :settings            - a ready-made ShardedDaemonProcessSettings (overrides
                              :keep-alive-interval / :role)

   Returns nil — daemon workers are not addressed directly. Call it once per node
   (every node runs the same call; Pekko allocates the instances).

   Example:
     (daemon/start sys \"projections\" 4 projection-worker
       {:keep-alive-interval 5000
        :stop-message :stop})"
  ([system name n actor-def] (start system name n actor-def {}))
  ([^ActorSystem system ^String name n actor-def opts]
   (let [{:keys [stop-message]} opts
         daemon (ShardedDaemonProcess/get (Adapter/toTyped system))
         behaviors (reify IntFunction
                     (apply [_ i]
                       (CljDaemonProcess/wrap (core/actor-props actor-def i))))
         cfg (or (:settings opts) (settings system opts))]
     (.init daemon Object name (int n) behaviors cfg
            (Optional/ofNullable stop-message))
     nil)))

(set! *warn-on-reflection* false)
