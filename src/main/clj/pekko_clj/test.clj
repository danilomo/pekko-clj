(ns pekko-clj.test
  (:require [pekko-clj.core :refer [spawn system]]))

(declare manager)

(def guardian-props {:function (fn [this msg]
                                 (let [manager (:manager @this)
                                       sender (.getSender this)]
                                   (.tell manager msg sender)
                                   @this))

                     :pre-start (fn [this]
                                  (let [manager (spawn (.getContext this) manager :ok)]
                                    {:manager manager}))})


(defn manager [_ msg]
  (println (str "Tengo fueme: " msg))
  :ok)

(def a (spawn system guardian-props))
(.tell a "aaaaa" nil)
