(ns pekko-clj.examples.chess
  (:require [pekko-clj.core :refer [! actor-system defactor parent sender spawn]]))

;; --- Player actor ---

(defactor player
  (handle [:move move]
    (when (:my-turn state)
      (! (parent) move))
    state)

  (handle [:status status]
    ((:callback state) [:status status])
    (if (= :ok status)
      (assoc state :my-turn false)
      state))

  (handle [:game-start color]
    ((:callback state) [:game-start color])
    state)

  (handle [:your-turn move]
    ((:callback state) [:your-turn move])
    (assoc state :my-turn true)))

;; --- Game actor (running state) ---

(defactor game
  (init [{:keys [white-ref black-ref white-cb black-cb]}]
    (let [white (spawn player {:color :white :my-turn true :callback white-cb})
          black (spawn player {:color :black :my-turn false :callback black-cb})]
      (! white-ref white)
      (! black-ref black)
      (! white [:game-start :white])
      (! black [:game-start :black])
      {:white white :black black :game nil #_"(new-game)"}))

  (handle move
    (let [{:keys [white black game]} state
          turn (or (:turn game) 0)
          current (if (even? turn) white black)
          next    (if (even? turn) black white)
          result  nil #_"(make-move game move)"]
      (if (nil? result)
        (do (! current [:status :not-ok]) state)
        (do (! current [:status :ok])
            (! next [:your-turn move])
            (assoc state :game result))))))

;; --- Lobby actor ---

;; Pairs joiners two at a time: the first to arrive plays white, the second black.
;; The keys handed to `game` are the ones its `init` destructures — a lobby that
;; invents its own names would leave every binding nil and silently start a game
;; with no players.
(defactor lobby
  (init [_] :empty)

  (handle [:join callback]
    (if (= :empty state)
      {:white-ref (sender) :white-cb callback}
      (do
        (spawn game (assoc state :black-ref (sender) :black-cb callback))
        :empty))))

(comment
  ;; The chess example requires the tenma-chess game logic to fully work.
  ;; This demonstrates the actor structure and message flow.
  (def sys (actor-system "chess"))
  (def l (spawn sys lobby))
  ;; Players would join via (<?> l [:join callback-fn])
  (.terminate sys))
