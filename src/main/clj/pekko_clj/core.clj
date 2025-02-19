(ns pekko-clj.core
  (:require [clojure.core.match :as m])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.pattern Patterns]
           [pekko_clj.actor CljActor]))


(defn new-actor
  ([src props] (.actorOf src (CljActor/create props)))
  ([src func initial]
   (.actorOf src (CljActor/create initial func))))

(defmacro spawn [& args]
  `(.spawn ~'this ~@args))

(defmacro ! [& args]
  `(.tell ~'this ~@args))

(defmacro reply [& args]
  `(.reply ~'this ~@args))

(defn actor-system []
  (ActorSystem/create "mysystem"))

(def system (actor-system))

(declare fb)

(defn fa [this msg]
  (m/match msg
    :ask (do
           (reply @this)
           @this)
    :change [fb :none]
    (n :guard number?) (+ @this n)))

(defn fb [this msg]
  (m/match msg
    :ask (do
           (reply "hello there")
           @this)))

(def actor (new-actor system fa 0))

(.tell actor 5 nil)
(.tell actor :change nil)

(def v (Patterns/ask actor :ask 1000))

(.value v)

(m/match :pata :pata 1 :peta 2)
