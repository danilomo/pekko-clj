(ns pekko-clj.examples.become
  (:require [pekko-clj.core :refer :all]))

(declare sad)

(defactor happy
  (handle :mood
    (reply "I'm happy!")
    state)

  (handle :toggle
    (become sad state)))

(defactor sad
  (handle :mood
    (reply "I'm sad...")
    state)

  (handle :toggle
    (become happy state)))

(comment
  (def sys (actor-system "become-example"))
  (def a (spawn sys happy nil))

  (<! sys a :mood)    ;; => "I'm happy!"
  (! a :toggle)
  (<! sys a :mood)    ;; => "I'm sad..."
  (! a :toggle)
  (<! sys a :mood)    ;; => "I'm happy!"

  (.terminate sys))
