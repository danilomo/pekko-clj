(ns pekko-clj.cluster.ddata-test
  "Tests for N8: Distributed Data (ORSet / LWWMap / PNCounter)."
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.cluster.ddata :as ddata]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.actor ActorRef]
           [org.apache.pekko.cluster.ddata Key ORSet SelfUniqueAddress
            Replicator$WriteLocal$ Replicator$WriteAll
            Replicator$WriteMajority Replicator$ReadLocal$
            Replicator$ReadAll Replicator$ReadMajority]))

(defn- await-result [future]
  (deref future 10000 {:status :ask-timeout}))

;; ---------------------------------------------------------------------------
;; Keys, consistency levels and conversions (no cluster needed)
;; ---------------------------------------------------------------------------

(deftest keys-test
  (is (instance? Key (ddata/or-set-key "s")))
  (is (= "s" (ddata/key-id (ddata/or-set-key "s"))))
  (is (= "m" (ddata/key-id (ddata/lww-map-key "m"))))
  (is (= "c" (ddata/key-id (ddata/pn-counter-key "c")))))

(deftest consistency-levels-test
  (is (instance? Replicator$WriteLocal$ (ddata/write-consistency :local)))
  (is (instance? Replicator$WriteLocal$ (ddata/write-consistency nil)))
  (is (instance? Replicator$WriteMajority (ddata/write-consistency :majority 2000)))
  (is (= 2000 (.toMillis (.timeout ^Replicator$WriteMajority (ddata/write-consistency :majority 2000)))))
  (is (instance? Replicator$WriteAll (ddata/write-consistency :all 1000)))
  (is (instance? Replicator$ReadLocal$ (ddata/read-consistency :local)))
  (is (instance? Replicator$ReadMajority (ddata/read-consistency :majority 2000)))
  (is (instance? Replicator$ReadAll (ddata/read-consistency :all)))
  (is (thrown? IllegalArgumentException (ddata/write-consistency :quorum)))
  (is (thrown? IllegalArgumentException (ddata/read-consistency :quorum))))

(deftest crdt->clj-test
  (is (= #{} (ddata/crdt->clj (ddata/empty-or-set))))
  (is (= {} (ddata/crdt->clj (ddata/empty-lww-map))))
  (is (= 0 (ddata/crdt->clj (ddata/empty-pn-counter))))
  (is (= :other (ddata/crdt->clj :other)) "non-CRDT values pass through"))

;; ---------------------------------------------------------------------------
;; Extension access
;; ---------------------------------------------------------------------------

(deftest replicator-and-self-address-test
  (let [sys (ts/create-cluster-system "ddata-extension-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (is (instance? ActorRef (ddata/replicator sys)))
      (is (= (ddata/replicator sys) (ddata/replicator (ddata/replicator sys)))
          "an already-resolved replicator ref passes through")
      (is (instance? SelfUniqueAddress (ddata/self-address sys)))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; ORSet
;; ---------------------------------------------------------------------------

(deftest or-set-test
  (let [sys (ts/create-cluster-system "ddata-orset-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [k (ddata/or-set-key "online-users")]
        ;; Unset keys read as :not-found
        (is (= :not-found (:status (await-result (ddata/get-data sys k)))))
        (is (nil? (ddata/value sys k)))

        (is (= :success (:status (await-result (ddata/add! sys k "ada")))))
        (await-result (ddata/add! sys k "grace"))
        (let [result (await-result (ddata/get-data sys k))]
          (is (= :success (:status result)))
          (is (= "online-users" (:key result)))
          (is (= #{"ada" "grace"} (:value result)))
          (is (instance? ORSet (:data result)) "the raw CRDT is available too"))

        (is (= :success (:status (await-result (ddata/remove! sys k "ada")))))
        (is (= #{"grace"} (ddata/value sys k))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; LWWMap
;; ---------------------------------------------------------------------------

(deftest lww-map-test
  (let [sys (ts/create-cluster-system "ddata-lwwmap-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [k (ddata/lww-map-key "config")]
        (await-result (ddata/put! sys k "level" "debug"))
        (await-result (ddata/put! sys k "retries" 3))
        (is (= {"level" "debug" "retries" 3} (ddata/value sys k)))
        ;; Last write wins
        (await-result (ddata/put! sys k "level" "info"))
        (is (= "info" (get (ddata/value sys k) "level")))
        (await-result (ddata/remove-key! sys k "retries"))
        (is (= {"level" "info"} (ddata/value sys k))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; PNCounter
;; ---------------------------------------------------------------------------

(deftest pn-counter-test
  (let [sys (ts/create-cluster-system "ddata-counter-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [k (ddata/pn-counter-key "hits")]
        (await-result (ddata/increment! sys k))
        (await-result (ddata/increment! sys k 4))
        (is (= 5 (ddata/value sys k)))
        (await-result (ddata/decrement! sys k 2))
        (is (= 3 (ddata/value sys k)))
        (is (= 3 (:value (await-result (ddata/get-data sys k)))))
        (is (number? (ddata/value sys k)) "counters read back as plain numbers"))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Generic update! and consistency options
;; ---------------------------------------------------------------------------

(deftest update-with-explicit-fn-test
  (let [sys (ts/create-cluster-system "ddata-update-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [k (ddata/or-set-key "manual")
            node (ddata/self-address sys)]
        ;; The escape hatch: mutate the CRDT yourself
        (is (= :success (:status (await-result
                                  (ddata/update! sys k
                                                 (fn [^ORSet s] (.add s node "x"))
                                                 {:initial (ddata/empty-or-set)})))))
        (is (= #{"x"} (ddata/value sys k)))
        ;; :majority consistency on a single-node cluster is still a majority
        (is (= :success (:status (await-result
                                  (ddata/add! sys k "y" {:consistency :majority
                                                         :timeout-ms 5000})))))
        (is (= #{"x" "y"} (ddata/value sys k {:consistency :majority})))
        ;; A modify fn that throws surfaces as a failure, not a hang
        (let [result (await-result (ddata/update! sys k
                                                  (fn [_] (throw (RuntimeException. "nope")))
                                                  {:initial (ddata/empty-or-set)}))]
          (is (= :failure (:status result)))
          (is (some? (:error result)))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(deftest subscribe-test
  (let [sys (ts/create-cluster-system "ddata-subscribe-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [k (ddata/or-set-key "watched")
            changes (atom [])
            subscriber (ddata/subscribe sys k (fn [change] (swap! changes conj change)))]
        (is (instance? ActorRef subscriber))
        (await-result (ddata/add! sys k "first"))
        (is (eventually (some #(= #{"first"} (:value %)) @changes)))
        (await-result (ddata/add! sys k "second"))
        (is (eventually (some #(= #{"first" "second"} (:value %)) @changes)))
        (let [change (last @changes)]
          (is (= "watched" (:key change)))
          (is (false? (:deleted? change))))
        ;; After unsubscribing, further changes are not delivered
        (ddata/unsubscribe sys k subscriber)
        (let [seen (count @changes)]
          (await-result (ddata/add! sys k "third"))
          (is (eventually (= #{"first" "second" "third"} (ddata/value sys k))))
          (Thread/sleep 500)                      ;; give a stray notification time to arrive
          (is (= seen (count @changes)))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Delete
;; ---------------------------------------------------------------------------

(deftest delete-test
  (let [sys (ts/create-cluster-system "ddata-delete-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [k (ddata/or-set-key "temporary")]
        (await-result (ddata/add! sys k "a"))
        (is (= #{"a"} (ddata/value sys k)))
        (is (= :success (:status (await-result (ddata/delete! sys k)))))
        ;; A deleted key stays deleted — reads and writes both report it
        (is (= :deleted (:status (await-result (ddata/get-data sys k)))))
        (is (= :deleted (:status (await-result (ddata/add! sys k "b")))))
        (is (nil? (ddata/value sys k))))
      (finally
        (ts/terminate-system sys)))))
