(ns pekko-clj.stash-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core])
  (:import [org.apache.pekko.actor ActorSystem ActorRef]
           [scala.concurrent Await]
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
  (Await/result (core/<?> actor msg 3000) timeout-duration))

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
    (Thread/sleep 100)
    ;; Nothing processed yet
    (is (= [] (await-ask actor :get-processed)))
    ;; Become ready - unstashes all
    (core/! actor :ready)
    (Thread/sleep 100)
    ;; All messages should be processed in order
    (is (= [:msg1 :msg2 :msg3] (await-ask actor :get-processed)))))

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
    (Thread/sleep 100)
    ;; Make stashing actor ready
    (core/! stashing-actor :ready)
    (Thread/sleep 200)
    ;; Check that sender was preserved
    (let [senders (await-ask stashing-actor :get-senders)]
      (is (= 1 (count senders)))
      (is (= sender-actor (first senders))))))

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
    ;; Initially empty
    (is (= 0 (await-ask actor :get-size)))
    ;; Enable stashing
    (core/! actor :start-stashing)
    (Thread/sleep 50)
    ;; Stash some messages
    (core/! actor :stash-me)
    (core/! actor :stash-me)
    (core/! actor :stash-me)
    (Thread/sleep 100)
    (is (= 3 (await-ask actor :get-size)))
    ;; Disable stashing before unstashing
    (core/! actor :stop-stashing)
    (Thread/sleep 50)
    ;; Unstash all - messages won't be re-stashed
    (core/! actor :unstash-all)
    (Thread/sleep 100)
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
    (core/! actor :first)
    (Thread/sleep 50)
    ;; Second message processed directly
    (core/! actor :second)
    (Thread/sleep 50)
    (is (= [:second] (await-ask actor :get-processed)))
    ;; Unstash one - should process :first
    (core/! actor :unstash-one)
    (Thread/sleep 50)
    (is (= [:second :first] (await-ask actor :get-processed)))))

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
    (Thread/sleep 50)
    ;; Clear the stash
    (core/! actor :clear)
    (Thread/sleep 50)
    ;; Become ready and unstash
    (core/! actor :ready)
    (Thread/sleep 50)
    ;; Nothing should be processed - stash was cleared
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
      (Thread/sleep 100)
      (is (= [] (await-ask actor :get-processed)))
      ;; Ready - unstash
      (core/! actor :ready)
      (Thread/sleep 100)
      (is (= [:a :b :c] (await-ask actor :get-processed))))))
