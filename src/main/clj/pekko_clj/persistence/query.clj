(ns pekko-clj.persistence.query
  "Persistence query (read side) for pekko-clj — the read-model half of CQRS.

   A persistent actor (pekko-clj.persistence) writes events to a journal; this
   namespace reads them back as a live or point-in-time pekko-clj.stream Source
   that composes with every stream operator (`smap`, `run-to-seq`, …).

   Two query flavours:
   - `current-*` — a bounded snapshot of what is in the journal right now; the
     Source completes.
   - live (`events-by-tag`, `events-by-persistence-id`, `persistence-ids`) —
     stays open and emits new events as they are persisted (LevelDB polls every
     `refresh-interval`, default 3s).

   Read-journal lookup:
     (def j (read-journal system))          ; the default LevelDB journal
     (def j (read-journal system id))       ; a specific journal by config id

   Query by tag (events must be tagged — see (tagger ...) in defactor-persistent):
     (-> (events-by-tag j \"cart\")
         (stream/smap :event)
         (stream/run-foreach handle mat))

   Each EventEnvelope is mapped to a Clojure map via `envelope->map`:
     {:offset <clj offset> :persistence-id \"…\" :sequence-nr 3 :event <payload>
      :timestamp 1234567890}

   Offsets are Clojure-friendly: `no-offset`, `(sequence-offset n)`, and the
   `:offset` on a mapped envelope is `{:type :sequence :value n}` for LevelDB.
   Pass either a raw Pekko Offset or one of these to the *-by-tag queries.

   Backend note: LevelDB backs `events-by-tag` / `current-events-by-tag`, but not
   `events-by-slice` (a typed, slice-based API with no LevelDB provider) — use
   tags for fan-in across persistence ids."
  (:require [pekko-clj.stream :as stream])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.persistence.query PersistenceQuery EventEnvelope
            Offset Sequence TimeBasedUUID]
           [org.apache.pekko.persistence.query.javadsl
            EventsByTagQuery CurrentEventsByTagQuery
            EventsByPersistenceIdQuery CurrentEventsByPersistenceIdQuery
            PersistenceIdsQuery CurrentPersistenceIdsQuery]
           [org.apache.pekko.persistence.query.journal.leveldb.javadsl LeveldbReadJournal]))

;; ---------------------------------------------------------------------------
;; Read journal lookup
;; ---------------------------------------------------------------------------

(def leveldb-identifier
  "Config identifier of the LevelDB read journal (the test/default backend)."
  (LeveldbReadJournal/Identifier))

(defn read-journal
  "Return the javadsl read journal for `system`.

   Arguments:
   - system: ActorSystem
   - identifier (optional): read-journal config id; defaults to the LevelDB
     journal (`leveldb-identifier`).

   The returned journal implements whichever query interfaces its backend
   supports; the query fns below check and throw a clear error otherwise.

   Example:
     (read-journal sys)
     (read-journal sys \"pekko.persistence.query.my-journal\")"
  ([^ActorSystem system]
   (read-journal system leveldb-identifier))
  ([^ActorSystem system ^String identifier]
   (.getReadJournalFor (PersistenceQuery/get system)
                       org.apache.pekko.persistence.query.javadsl.ReadJournal
                       identifier)))

;; ---------------------------------------------------------------------------
;; Offsets
;; ---------------------------------------------------------------------------

(def no-offset
  "The 'from the beginning' offset — start a tag query at the first event."
  (Offset/noOffset))

(defn sequence-offset
  "A sequence-number Offset (LevelDB's offset type). Resume a tag query just
   after sequence number `n`."
  [n]
  (Offset/sequence (long n)))

(defn offset->clj
  "Map a Pekko Offset to Clojure data:
   - NoOffset       -> {:type :no-offset}
   - Sequence       -> {:type :sequence :value n}
   - TimeBasedUUID  -> {:type :time-based-uuid :value #uuid ...}
   Anything else is returned unchanged.

   (NoOffset is a Scala case object — its singleton instance is a NoOffset$, not
   the NoOffset class — so it is matched by value equality to `no-offset`.)"
  [offset]
  (cond
    (instance? Sequence offset)      {:type :sequence :value (.value ^Sequence offset)}
    (instance? TimeBasedUUID offset) {:type :time-based-uuid :value (.value ^TimeBasedUUID offset)}
    (= offset no-offset)             {:type :no-offset}
    :else offset))

(defn- ->offset
  "Coerce an offset argument to a Pekko Offset. Accepts a raw Offset, nil (=>
   from the beginning), a number (=> sequence offset), or the `offset->clj` map
   form `{:type :sequence :value n}` / `{:type :no-offset}`."
  ^Offset [offset]
  (cond
    (nil? offset)              no-offset
    (instance? Offset offset)  offset
    (number? offset)           (sequence-offset offset)
    (map? offset)              (case (:type offset)
                                 :no-offset no-offset
                                 :sequence  (sequence-offset (:value offset))
                                 (throw (ex-info "Unsupported offset map" {:offset offset})))
    :else (throw (ex-info "Unsupported offset" {:offset offset}))))

;; ---------------------------------------------------------------------------
;; EventEnvelope mapping
;; ---------------------------------------------------------------------------

(defn envelope->map
  "Map an EventEnvelope to a Clojure map:
     {:offset {:type :sequence :value n}
      :persistence-id \"…\"
      :sequence-nr 3
      :event <payload>
      :timestamp 1234567890
      :metadata <event metadata, or nil when the event carries none>}"
  [^EventEnvelope env]
  (let [md (.getEventMetaData env)]
    {:offset (offset->clj (.offset env))
     :persistence-id (.persistenceId env)
     :sequence-nr (.sequenceNr env)
     :event (.event env)
     :timestamp (.timestamp env)
     :metadata (.orElse md nil)}))

(defn- map-envelopes
  "Map a Source<EventEnvelope> to a Source of Clojure maps."
  [source]
  (stream/smap source envelope->map))

(defn- require-capability [journal iface what]
  (when-not (instance? iface journal)
    (throw (ex-info (str "Read journal does not support " what)
                    {:journal (class journal) :capability iface})))
  journal)

;; ---------------------------------------------------------------------------
;; Queries — each returns a pekko-clj.stream Source of Clojure maps/strings
;; ---------------------------------------------------------------------------

(defn events-by-tag
  "Live Source of events tagged `tag`, from `offset` onward (default: the
   beginning). Emits `envelope->map`s as new tagged events are persisted; never
   completes on its own — stop it with `stream/take`, a KillSwitch, etc.

   Tag events with defactor-persistent's (tagger [event] ...) clause."
  ([journal tag] (events-by-tag journal tag nil))
  ([journal tag offset]
   (let [^EventsByTagQuery j (require-capability journal EventsByTagQuery "events-by-tag")]
     (map-envelopes (.eventsByTag j tag (->offset offset))))))

(defn current-events-by-tag
  "Point-in-time Source of events tagged `tag` from `offset` onward. Emits every
   matching event already in the journal, then completes."
  ([journal tag] (current-events-by-tag journal tag nil))
  ([journal tag offset]
   (let [^CurrentEventsByTagQuery j (require-capability journal CurrentEventsByTagQuery "current-events-by-tag")]
     (map-envelopes (.currentEventsByTag j tag (->offset offset))))))

(defn events-by-persistence-id
  "Live Source of a single persistent actor's events, sequence numbers `from`..
   `to` inclusive (defaults 0..Long/MAX_VALUE — the whole stream, staying open
   for new events)."
  ([journal persistence-id]
   (events-by-persistence-id journal persistence-id 0 Long/MAX_VALUE))
  ([journal persistence-id from to]
   (let [^EventsByPersistenceIdQuery j (require-capability journal EventsByPersistenceIdQuery "events-by-persistence-id")]
     (map-envelopes (.eventsByPersistenceId j persistence-id (long from) (long to))))))

(defn current-events-by-persistence-id
  "Point-in-time Source of a single persistent actor's events, sequence numbers
   `from`..`to` inclusive; completes when the current range is exhausted."
  ([journal persistence-id]
   (current-events-by-persistence-id journal persistence-id 0 Long/MAX_VALUE))
  ([journal persistence-id from to]
   (let [^CurrentEventsByPersistenceIdQuery j (require-capability journal CurrentEventsByPersistenceIdQuery "current-events-by-persistence-id")]
     (map-envelopes (.currentEventsByPersistenceId j persistence-id (long from) (long to))))))

(defn persistence-ids
  "Live Source of persistence ids (strings), emitting new ids as actors first
   persist. Does not complete."
  [journal]
  (let [^PersistenceIdsQuery j (require-capability journal PersistenceIdsQuery "persistence-ids")]
    (.persistenceIds j)))

(defn current-persistence-ids
  "Point-in-time Source of the persistence ids (strings) currently in the
   journal; completes."
  [journal]
  (let [^CurrentPersistenceIdsQuery j (require-capability journal CurrentPersistenceIdsQuery "current-persistence-ids")]
    (.currentPersistenceIds j)))
