(ns pekko-clj.cluster.daemon-test
  "Tests for N5's Sharded Daemon Process wrapper."
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.cluster.daemon :as daemon]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [org.apache.pekko.cluster.sharding.typed ShardedDaemonProcessSettings]))

(def started-workers (atom #{}))
(def worker-messages (atom []))

(core/defactor partition-worker
  "Daemon worker that records the index it was started with."
  (init [i]
    (swap! started-workers conj i)
    {:index i})
  (handle [:ping]
    (swap! worker-messages conj [(:index state) :ping])
    state))

;; ---------------------------------------------------------------------------
;; Settings
;; ---------------------------------------------------------------------------

(deftest daemon-settings-test
  (let [sys (ts/create-cluster-system "daemon-settings-test")]
    (try
      (let [s (daemon/settings sys {})]
        (is (instance? ShardedDaemonProcessSettings s)))
      (let [s (daemon/settings sys {:keep-alive-interval 3000 :role "workers"})]
        (is (= 3000 (.toMillis (.keepAliveInterval s))))
        (is (= "workers" (.get (.role s)))))
      ;; A java.time.Duration is accepted too
      (is (= 5000 (.toMillis (.keepAliveInterval
                              (daemon/settings sys {:keep-alive-interval
                                                    (java.time.Duration/ofSeconds 5)})))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Running workers
;; ---------------------------------------------------------------------------

(deftest daemon-process-starts-all-instances-test
  (let [sys (ts/create-cluster-system "daemon-process-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (reset! started-workers #{})
      (is (nil? (daemon/start sys "partition-workers" 3 partition-worker
                              {:keep-alive-interval 1000}))
          "start returns nil — daemon workers are not addressed directly")
      ;; Pekko keeps exactly one worker per index alive across the cluster; each
      ;; classic actor sees its index as init args.
      (is (eventually 20000 (= #{0 1 2} @started-workers)))
      (finally
        (ts/terminate-system sys)))))

(deftest daemon-process-with-stop-message-test
  ;; The stop message is delivered through the typed wrapper to the classic actor.
  (let [sys (ts/create-cluster-system "daemon-stop-message-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (reset! started-workers #{})
      (daemon/start sys "stoppable-workers" 2 partition-worker
                    {:keep-alive-interval 1000
                     :stop-message :stop})
      (is (eventually 20000 (= #{0 1} @started-workers)))
      (finally
        (ts/terminate-system sys)))))
