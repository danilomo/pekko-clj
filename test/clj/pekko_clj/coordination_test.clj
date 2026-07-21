(ns pekko-clj.coordination-test
  "Tests for N6: Split-Brain-Resolver config helper + CoordinatedShutdown wrapper
   (both live in pekko-clj.cluster)."
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.cluster :as cluster]
            [pekko-clj.test-support :as ts])
  (:import [com.typesafe.config Config]
           [org.apache.pekko Done]
           [org.apache.pekko.actor CoordinatedShutdown CoordinatedShutdown$Reason]
           [java.util.concurrent CompletableFuture]))

;; ---------------------------------------------------------------------------
;; Split Brain Resolver config helper
;; ---------------------------------------------------------------------------

(deftest sbr-keep-majority-default-test
  (let [^Config cfg (cluster/split-brain-resolver-config {})]
    (is (instance? Config cfg))
    (is (= "org.apache.pekko.cluster.sbr.SplitBrainResolverProvider"
           (.getString cfg "pekko.cluster.downing-provider-class")))
    (is (= "keep-majority"
           (.getString cfg "pekko.cluster.split-brain-resolver.active-strategy")))))

(deftest sbr-role-and-stable-after-test
  (let [^Config cfg (cluster/split-brain-resolver-config
                     {:active-strategy :keep-majority
                      :role "backend"
                      :stable-after 15000})]
    (is (= "backend" (.getString cfg "pekko.cluster.split-brain-resolver.keep-majority.role")))
    ;; A number is rendered as milliseconds and parses back as a real duration.
    (is (= 15000 (.toMillis (.getDuration cfg "pekko.cluster.split-brain-resolver.stable-after"))))))

(deftest sbr-static-quorum-test
  (let [^Config cfg (cluster/split-brain-resolver-config
                     {:active-strategy :static-quorum
                      :quorum-size 3
                      :role "data"})]
    (is (= "static-quorum" (.getString cfg "pekko.cluster.split-brain-resolver.active-strategy")))
    (is (= 3 (.getInt cfg "pekko.cluster.split-brain-resolver.static-quorum.quorum-size")))
    (is (= "data" (.getString cfg "pekko.cluster.split-brain-resolver.static-quorum.role")))))

(deftest sbr-keep-oldest-test
  (let [^Config cfg (cluster/split-brain-resolver-config
                     {:active-strategy :keep-oldest
                      :down-if-alone false})]
    (is (= "keep-oldest" (.getString cfg "pekko.cluster.split-brain-resolver.active-strategy")))
    (is (false? (.getBoolean cfg "pekko.cluster.split-brain-resolver.keep-oldest.down-if-alone")))))

(deftest sbr-lease-majority-test
  (let [^Config cfg (cluster/split-brain-resolver-config
                     {:active-strategy :lease-majority
                      :lease-implementation "pekko.coordination.lease.kubernetes"
                      :release-after "40s"})]
    (is (= "lease-majority" (.getString cfg "pekko.cluster.split-brain-resolver.active-strategy")))
    (is (= "pekko.coordination.lease.kubernetes"
           (.getString cfg "pekko.cluster.split-brain-resolver.lease-majority.lease-implementation")))
    (is (= "40s" (.getString cfg "pekko.cluster.split-brain-resolver.lease-majority.release-after")))))

(deftest sbr-down-all-when-unstable-rendering-test
  (is (= "on"  (.getString (cluster/split-brain-resolver-config {:down-all-when-unstable true})
                           "pekko.cluster.split-brain-resolver.down-all-when-unstable")))
  (is (= "off" (.getString (cluster/split-brain-resolver-config {:down-all-when-unstable false})
                           "pekko.cluster.split-brain-resolver.down-all-when-unstable")))
  ;; down-all strategy needs no sub-block
  (is (= "down-all" (.getString (cluster/split-brain-resolver-config {:active-strategy :down-all})
                                "pekko.cluster.split-brain-resolver.active-strategy"))))

(deftest sbr-unknown-strategy-throws-test
  (is (thrown? IllegalArgumentException
               (cluster/split-brain-resolver-config {:active-strategy :bogus}))))

;; ---------------------------------------------------------------------------
;; create-system integration (SBR merged into the system's config)
;; ---------------------------------------------------------------------------

(deftest create-system-applies-sbr-test
  (let [sys (cluster/create-system "sbr-create"
              {:port 0
               :split-brain-resolver {:active-strategy :static-quorum
                                      :quorum-size 3
                                      :role "backend"}})]
    (try
      (let [cfg (.config (.settings sys))]
        (is (= "static-quorum" (.getString cfg "pekko.cluster.split-brain-resolver.active-strategy")))
        (is (= 3 (.getInt cfg "pekko.cluster.split-brain-resolver.static-quorum.quorum-size")))
        (is (= "backend" (.getString cfg "pekko.cluster.split-brain-resolver.static-quorum.role"))))
      (finally (ts/terminate-system sys)))))

(deftest extra-config-overrides-sbr-test
  ;; :extra-config has higher precedence than :split-brain-resolver. (Override to
  ;; keep-majority, which needs no required params, so the node still starts.)
  (let [sys (cluster/create-system "sbr-precedence"
              {:port 0
               :split-brain-resolver {:active-strategy :static-quorum :quorum-size 3}
               :extra-config "pekko.cluster.split-brain-resolver.active-strategy = keep-majority"})]
    (try
      (is (= "keep-majority"
             (.getString (.config (.settings sys))
                         "pekko.cluster.split-brain-resolver.active-strategy")))
      (finally (ts/terminate-system sys)))))

;; ---------------------------------------------------------------------------
;; Coordinated shutdown
;; ---------------------------------------------------------------------------

(deftest shutdown-phases-and-reasons-test
  (is (string? (cluster/shutdown-phases :before-actor-system-terminate)))
  (is (contains? cluster/shutdown-phases :cluster-leave))
  (is (instance? CoordinatedShutdown$Reason (cluster/shutdown-reasons :unknown)))
  (let [sys (core/actor-system "cs-ext")]
    (try
      (is (instance? CoordinatedShutdown (cluster/coordinated-shutdown sys)))
      (finally (core/shutdown-system sys)))))

(deftest add-shutdown-task-runs-on-shutdown-test
  (let [sys (core/actor-system "cs-task")
        ran (atom false)]
    (cluster/add-shutdown-task sys :before-actor-system-terminate "set-flag"
                               (fn [] (reset! ran true)))
    (let [fut (cluster/run-coordinated-shutdown sys)]
      (is (instance? CompletableFuture fut))
      (is (instance? Done (deref fut 15000 nil)))
      (is (true? @ran)))))

(deftest add-shutdown-task-awaits-completion-stage-test
  ;; A task returning a CompletionStage is awaited before the phase completes.
  (let [sys (core/actor-system "cs-stage")
        ran (atom false)]
    (cluster/add-shutdown-task sys :service-stop "async-task"
                               (fn []
                                 (reset! ran true)
                                 (CompletableFuture/completedFuture (Done/done))))
    ;; A full shutdown runs every phase in order, including :service-stop.
    (is (instance? Done (deref (cluster/run-coordinated-shutdown sys) 15000 nil)))
    (is (true? @ran))))

(deftest cancellable-shutdown-task-can-be-cancelled-test
  (let [sys (core/actor-system "cs-cancel")
        ran (atom false)
        c   (cluster/add-cancellable-shutdown-task sys :before-actor-system-terminate "cancel-me"
                                                    (fn [] (reset! ran true)))]
    (is (some? c))
    (is (true? (.cancel c)))
    (deref (cluster/run-coordinated-shutdown sys) 15000 nil)
    (is (false? @ran))))

(deftest add-jvm-shutdown-hook-returns-nil-test
  (let [sys (core/actor-system "cs-hook")]
    (try
      (is (nil? (cluster/add-jvm-shutdown-hook sys (fn [] :noop))))
      (finally (core/shutdown-system sys)))))

(deftest run-coordinated-shutdown-bad-reason-throws-test
  (let [sys (core/actor-system "cs-bad-reason")]
    (try
      (is (thrown? IllegalArgumentException
                   (cluster/run-coordinated-shutdown sys :not-a-reason)))
      (finally (core/shutdown-system sys)))))

(deftest add-shutdown-task-bad-phase-throws-test
  (let [sys (core/actor-system "cs-bad-phase")]
    (try
      (is (thrown? IllegalArgumentException
                   (cluster/add-shutdown-task sys :not-a-phase "x" (fn [] nil))))
      (finally (core/shutdown-system sys)))))
