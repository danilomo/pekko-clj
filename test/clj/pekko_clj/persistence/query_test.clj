(ns pekko-clj.persistence.query-test
  (:require [clojure.test :refer :all]
            [pekko-clj.persistence :as p]
            [pekko-clj.persistence.query :as q]
            [pekko-clj.stream :as s]
            [pekko-clj.core :as core]
            [pekko-clj.test-support :refer [eventually]])
  (:import [org.apache.pekko.actor ActorSystem]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [java.util UUID]))

(defn- create-test-system [name]
  (ActorSystem/create name (ConfigFactory/load "persistence-test.conf")))

(defn- terminate-system [sys]
  (.terminate sys)
  (Await/result (.whenTerminated sys) (Duration/create 10 "seconds")))

(defn- unique-id [] (str (UUID/randomUUID)))

;; A persistent actor that tags each event, so its events can be read back with
;; events-by-tag across persistence ids.
(p/defactor-persistent tagged-counter
  :persistence-id (fn [args] (str "tagged-" (:id args)))

  (init [_] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command [:add n]
    (p/persist [:added n]))

  (command :get
    (p/reply (:count state)))

  (event [:incremented]
    (update state :count inc))

  (event [:added n]
    (update state :count + n))

  ;; :incremented -> #{"counter" "all"}, [:added n] -> #{"add" "all"}
  (tagger [event]
    (case (first event)
      :incremented #{"counter" "all"}
      :added       #{"add" "all"}
      nil)))

;; snapshot-every 2, keep 1, delete subsumed events: after 6 events only the
;; two the kept snapshot (at seq 6) does not cover remain in the journal.
(p/defactor-persistent deleting-counter
  :persistence-id (fn [args] (str "del-" (:id args)))

  (init [_] {:count 0})

  (command :increment
    (p/persist [:incremented]))

  (command :get
    (p/reply (:count state)))

  (event [:incremented]
    (update state :count inc))

  (snapshot-every 2 1)
  (delete-events-on-snapshot))

(defn- drain
  "Run a Source to a seq (blocking), returning a Clojure vector."
  [source mat]
  (vec (s/await-completion (s/run-to-seq source mat) 5000)))

;; ---------------------------------------------------------------------------
;; Offset + envelope mapping (pure, no journal)
;; ---------------------------------------------------------------------------

(deftest offset->clj-maps-known-offsets
  (is (= {:type :no-offset} (q/offset->clj q/no-offset)))
  (is (= {:type :sequence :value 7} (q/offset->clj (q/sequence-offset 7)))))

(deftest envelope->map-shape
  ;; Build a real EventEnvelope and confirm the mapping.
  (let [env (org.apache.pekko.persistence.query.EventEnvelope/apply
             (q/sequence-offset 3) "pid-1" 3 [:incremented])
        m (q/envelope->map env)]
    (is (= "pid-1" (:persistence-id m)))
    (is (= 3 (:sequence-nr m)))
    (is (= [:incremented] (:event m)))
    (is (= {:type :sequence :value 3} (:offset m)))))

;; ---------------------------------------------------------------------------
;; Queries against a live LevelDB journal
;; ---------------------------------------------------------------------------

(deftest current-events-by-persistence-id-returns-events
  (let [sys (create-test-system "query-test")
        id (unique-id)
        pid (str "tagged-" id)
        mat (s/materializer sys)
        actor (p/spawn sys tagged-counter {:id id})]
    (try
      (core/! actor :increment)
      (core/! actor [:add 5])
      (is (= 6 (core/<! actor :get 3000)))
      (let [j (q/read-journal sys)
            events (eventually
                     (let [es (->> (drain (q/current-events-by-persistence-id j pid) mat)
                                   (mapv :event))]
                       (when (= 2 (count es)) es)))]
        (is (= [[:incremented] [:added 5]] events)))
      (finally
        (terminate-system sys)))))

(deftest current-events-by-tag-fans-in-across-actors
  ;; The "all"/"counter" tags accumulate across every tagged-counter ever
  ;; persisted to the shared journal, so scope assertions to these two actors'
  ;; persistence ids.
  (let [sys (create-test-system "query-test")
        id-a (unique-id)
        id-b (unique-id)
        pid-a (str "tagged-" id-a)
        pid-b (str "tagged-" id-b)
        mine? #(contains? #{pid-a pid-b} (:persistence-id %))
        mat (s/materializer sys)
        a (p/spawn sys tagged-counter {:id id-a})
        b (p/spawn sys tagged-counter {:id id-b})]
    (try
      (core/! a :increment)                 ; tags counter, all
      (core/! b :increment)                 ; tags counter, all
      (core/! a [:add 2])                   ; tags add, all
      (is (= 1 (core/<! b :get 3000)))
      (is (= 3 (core/<! a :get 3000)))
      (let [j (q/read-journal sys)]
        ;; "counter" tag: the two :increment events from these two actors.
        (let [counters (eventually
                         (let [es (->> (drain (q/current-events-by-tag j "counter") mat)
                                       (filter mine?) (mapv :event))]
                           (when (= 2 (count es)) es)))]
          (is (= [[:incremented] [:incremented]] counters)))
        ;; "all" tag: all three events from these two actors.
        (let [alls (eventually
                     (let [es (->> (drain (q/current-events-by-tag j "all") mat)
                                   (filter mine?) (mapv :event))]
                       (when (= 3 (count es)) es)))]
          (is (= 3 (count alls)))
          (is (= #{[:incremented] [:added 2]} (set alls)))))
      (finally
        (terminate-system sys)))))

(deftest current-events-by-tag-offset-resumes
  (let [sys (create-test-system "query-test")
        id (unique-id)
        pid (str "tagged-" id)
        mine? #(= pid (:persistence-id %))
        mat (s/materializer sys)
        actor (p/spawn sys tagged-counter {:id id})]
    (try
      (core/! actor :increment)
      (core/! actor :increment)
      (is (= 2 (core/<! actor :get 3000)))
      (let [j (q/read-journal sys)
            mine (eventually
                   (let [es (->> (drain (q/current-events-by-tag j "counter") mat)
                                 (filter mine?))]
                     (when (= 2 (count es)) es)))
            first-offset (:offset (first mine))]
        (is (= :sequence (:type first-offset)))
        ;; Resuming from the first event's offset skips it, leaving the second.
        (let [after (->> (drain (q/current-events-by-tag j "counter" first-offset) mat)
                         (filter mine?))]
          (is (= 1 (count after)))))
      (finally
        (terminate-system sys)))))

(deftest current-persistence-ids-includes-actor
  (let [sys (create-test-system "query-test")
        id (unique-id)
        pid (str "tagged-" id)
        mat (s/materializer sys)
        actor (p/spawn sys tagged-counter {:id id})]
    (try
      (core/! actor :increment)
      (is (= 1 (core/<! actor :get 3000)))
      (let [j (q/read-journal sys)]
        (is (eventually
              (contains? (set (drain (q/current-persistence-ids j) mat)) pid))))
      (finally
        (terminate-system sys)))))

(deftest events-by-tag-live-picks-up-new-events
  (let [sys (create-test-system "query-test")
        id (unique-id)
        mat (s/materializer sys)
        actor (p/spawn sys tagged-counter {:id id})]
    (try
      (core/! actor :increment)
      (is (= 1 (core/<! actor :get 3000)))
      (let [pid (str "tagged-" id)
            j (q/read-journal sys)
            ;; Live query: keep only this actor's events (the tag accumulates
            ;; across runs), take the first, complete when it arrives.
            fut (-> (q/events-by-tag j "counter")
                    (s/sfilter #(= pid (:persistence-id %)))
                    (s/take 1)
                    (s/run-to-seq mat))
            events (s/await-completion fut 10000)]
        (is (= [[:incremented]] (mapv :event (vec events)))))
      (finally
        (terminate-system sys)))))

(deftest delete-events-on-snapshot-removes-subsumed-events
  ;; Observable proof (not just recovery) that retention deletes events: after 6
  ;; events with (snapshot-every 2 1) + (delete-events-on-snapshot), the kept
  ;; snapshot is at seq 6 and events at or below seq 4 are gone — only seq 5 & 6
  ;; remain in the journal.
  (let [sys (create-test-system "query-test")
        id (unique-id)
        pid (str "del-" id)
        mat (s/materializer sys)
        actor (p/spawn sys deleting-counter {:id id})]
    (try
      (dotimes [_ 6] (core/! actor :increment))
      (is (= 6 (core/<! actor :get 3000)))
      (let [j (q/read-journal sys)
            remaining (eventually
                        (let [es (drain (q/current-events-by-persistence-id j pid) mat)]
                          (when (= 2 (count es)) es)))]
        (is (= [5 6] (mapv :sequence-nr remaining))))
      (finally
        (terminate-system sys)))))

(deftest read-journal-rejects-unsupported-capability
  ;; envelope->map / query fns throw a clear error when the journal lacks a
  ;; capability. Use a stub that implements none of the query interfaces.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not support"
        (q/events-by-tag (Object.) "x"))))
