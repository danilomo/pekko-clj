(ns pekko-clj.cluster.singleton-test
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.cluster.singleton :as singleton]
            [pekko-clj.test-support :as ts :refer [eventually]]))

;; core/<?> now returns a CompletableFuture; block on it (deref returns nil on
;; timeout, rethrows the actor's failure otherwise).
(defn await-result [future]
  (deref future 30000 nil))

;; ---------------------------------------------------------------------------
;; Test Actor Definitions
;; ---------------------------------------------------------------------------

(core/defactor simple-singleton
  "Simple singleton for testing"
  (init [args]
    {:value (or (:initial args) 0)})
  (handle :get
    (core/reply (:value state)))
  (handle [:set v]
    (assoc state :value v))
  (handle :inc
    (update state :value inc))
  (handle :stop
    :stop))

(def singleton-events (atom []))

(core/defactor logging-singleton
  "Singleton that logs lifecycle events"
  (init [args]
    (swap! singleton-events conj [:started])
    {:id (or (:id args) (rand-int 10000))})
  (handle :get-id
    (core/reply (:id state)))
  (handle :ping
    (core/reply :pong))
  (handle :fail
    (swap! singleton-events conj [:failing])
    (throw (ex-info "Intentional failure" {})))
  (handle :stop
    (swap! singleton-events conj [:stopping])
    :stop))

;; ---------------------------------------------------------------------------
;; Tests: Basic Singleton
;; ---------------------------------------------------------------------------

(deftest singleton-start-test
  (let [sys (ts/create-cluster-system "singleton-start-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [manager (singleton/start sys simple-singleton
                                     {:name "test-singleton"})]
        (is (some? manager))
        (is (instance? org.apache.pekko.actor.ActorRef manager)))
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-proxy-test
  (let [sys (ts/create-cluster-system "singleton-proxy-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [_ (singleton/start sys simple-singleton
                               {:name "test-singleton"})
            proxy (singleton/proxy sys
                                   {:singleton-manager-path "/user/test-singleton"})]
        (is (some? proxy))
        (is (instance? org.apache.pekko.actor.ActorRef proxy)))
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-start-with-proxy-test
  (let [sys (ts/create-cluster-system "singleton-start-proxy-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [{:keys [manager proxy]} (singleton/start-with-proxy sys simple-singleton
                                                                {:name "test-singleton"})]
        (is (some? manager))
        (is (some? proxy))
        (is (not= manager proxy)))
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-message-passing-test
  ;; Restored (H4): the previous flakiness was fixed-sleep timing. Polling with
  ;; `eventually` waits for the proxy to identify the singleton and route to it.
  (let [sys (ts/create-cluster-system "singleton-msg-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [{:keys [proxy]} (singleton/start-with-proxy sys simple-singleton
                                                        {:name "test-singleton"
                                                         :args {:initial 42}
                                                         :identification-interval-ms 100})]
        ;; Poll until the singleton is reachable via the proxy and returns its
        ;; initial value.
        (is (eventually 20000 (= 42 (core/<! proxy :get 3000))))
        ;; Update the value through the proxy, then poll until it is reflected.
        (core/! proxy [:set 100])
        (is (eventually 20000 (= 100 (core/<! proxy :get 3000)))))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Singleton with Options
;; ---------------------------------------------------------------------------

(deftest singleton-with-role-test
  (let [sys (cluster/create-system "singleton-role-test"
                                   {:hostname "127.0.0.1"
                                    :port 0
                                    :roles ["backend"]})]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [manager (singleton/start sys simple-singleton
                                     {:name "role-singleton"
                                      :role "backend"})]
        (is (some? manager)))
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-with-hand-over-settings-test
  (let [sys (ts/create-cluster-system "singleton-handover-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [manager (singleton/start sys simple-singleton
                                     {:name "handover-singleton"
                                      :hand-over-retry-interval 2000})]
        (is (some? manager)))
      (finally
        (ts/terminate-system sys)))))

(deftest proxy-with-custom-settings-test
  (let [sys (ts/create-cluster-system "proxy-settings-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [_ (singleton/start sys simple-singleton
                               {:name "test-singleton"})
            proxy (singleton/proxy sys
                                   {:singleton-manager-path "/user/test-singleton"
                                    :buffer-size 2000
                                    :identification-interval-ms 500})]
        (is (some? proxy)))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Supervision
;; ---------------------------------------------------------------------------

(deftest singleton-with-backoff-supervision-test
  (reset! singleton-events [])
  (let [sys (ts/create-cluster-system "singleton-backoff-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (let [{:keys [proxy]} (singleton/start-with-proxy sys logging-singleton
                                                        {:name "supervised-singleton"
                                                         :supervision {:strategy :restart-with-backoff
                                                                       :min-backoff-ms 100
                                                                       :max-backoff-ms 1000
                                                                       :random-factor 0.1}})]
        ;; Poll until the singleton is up and answering via the proxy.
        (is (eventually 15000 (= :pong (await-result (core/<?> proxy :ping 3000)))))
        ;; Verify started event
        (is (some #(= [:started] %) @singleton-events)))
      (finally
        (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Tests: Singleton State Query
;; ---------------------------------------------------------------------------

(deftest singleton-running-here-test
  (let [sys (ts/create-cluster-system "singleton-here-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      ;; Before starting, should return false (nonexistent manager path).
      (is (not (singleton/singleton-running-here? sys "/user/nonexistent")))
      ;; Start the singleton; in a single-node cluster this node hosts it.
      (singleton/start sys simple-singleton
                       {:name "local-singleton"})
      ;; Positive branch (B6): once the singleton child is running on this node,
      ;; singleton-running-here? must return true. Previously it always returned
      ;; false because (.provider (.dispatcher system)) threw and was swallowed.
      (is (ts/poll-until #(singleton/singleton-running-here? sys "/user/local-singleton")
                         10000))
      ;; A different, non-running manager path still returns false.
      (is (not (singleton/singleton-running-here? sys "/user/local-singleton-other")))
      (finally
        (ts/terminate-system sys)))))
