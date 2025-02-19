(ns pekko-clj.test
  (:import  [org.apache.pekko.pattern Patterns]
            [pekko_clj.actor FnWrapper])
  (:require [pekko-clj.core :refer [new-actor reply system spawn]]))

(declare manager)

(defn seconds [val]
  (java.time.Duration/ofSeconds val))

(def guardian-props
  {:pre-start
   (fn [this]
     (.scheduleOnce this (seconds 1) #(println "scheduled message!"))
     {:manager (spawn manager :none)})

   :function
   (fn [this msg]
     (.forward this (:manager @this) msg))})

(defn manager [this msg]
  (reply (.toUpperCase msg))
  :ok)

(def a (new-actor system guardian-props))

(.onComplete
 (Patterns/ask a "aaaaa" 1000)
 (FnWrapper/create #(println %))
 (.getDispatcher system))

