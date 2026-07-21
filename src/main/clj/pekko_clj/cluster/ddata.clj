(ns pekko-clj.cluster.ddata
  "Distributed Data (CRDTs) for pekko-clj.

   A deliberately small wrapper over Pekko's `DistributedData` replicator: three
   conflict-free replicated data types — an observed-remove **set** (`ORSet`), a
   last-writer-wins **map** (`LWWMap`) and an increment/decrement **counter**
   (`PNCounter`) — and the four commands that drive them: update, get, subscribe
   and delete. Values are replicated to every node and merged without coordination,
   so writes never block on other nodes.

   Every command returns a `CompletableFuture` completing with a Clojure result map
   whose `:status` is `:success`, `:not-found`, `:timeout`, `:deleted` or
   `:failure` — no Pekko response classes to match on. Values come back as Clojure
   data (`crdt->clj`): a set, a map, or a number.

   Example:
     (def online (ddata/or-set-key \"online-users\"))

     (ddata/add! sys online \"ada\")
     (ddata/add! sys online \"grace\")
     @(ddata/get-data sys online)      ;; => {:status :success :key \"online-users\"
                                       ;;     :value #{\"ada\" \"grace\"} ...}

     ;; React to changes from any node
     (ddata/subscribe sys online (fn [{:keys [value]}] (println \"online:\" value)))

     ;; Counters and maps work the same way
     (ddata/increment! sys (ddata/pn-counter-key \"hits\") 1)
     (ddata/put! sys (ddata/lww-map-key \"config\") \"level\" \"debug\")

   Consistency: writes and reads default to `:local` (fast, eventually consistent).
   Pass `:consistency :majority` / `:all` (with an optional `:timeout-ms`) where a
   command must reach other nodes before it is acknowledged.

   Requires a cluster ActorSystem. Replicated values travel between nodes, so their
   elements must be serializable — enable the Transit serializer
   (`pekko-clj.serialization`, `create-system`'s `:transit-serialization`) to
   replicate Clojure data."
  (:refer-clojure :exclude [get key remove])
  (:require [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorRef ActorSystem]
           [org.apache.pekko.cluster.ddata DistributedData SelfUniqueAddress Key
                                           ORSet ORSetKey LWWMap LWWMapKey
                                           PNCounter PNCounterKey
                                           Replicator
                                           Replicator$Update
                                           Replicator$UpdateSuccess
                                           Replicator$UpdateTimeout
                                           Replicator$ModifyFailure
                                           Replicator$UpdateDataDeleted
                                           Replicator$Get
                                           Replicator$GetSuccess
                                           Replicator$NotFound
                                           Replicator$GetFailure
                                           Replicator$GetDataDeleted
                                           Replicator$Subscribe
                                           Replicator$Unsubscribe
                                           Replicator$Changed
                                           Replicator$Deleted
                                           Replicator$Delete
                                           Replicator$DeleteSuccess
                                           Replicator$ReplicationDeleteFailure
                                           Replicator$DataDeleted
                                           Replicator$WriteConsistency
                                           Replicator$WriteAll
                                           Replicator$WriteMajority
                                           Replicator$ReadConsistency
                                           Replicator$ReadAll
                                           Replicator$ReadMajority]
           [pekko_clj.actor FnWrapper]
           [java.time Duration]
           [java.util.concurrent CompletableFuture]))

(set! *warn-on-reflection* true)

(def ^:private ^scala.Option none (scala.None$/MODULE$))

(def ^:private default-consistency-timeout-ms 5000)

;; ---------------------------------------------------------------------------
;; Extension access
;; ---------------------------------------------------------------------------

(defn distributed-data
  "The DistributedData extension for `system` (starts the replicator on first use)."
  ^DistributedData [^ActorSystem system]
  (DistributedData/get system))

(defn replicator
  "The replicator ActorRef for `system`. Accepts an ActorSystem or an already
   resolved replicator ActorRef (cache it inside an actor)."
  ^ActorRef [system-or-replicator]
  (if (instance? ActorSystem system-or-replicator)
    (.replicator (distributed-data system-or-replicator))
    system-or-replicator))

(defn self-address
  "This node's SelfUniqueAddress — the 'who is writing' token every CRDT mutation
   needs. The `add!`/`put!`/`increment!` helpers pass it for you."
  ^SelfUniqueAddress [^ActorSystem system]
  (.selfUniqueAddress (distributed-data system)))

;; ---------------------------------------------------------------------------
;; Keys and empty values
;; ---------------------------------------------------------------------------

(defn or-set-key
  "Key for an observed-remove set (`ORSet`) with the given id."
  ^Key [^String id]
  (ORSetKey/create id))

(defn lww-map-key
  "Key for a last-writer-wins map (`LWWMap`) with the given id."
  ^Key [^String id]
  (LWWMapKey/create id))

(defn pn-counter-key
  "Key for an increment/decrement counter (`PNCounter`) with the given id."
  ^Key [^String id]
  (PNCounterKey/create id))

(defn key-id
  "The id string of a ddata Key."
  ^String [^Key k]
  (.id k))

(defn empty-or-set [] (ORSet/create))
(defn empty-lww-map [] (LWWMap/create))
(defn empty-pn-counter [] (PNCounter/create))

;; ---------------------------------------------------------------------------
;; Consistency levels
;; ---------------------------------------------------------------------------

(defn- ->duration
  ^Duration [ms]
  (if (instance? Duration ms) ms (Duration/ofMillis (long ms))))

(defn write-consistency
  "A Pekko WriteConsistency:
   - :local    - written to the local replica only (default; gossiped after)
   - :majority - acknowledged by a majority of nodes within timeout-ms
   - :all      - acknowledged by all nodes within timeout-ms"
  (^Replicator$WriteConsistency [level] (write-consistency level default-consistency-timeout-ms))
  (^Replicator$WriteConsistency [level timeout-ms]
   (case level
     (:local nil) (Replicator/writeLocal)
     :majority (Replicator$WriteMajority. (->duration timeout-ms))
     :all (Replicator$WriteAll. (->duration timeout-ms))
     (throw (IllegalArgumentException.
             (str "Unknown write consistency: " level " (expected :local, :majority or :all)"))))))

(defn read-consistency
  "A Pekko ReadConsistency: :local (default), :majority or :all."
  (^Replicator$ReadConsistency [level] (read-consistency level default-consistency-timeout-ms))
  (^Replicator$ReadConsistency [level timeout-ms]
   (case level
     (:local nil) (Replicator/readLocal)
     :majority (Replicator$ReadMajority. (->duration timeout-ms))
     :all (Replicator$ReadAll. (->duration timeout-ms))
     (throw (IllegalArgumentException.
             (str "Unknown read consistency: " level " (expected :local, :majority or :all)"))))))

;; ---------------------------------------------------------------------------
;; CRDT → Clojure data
;; ---------------------------------------------------------------------------

(defn crdt->clj
  "Convert a replicated value to Clojure data: ORSet → set, LWWMap → map,
   PNCounter → number (long when it fits, else bigint). Other ReplicatedData is
   returned unchanged."
  [data]
  (condp instance? data
    ORSet (into #{} (.getElements ^ORSet data))
    LWWMap (into {} (.getEntries ^LWWMap data))
    PNCounter (let [v (.getValue ^PNCounter data)]
                (if (< (.bitLength v) 63) (.longValue v) (bigint v)))
    data))

;; ---------------------------------------------------------------------------
;; Response → Clojure data
;; ---------------------------------------------------------------------------

(defn- update-response->map [resp]
  (condp instance? resp
    Replicator$UpdateSuccess {:status :success :key (key-id (.key ^Replicator$UpdateSuccess resp))}
    Replicator$UpdateTimeout {:status :timeout :key (key-id (.key ^Replicator$UpdateTimeout resp))}
    Replicator$ModifyFailure {:status :failure
                              :key (key-id (.key ^Replicator$ModifyFailure resp))
                              :error (.errorMessage ^Replicator$ModifyFailure resp)
                              :cause (.cause ^Replicator$ModifyFailure resp)}
    Replicator$UpdateDataDeleted {:status :deleted
                                  :key (key-id (.key ^Replicator$UpdateDataDeleted resp))}
    {:status :failure :response resp}))

(defn- get-response->map [resp]
  (condp instance? resp
    Replicator$GetSuccess (let [^Replicator$GetSuccess r resp
                                data (.dataValue r)]
                            {:status :success
                             :key (key-id (.key r))
                             :value (crdt->clj data)
                             :data data})
    Replicator$NotFound {:status :not-found :key (key-id (.key ^Replicator$NotFound resp))}
    Replicator$GetDataDeleted {:status :deleted :key (key-id (.key ^Replicator$GetDataDeleted resp))}
    Replicator$GetFailure {:status :failure :key (key-id (.key ^Replicator$GetFailure resp))}
    {:status :failure :response resp}))

(defn- delete-response->map [resp]
  (condp instance? resp
    Replicator$DeleteSuccess {:status :success :key (key-id (.key ^Replicator$DeleteSuccess resp))}
    Replicator$DataDeleted {:status :deleted :key (key-id (.key ^Replicator$DataDeleted resp))}
    Replicator$ReplicationDeleteFailure
    {:status :failure :key (key-id (.key ^Replicator$ReplicationDeleteFailure resp))}
    {:status :failure :response resp}))

(defn- ask-replicator
  "Ask the replicator and map its reply with `response->map`."
  [system msg response->map timeout-ms]
  (.thenApply ^CompletableFuture (core/<?> (replicator system) msg (or timeout-ms core/*timeout*))
              (reify java.util.function.Function
                (apply [_ resp] (response->map resp)))))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(defn update!
  "Update the value at `key` by applying `f` to it, merging the result into the
   cluster. `f` receives the current value (or `:initial` when there is none) and
   must return the new CRDT — mutate it with the CRDT's own methods, which need
   `self-address`.

   Prefer `add!`/`remove!`/`put!`/`remove-key!`/`increment!`/`decrement!`, which
   fill in the empty value and the node address for you; use `update!` for
   anything they don't cover.

   Options:
   - :initial     - value used when the key is unset (required unless `f` handles nil)
   - :consistency - :local (default), :majority or :all
   - :timeout-ms  - consistency timeout, and the ask timeout for the reply

   Returns: CompletableFuture of {:status :success/:timeout/:failure/:deleted :key id}

   Example:
     (update! sys (or-set-key \"users\") #(.add % (self-address sys) \"ada\")
              {:initial (empty-or-set)})"
  ([system key f] (update! system key f {}))
  ([system ^Key key f {:keys [initial consistency timeout-ms]}]
   (let [^scala.Function1 modify (FnWrapper/create
                 (fn [opt]
                   (f (if (.isDefined ^scala.Option opt) (.get ^scala.Option opt) initial))))]
     (ask-replicator system
                     (Replicator$Update. key
                                         (write-consistency consistency
                                                            (or timeout-ms default-consistency-timeout-ms))
                                         none
                                         modify)
                     update-response->map
                     timeout-ms))))

(defn get-data
  "Read the value at `key`.

   Options: :consistency (:local default, :majority, :all), :timeout-ms

   Returns: CompletableFuture of {:status :success :key id :value <clojure data>
   :data <raw CRDT>}, or {:status :not-found/:deleted/:failure :key id}."
  ([system key] (get-data system key {}))
  ([system ^Key key {:keys [consistency timeout-ms]}]
   (ask-replicator system
                   (Replicator$Get. key
                                    (read-consistency consistency
                                                      (or timeout-ms default-consistency-timeout-ms))
                                    none)
                   get-response->map
                   timeout-ms)))

(defn delete!
  "Delete the value at `key` cluster-wide. A deleted key can never be used again —
   later updates and reads answer {:status :deleted}.

   Options: :consistency (:local default, :majority, :all), :timeout-ms

   Returns: CompletableFuture of {:status :success/:deleted/:failure :key id}."
  ([system key] (delete! system key {}))
  ([system ^Key key {:keys [consistency timeout-ms]}]
   (ask-replicator system
                   (Replicator$Delete. key
                                       (write-consistency consistency
                                                          (or timeout-ms default-consistency-timeout-ms))
                                       none)
                   delete-response->map
                   timeout-ms)))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(defn change->map
  "Convert a replicator Changed/Deleted notification to a map
   {:key id :value <clojure data> :data <raw CRDT> :deleted? bool}, or nil for any
   other message."
  [msg]
  (condp instance? msg
    Replicator$Changed (let [^Replicator$Changed c msg
                             data (.dataValue c)]
                         {:key (key-id (.key c))
                          :value (crdt->clj data)
                          :data data
                          :deleted? false})
    Replicator$Deleted {:key (key-id (.key ^Replicator$Deleted msg))
                        :value nil
                        :data nil
                        :deleted? true}
    nil))

(core/defactor change-subscriber
  "Internal actor: subscribes itself to a key on start and calls a handler fn for
   each change notification."
  (init [args]
    (core/! (:replicator args) (Replicator$Subscribe. (:key args) (core/self)))
    args)
  (handle msg
    (when-let [change (change->map msg)]
      ((:handler state) change))
    state))

(defn subscribe
  "Subscribe to changes of `key`. The final argument is either an ActorRef (which
   receives raw Changed/Deleted messages — convert them with `change->map`) or a
   function called with {:key :value :data :deleted?} for each change.

   Returns the subscriber ActorRef; pass it to `unsubscribe` (and stop it when it
   is one this function spawned)."
  [^ActorSystem system ^Key key subscriber-or-fn]
  (if (instance? ActorRef subscriber-or-fn)
    (do (core/! (replicator system) (Replicator$Subscribe. key ^ActorRef subscriber-or-fn))
        subscriber-or-fn)
    (core/spawn system change-subscriber {:replicator (replicator system)
                                          :key key
                                          :handler subscriber-or-fn})))

(defn unsubscribe
  "Stop sending change notifications for `key` to `subscriber`."
  [system ^Key key ^ActorRef subscriber]
  (core/! (replicator system) (Replicator$Unsubscribe. key subscriber))
  nil)

;; ---------------------------------------------------------------------------
;; Opinionated per-type operations
;; ---------------------------------------------------------------------------

(defn add!
  "Add `element` to the ORSet at `key`. Options as for `update!`."
  ([system key element] (add! system key element {}))
  ([^ActorSystem system key element opts]
   (let [node (self-address system)]
     (update! system key
              (fn [^ORSet s] (.add s node element))
              (assoc opts :initial (empty-or-set))))))

(defn remove!
  "Remove `element` from the ORSet at `key`. Options as for `update!`."
  ([system key element] (remove! system key element {}))
  ([^ActorSystem system key element opts]
   (let [node (self-address system)]
     (update! system key
              (fn [^ORSet s] (.remove s node element))
              (assoc opts :initial (empty-or-set))))))

(defn put!
  "Put `v` under `k` in the LWWMap at `key` (last writer wins). Options as for
   `update!`."
  ([system key k v] (put! system key k v {}))
  ([^ActorSystem system key k v opts]
   (let [node (self-address system)]
     (update! system key
              (fn [^LWWMap m] (.put m node k v))
              (assoc opts :initial (empty-lww-map))))))

(defn remove-key!
  "Remove `k` from the LWWMap at `key`. Options as for `update!`."
  ([system key k] (remove-key! system key k {}))
  ([^ActorSystem system key k opts]
   (let [node (self-address system)]
     (update! system key
              (fn [^LWWMap m] (.remove m node k))
              (assoc opts :initial (empty-lww-map))))))

(defn increment!
  "Increment the PNCounter at `key` by `n` (default 1). Options as for `update!`."
  ([system key] (increment! system key 1 {}))
  ([system key n] (increment! system key n {}))
  ([^ActorSystem system key n opts]
   (let [node (self-address system)]
     (update! system key
              (fn [^PNCounter c] (.increment c node (long n)))
              (assoc opts :initial (empty-pn-counter))))))

(defn decrement!
  "Decrement the PNCounter at `key` by `n` (default 1). Options as for `update!`."
  ([system key] (decrement! system key 1 {}))
  ([system key n] (decrement! system key n {}))
  ([^ActorSystem system key n opts]
   (let [node (self-address system)]
     (update! system key
              (fn [^PNCounter c] (.decrement c node (long n)))
              (assoc opts :initial (empty-pn-counter))))))

(defn value
  "Blocking convenience: the current Clojure value at `key`, or nil when the key is
   unset, deleted, or the read did not complete in time — use `get-data` when you
   need to tell those apart. Do not call from inside an actor handler (it blocks)."
  ([system key] (value system key {}))
  ([system key opts]
   (let [result (deref (get-data system key opts)
                       (or (:timeout-ms opts) core/*timeout*)
                       nil)]
     (when (= :success (:status result))
       (:value result)))))

(set! *warn-on-reflection* false)
