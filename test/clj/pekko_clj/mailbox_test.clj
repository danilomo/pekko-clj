(ns pekko-clj.mailbox-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.mailbox :as mailbox]
            [pekko-clj.test-support :as ts :refer [eventually]])
  (:import [com.typesafe.config Config]))

;; Priority function: lower number = served first. Referenced by name from the
;; mailbox config, so it must be a top-level var.
(defn mbx-prio [msg]
  (case msg
    :high 0
    :low  10
    :gate 5
    50))

(def order (atom []))
(def started (promise))
(def gate (promise))

(core/defactor prio-actor
  "Blocks inside the :gate handler until `gate` is delivered, then records the order
   in which subsequently-queued messages are dequeued."
  (init [_] {})
  (handle :gate
    (deliver started true)
    @gate
    state)
  (handle msg
    (swap! order conj msg)
    state))

(deftest priority-mailbox-config-shape-test
  (let [^Config cfg (mailbox/priority-mailbox-config "m1" `mbx-prio)]
    (is (= "pekko_clj.actor.CljPriorityMailbox" (.getString cfg "m1.mailbox-type")))
    (is (= "pekko-clj.mailbox-test/mbx-prio" (.getString cfg "m1.priority-fn"))))
  ;; Accepts a var and a plain "ns/name" string too.
  (is (= "pekko-clj.mailbox-test/mbx-prio"
         (.getString ^Config (mailbox/priority-mailbox-config "m2" #'mbx-prio)
                     "m2.priority-fn")))
  (is (= "a.b/c"
         (.getString ^Config (mailbox/priority-mailbox-config "m3" "a.b/c")
                     "m3.priority-fn"))))

(deftest priority-mailbox-orders-by-priority-test
  (let [cfg (mailbox/priority-mailbox-config "prio-mbx" `mbx-prio)
        sys (core/actor-system "mbx" cfg)]
    (try
      (let [props (mailbox/with-mailbox (core/actor-props prio-actor) "prio-mbx")
            a     (core/spawn-props sys props)]
        (core/! a :gate)
        @started                    ; the actor is now blocked in the :gate handler
        (core/! a :low)             ; these two queue while the actor is busy
        (core/! a :high)
        (deliver gate true)         ; release: the mailbox dequeues by priority
        (is (eventually (= [:high :low] @order))))
      (finally (core/shutdown-system sys)))))
