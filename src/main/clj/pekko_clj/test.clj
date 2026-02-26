(ns pekko-clj.test
  (:require [pekko-clj.core :refer :all]))

(defn seconds [val]
  (java.time.Duration/ofSeconds val))

(defactor manager
  (handle msg
    (reply (.toUpperCase msg))))

(defactor guardian
  (init [_]
    (schedule-once (seconds 1) #(println "scheduled message!"))
    {:manager (spawn manager)})

  (handle msg
    (forward (:manager state) msg)
    state))

;; Usage:
;; (def sys (actor-system "test"))
;; (def a (spawn sys guardian))
;; (<! sys a "hello")  ;; => "HELLO"
;; (.terminate sys)
