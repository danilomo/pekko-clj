(ns pekko-clj.stash-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pekko-clj.core :as core]
            [pekko-clj.test-support :refer [eventually]])
  (:import [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def timeout-duration (Duration/create 5 "seconds"))

(def ^:dynamic *system* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "stash-test")]
    (try
      (binding [*system* sys]
        (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each actor-system-fixture)

(defn await-ask
  "Send a message and block for the reply via core/<?>"
  [actor msg]
  (core/<! actor msg 3000))

;; ---------------------------------------------------------------------------
;; Tests: Stashing
;; ---------------------------------------------------------------------------

(deftest stash-and-unstash-all
  (let [processed (atom [])
        ;; Actor that stashes messages until :ready, then unstashes
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (let [state @this]
                                (cond
                                  (= msg :ready)
                                  (do
                                    (core/unstash-all)
                                    {:ready true})

                                  (= msg :get-processed)
                                  (do
                                    (.reply this @processed)
                                    state)

                                  (:ready state)
                                  (do
                                    (swap! processed conj msg)
                                    state)

                                  :else
                                  (do
                                    (core/stash)
                                    state)))))
                :state {:ready false}})]
    ;; Send messages before ready
    (core/! actor :msg1)
    (core/! actor :msg2)
    (core/! actor :msg3)
    ;; All three are stashed (FIFO before this ask), so nothing is processed yet.
    (is (= [] (await-ask actor :get-processed)))
    ;; Become ready - unstashes all
    (core/! actor :ready)
    ;; All messages should be processed in order.
    (is (eventually (= [:msg1 :msg2 :msg3] (await-ask actor :get-processed))))))

(deftest stash-preserves-sender
  (let [received-senders (atom [])
        stashing-actor (core/new-actor
                        *system*
                        {:function (fn [this msg]
                                     (binding [core/*current-actor* this]
                                       (let [state @this]
                                         (cond
                                           (= msg :ready)
                                           (do
                                             (core/unstash-all)
                                             {:ready true})

                                           (= msg :get-senders)
                                           (do
                                             (.reply this @received-senders)
                                             state)

                                           (:ready state)
                                           (do
                                             (swap! received-senders conj (.senderRef this))
                                             state)

                                           :else
                                           (do
                                             (core/stash)
                                             state)))))
                         :state {:ready false}})
        ;; A sender actor that forwards messages
        sender-actor (core/new-actor
                      *system*
                      {:function (fn [this msg]
                                   (binding [core/*current-actor* this]
                                     (when (= msg :send-to-stashing)
                                       (core/! stashing-actor :payload)
                                       (.reply this :sent))
                                     nil))
                       :state nil})]
    ;; Sender sends to stashing actor
    (is (= :sent (await-ask sender-actor :send-to-stashing)))
    ;; Make stashing actor ready (unstashes the payload).
    (core/! stashing-actor :ready)
    ;; Check that the original sender was preserved on the unstashed message.
    (is (eventually (= 1 (count (await-ask stashing-actor :get-senders)))))
    (is (= sender-actor (first (await-ask stashing-actor :get-senders))))))

(deftest stash-size-tracking
  (let [actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (let [state @this]
                                (cond
                                  ;; Only stash when in stashing mode
                                  (and (= msg :stash-me) (:stashing state))
                                  (do (core/stash) state)

                                  (= msg :start-stashing)
                                  {:stashing true}

                                  (= msg :stop-stashing)
                                  {:stashing false}

                                  (= msg :get-size)
                                  (do (.reply this (core/stash-size)) state)

                                  (= msg :unstash-all)
                                  (do (core/unstash-all) state)

                                  ;; Don't stash when not in stashing mode
                                  :else state))))
                :state {:stashing false}})]
    ;; All sends and asks share one mailbox (FIFO), so the asks observe the
    ;; expected state without any sleeps.
    (is (= 0 (await-ask actor :get-size)))
    (core/! actor :start-stashing)
    (core/! actor :stash-me)
    (core/! actor :stash-me)
    (core/! actor :stash-me)
    (is (= 3 (await-ask actor :get-size)))
    ;; Disable stashing, then unstash all (messages won't be re-stashed).
    (core/! actor :stop-stashing)
    (core/! actor :unstash-all)
    (is (= 0 (await-ask actor :get-size)))))

(deftest unstash-single-message
  (let [processed (atom [])
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (let [state @this]
                                (cond
                                  (= msg :unstash-one)
                                  (do
                                    (core/unstash)
                                    state)

                                  (= msg :get-processed)
                                  (do
                                    (.reply this @processed)
                                    state)

                                  (:ready state)
                                  (do
                                    (swap! processed conj msg)
                                    state)

                                  :else
                                  (do
                                    (core/stash)
                                    {:ready true})))))
                :state {:ready false}})]
    ;; First message gets stashed and actor becomes ready
    (core/! actor :first)  ; stashed; actor becomes ready
    (core/! actor :second) ; processed directly
    (is (= [:second] (await-ask actor :get-processed)))
    ;; Unstash one - should process :first
    (core/! actor :unstash-one)
    (is (eventually (= [:second :first] (await-ask actor :get-processed))))))

(deftest clear-stash-discards-messages
  (let [processed (atom [])
        actor (core/new-actor
               *system*
               {:function (fn [this msg]
                            (binding [core/*current-actor* this]
                              (let [state @this]
                                (cond
                                  (= msg :clear)
                                  (do
                                    (core/clear-stash)
                                    state)

                                  (= msg :ready)
                                  (do
                                    (core/unstash-all)
                                    {:ready true})

                                  (= msg :get-processed)
                                  (do
                                    (.reply this @processed)
                                    state)

                                  (:ready state)
                                  (do
                                    (swap! processed conj msg)
                                    state)

                                  :else
                                  (do
                                    (core/stash)
                                    state)))))
                :state {:ready false}})]
    ;; Stash some messages
    (core/! actor :msg1)
    (core/! actor :msg2)
    ;; Clear the stash, then become ready and unstash (nothing left).
    (core/! actor :clear)
    (core/! actor :ready)
    ;; Nothing should be processed - stash was cleared (all FIFO, no wait needed).
    (is (= [] (await-ask actor :get-processed)))))

(deftest stash-with-defactor
  (let [processed (atom [])]
    (core/defactor stashing-actor
      (init [_] {:ready false})
      (handle :ready
        (core/unstash-all)
        {:ready true})
      (handle :get-processed
        (core/reply @processed))
      (handle msg
        (if (:ready state)
          (do
            (swap! processed conj msg)
            state)
          (do
            (core/stash)
            state))))

    (let [actor (core/spawn *system* stashing-actor nil)]
      ;; Stash messages
      (core/! actor :a)
      (core/! actor :b)
      (core/! actor :c)
      (is (= [] (await-ask actor :get-processed)))
      ;; Ready - unstash
      (core/! actor :ready)
      (is (eventually (= [:a :b :c] (await-ask actor :get-processed)))))))
