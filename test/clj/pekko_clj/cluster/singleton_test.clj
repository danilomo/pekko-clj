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

;; B17: no :stop (or any) handling at all — proves the default
;; termination-message stops the singleton with no cooperation from the actor.
(core/defactor vanilla-singleton
  "Singleton with no lifecycle handling — relies entirely on the default
   termination-message to be stoppable."
  (init [args] {:value (or (:initial args) 0)})
  (handle :get
    (core/reply (:value state))))

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

;; B22: cooperatively stops itself on a *custom* termination-message (:bye).
;; Under :restart-with-stop supervision this is the case that used to stall
;; hand-over — the onStop supervisor restarted the actor forever.
(core/defactor coop-singleton
  "Singleton that stops itself when it receives the custom :bye termination-message."
  (init [args] {:value (or (:initial args) 0)})
  (handle :get
    (core/reply (:value state)))
  (handle :bye
    (core/stop (core/self))))

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

(deftest singleton-default-termination-message-stops-without-cooperation
  ;; B17: :termination-message defaults to PoisonPill (not the old :stop
  ;; keyword, which a defactor that doesn't explicitly handle it just leaves
  ;; unhandled — hand-over would stall until retries are exhausted). Leaving a
  ;; 1-node cluster triggers hand-over with nowhere to go: the manager sends
  ;; the termination-message and waits for the child to stop. A short
  ;; hand-over-retry-interval bounds the test if this regresses.
  (let [sys (ts/create-cluster-system "singleton-b17-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (singleton/start sys vanilla-singleton
                       {:name "b17-singleton"
                        :hand-over-retry-interval 200})
      (is (ts/poll-until #(singleton/singleton-running-here? sys "/user/b17-singleton") 10000)
          "singleton should be running before we test its shutdown")
      (cluster/leave sys)
      (is (ts/poll-until #(not (singleton/singleton-running-here? sys "/user/b17-singleton")) 5000)
          "singleton should stop promptly under the PoisonPill default even though the actor defines no :stop handler")
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

;; B22: hand-over must complete under supervision. Leaving a 1-node cluster
;; triggers hand-over with nowhere to go: the manager sends the
;; termination-message and waits for its child (the backoff supervisor) to
;; terminate. `singleton-running-here?` on the manager path resolves that child,
;; so it flips to false exactly when hand-over completes.

(deftest singleton-restart-with-stop-hands-over-with-custom-message
  ;; The core B22 regression. Without withFinalStopMessage the onStop supervisor
  ;; restarts the actor after it stops itself on :bye, so the supervisor never
  ;; terminates and this poll times out (verified live: still running after 8s).
  (let [sys (ts/create-cluster-system "singleton-b22-stop-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (singleton/start sys coop-singleton
                       {:name "b22-stop-singleton"
                        :termination-message :bye
                        :hand-over-retry-interval 200
                        :supervision {:strategy :restart-with-stop
                                      :min-backoff-ms 200
                                      :max-backoff-ms 500}})
      (is (ts/poll-until #(singleton/singleton-running-here? sys "/user/b22-stop-singleton") 10000)
          "singleton should be running before hand-over")
      (cluster/leave sys)
      (is (ts/poll-until #(not (singleton/singleton-running-here? sys "/user/b22-stop-singleton")) 8000)
          ":restart-with-stop must not stall hand-over when the actor stops on the custom termination-message")
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-restart-with-backoff-hands-over-with-custom-message
  ;; Pins the assumption that onFailure supervisors already hand over: a clean
  ;; self-stop is not a failure, so the supervisor stops itself with no extra
  ;; wiring needed.
  (let [sys (ts/create-cluster-system "singleton-b22-backoff-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (singleton/start sys coop-singleton
                       {:name "b22-backoff-singleton"
                        :termination-message :bye
                        :hand-over-retry-interval 200
                        :supervision {:strategy :restart-with-backoff
                                      :min-backoff-ms 200
                                      :max-backoff-ms 500}})
      (is (ts/poll-until #(singleton/singleton-running-here? sys "/user/b22-backoff-singleton") 10000)
          "singleton should be running before hand-over")
      (cluster/leave sys)
      (is (ts/poll-until #(not (singleton/singleton-running-here? sys "/user/b22-backoff-singleton")) 8000)
          ":restart-with-backoff hands over on a clean self-stop")
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-supervision-default-poison-pill-hands-over
  ;; Pins that the default PoisonPill termination-message still hands over under
  ;; :restart-with-stop — PoisonPill stops the supervisor directly, so it never
  ;; stalled; the withFinalStopMessage fix must not break this path.
  (let [sys (ts/create-cluster-system "singleton-b22-pill-test")]
    (try
      (is (ts/wait-for-cluster-up sys))
      (singleton/start sys vanilla-singleton
                       {:name "b22-pill-singleton"
                        :hand-over-retry-interval 200
                        :supervision {:strategy :restart-with-stop
                                      :min-backoff-ms 200
                                      :max-backoff-ms 500}})
      (is (ts/poll-until #(singleton/singleton-running-here? sys "/user/b22-pill-singleton") 10000)
          "singleton should be running before hand-over")
      (cluster/leave sys)
      (is (ts/poll-until #(not (singleton/singleton-running-here? sys "/user/b22-pill-singleton")) 8000)
          "default PoisonPill hand-over still completes under :restart-with-stop")
      (finally
        (ts/terminate-system sys)))))

(deftest singleton-unknown-supervision-strategy-throws
  ;; wrap-with-supervision runs before any cluster extension is touched, so a
  ;; plain (non-cluster) system is enough to observe the throw.
  (let [sys (core/actor-system "singleton-bad-strategy-test")]
    (try
      (is (thrown? IllegalArgumentException
            (singleton/start sys simple-singleton
                             {:name "bad-strategy-singleton"
                              :supervision {:strategy :restart-with-backof}})))
      (finally
        (.terminate sys)))))

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
