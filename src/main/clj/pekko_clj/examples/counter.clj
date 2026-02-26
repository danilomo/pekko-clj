(ns pekko-clj.examples.counter
  (:require [pekko-clj.core :refer :all]))

(defactor counter
  "A simple counter actor."

  (init [args]
    {:count (or (:start args) 0)})

  (handle :inc
    (update state :count inc))

  (handle :dec
    (update state :count dec))

  (handle [:add n]
    (update state :count + n))

  (handle :get
    (reply (:count state)))

  (handle :reset
    {:count 0}))

(comment
  ;; REPL usage:
  (def sys (actor-system "counter-example"))
  (def c (spawn sys counter {:start 0}))

  (! c :inc)
  (! c :inc)
  (! c :inc)
  (! c [:add 10])
  (<! sys c :get)  ;; => 13

  (! c :reset)
  (<! sys c :get)  ;; => 0

  (.terminate sys))
