(ns zetawar.app
  (:require
   [cognitect.transit :as transit]
   [datascript.core :as d]
   [goog.crypt.base64 :as base64]
   [lzw]
   [taoensso.timbre :as log]
   [zetawar.data :as data]
   [zetawar.db :refer [e find-by qe qes qess]]
   [zetawar.game :as game]
   [zetawar.players :as players]
   [zetawar.util :as util :refer [breakpoint inspect]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; DB Accessors

(defn root [db]
  (find-by db :app/game))

(defn current-game [db]
  (:app/game (root db)))

(defn current-game-id [db]
  (:game/id (current-game db)))

(defn selected-hex [db]
  (let [{:keys [app/selected-q app/selected-r]} (root db)]
    [selected-q selected-r]))

(defn targeted-hex [db]
  (let [{:keys [app/targeted-q app/targeted-r]} (root db)]
    [targeted-q targeted-r]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Game state encoding

;; TODO: move to encoding or serialization ns
;; TODO: document UTF-8 hack

(defn encode-game-state [game-state]
  (let [writer (transit/writer :json)]
    (-> (transit/write writer game-state)
        js/lzwEncode
        js/encodeURIComponent
        js/unescape
        (base64/encodeString true))))

(defn decode-game-state [encoded-game-state]
  (let [reader (transit/reader :json)
        transit-game-state (-> encoded-game-state
                               (base64/decodeString true)
                               js/escape
                               js/decodeURIComponent
                               js/lzwDecode)]
    (transit/read reader transit-game-state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Game setup

(defn create-players! [{:as app-ctx :keys [conn players]}]
  (let [factions (qess '[:find ?f
                         :where
                         [_  :app/game ?g]
                         [?g :game/factions ?f]]
                       @conn)]
    ;; TODO: cleanup (relocate?) player stopping
    ;; Stop existing players (when starting new games)
    (when players
      (doseq [[_ player] @players]
        (when player
          (players/stop player))))
    (doseq [{:as faction :keys [faction/ai faction/color]} factions]
      (let [player-type (if ai ::players/reference-ai ::players/human)
            player (players/new-player app-ctx player-type color)]
        (d/transact! conn [{:db/id (e faction)
                            :faction/player-type player-type}])
        (players/start player)
        (swap! players assoc color player)))))

(defn start-new-game!
  ([{:as app-ctx :keys [conn players]} scenario-id]
   (start-new-game! app-ctx data/rulesets data/maps data/scenarios scenario-id))
  ([{:as app-ctx :keys [conn players]} rulesets map-defs scenario-defs scenario-id]
   (let [game (current-game @conn)]
     (when game
       (d/transact conn [[:db.fn/retractEntity (e game)]]))
     (let [scenario-def (scenario-defs scenario-id)
           game-id (game/load-scenario! conn rulesets map-defs scenario-def)
           db @conn
           app-eid (or (some-> (root db) e) -101)
           game (game/game-by-id db game-id)
           turn-stepping (= (game/faction-count db game)
                            (game/ai-faction-count db game))]
       (d/transact! conn [{:db/id app-eid
                           :app/game [:game/id game-id]
                           :app/hide-win-message false
                           :app/ai-turn-stepping turn-stepping
                           :app/ui-language :en}])
       ;; Skip player creation for tests
       (when players
         (create-players! app-ctx))))))

(defn load-encoded-game-state!
  ([{:as app-ctx :keys [conn players]} encoded-game-state]
   (load-encoded-game-state! app-ctx data/rulesets data/maps data/scenarios encoded-game-state))
  ([{:as app-ctx :keys [conn players]} rulesets map-defs scenario-defs encoded-game-state]
   (let [game-state (decode-game-state encoded-game-state)
         game-id (game/load-game-state! conn
                                        rulesets
                                        map-defs
                                        scenario-defs
                                        game-state)
         db @conn
         game (game/game-by-id db game-id)
         turn-stepping (= (game/faction-count db game)
                          (game/ai-faction-count db game))]
     (d/transact! conn [{:db/id -1
                         :app/game [:game/id game-id]
                         :app/ai-turn-stepping turn-stepping
                         :app/ui-language :en}])
     ;; Skip player creation for tests
     (when players
       (create-players! app-ctx)))))

;; TODO: put URL in paste buffer automatically
(defn set-url-game-state! [db]
  (let [encoded-game-state (->> (current-game db)
                                (game/get-game-state db)
                                encode-game-state)]
    (set! js/window.location.hash encoded-game-state)))
