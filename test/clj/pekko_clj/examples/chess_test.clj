(ns pekko-clj.examples.chess-test
  "H19: the chess example is a stubbed skeleton (the game-logic calls are `#_`ed
   out), but the actor wiring around the stubs is real and should run. It did not:
   the lobby handed `game` a map keyed :first/:second while `game`'s init
   destructured white-ref/black-ref/white-cb/black-cb, so every binding was nil —
   two players spawned with no callback, and their refs sent to nil. Nothing threw;
   the example simply did nothing observable."
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.core :as core]
            [pekko-clj.examples.chess :as chess]
            [pekko-clj.test-support :refer [eventually]]))

;; A joiner has to be an actor: the lobby pairs players by `(sender)`, and `game`
;; sends each one its player ActorRef back.
(core/defactor joiner
  (init [args] args)
  (handle [:go lobby-ref]
    (core/! lobby-ref [:join (:cb state)])
    state)
  (handle player-ref
    ((:on-ref state) player-ref)
    state))

(deftest lobby-pairs-two-joiners-into-a-running-game
  (let [sys (core/actor-system "chess-example-test")]
    (try
      (let [seen (atom [])
            refs (atom [])
            mk (fn [tag]
                 (core/spawn sys joiner
                             {:cb (fn [msg] (swap! seen conj [tag msg]))
                              :on-ref (fn [r] (swap! refs conj [tag r]))}))
            lobby (core/spawn sys chess/lobby)
            p1 (mk :p1)
            p2 (mk :p2)]
        (core/! p1 [:go lobby])
        (core/! p2 [:go lobby])
        ;; Two joins fill the lobby, which spawns the game; the game spawns both
        ;; player actors and starts them. Which joiner gets white depends on which
        ;; :join the lobby sees first, so assert set-wise.
        (is (eventually (= 2 (count @seen)))
            "both callbacks fired — the game really started")
        (is (= #{[:game-start :white] [:game-start :black]} (set (map second @seen)))
            "one player is white, the other black")
        (is (eventually (= 2 (count @refs)))
            "each joiner received its player ActorRef back from the game")
        (is (= #{:p1 :p2} (set (map first @refs))))
        (is (every? #(instance? org.apache.pekko.actor.ActorRef %) (map second @refs))))
      (finally
        (core/shutdown-system sys 10000)))))
