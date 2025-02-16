(ns pekko-clj.core
  (:require [clojure.core.match :as m])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.pattern Patterns]
           [pekko_clj.actor CljActor]))

(defn reply [src msg]
  (.tell (.getSender src) msg (.getSelf src)))

(defn spawn
  ([src func] (spawn src func (func)))
  ([src func initial]
   (.actorOf src (CljActor/create initial func))))

(defn actor-system []
  (ActorSystem/create "mysystem"))

(def system (actor-system))

(declare fb)

(defn fa [this msg]
  (m/match msg
    :ask (do
           (reply this @this)
           @this)
    :change [fb :none]
    (n :guard number?) (+ @this n)))

(defn fb [this msg]
  (m/match msg
    :ask (do
           (reply this "hello there")
           @this)))


(def actor (spawn system fa 0))

(.tell actor 5 nil)
(.tell actor :change nil)

(def v (Patterns/ask actor :ask 1000))

(.value v)

