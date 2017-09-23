(ns zetawar.views
  (:require
   [cljsjs.clipboard]
   [cljsjs.react-bootstrap]
   [clojure.string :as string]
   [datascript.core :as d]
   [posh.reagent :as posh]
   [reagent.core :as r :refer [with-let]]
   [taoensso.timbre :as log]
   [zetawar.data :as data]
   [zetawar.db :refer [e qe]]
   [zetawar.events.ui :as events.ui]
   [zetawar.game :as game]
   [zetawar.players :as players]
   [zetawar.site :as site]
   [zetawar.subs :as subs]
   [zetawar.tiles :as tiles]
   [zetawar.util :refer [breakpoint inspect only oonly]]
   [zetawar.views.common :refer [footer navbar]]))

(defn tile-border [{:as view-ctx :keys [conn]} q r]
  (let [[x y] (tiles/offset->pixel q r)]
    [:g {:id (str "border-" q "," r)}
     (cond
       ;; Selected
       @(subs/selected? conn q r)
       [:image {:x x :y y
                :width tiles/width :height tiles/height
                :xlink-href (site/prefix "/images/game/borders/selected.png")}]

       ;; Enemy unit targeted
       (and @(subs/targeted? conn q r)
            @(subs/enemy-at? conn q r))
       [:image {:x x :y y
                :width tiles/width :height tiles/height
                :xlink-href (site/prefix "/images/game/borders/targeted-enemy.png")}]

       ;; Friend unit targeted (for repair)
       (and @(subs/targeted? conn q r)
            @(subs/friend-at? conn q r))
       [:image {:x x :y y
                :width tiles/width :height tiles/height
                :xlink-href (site/prefix "/images/game/borders/targeted-friend.png")}]

       ;; Terrain targeted
       @(subs/targeted? conn q r)
       [:image {:x x :y y
                :width tiles/width :height tiles/height
                :xlink-href (site/prefix "/images/game/borders/selected.png")}])]))

(defn unit-image [unit]
  (let [color-name (-> unit game/unit-color name)]
    ;; TODO: return placeholder if terrain image is not found
    (some-> unit
            (get-in [:unit/type :unit-type/image])
            (string/replace "COLOR" color-name))))

(defn board-unit [{:as view-ctx :keys [conn dispatch]} q r]
  (when-let [unit @(subs/unit-at conn q r)]
    (let [[x y] (tiles/offset->pixel q r)
          image (unit-image unit)]
      [:g {:id (str "unit-" (e unit))}
       [:image {:x x :y y
                :width tiles/width :height tiles/height
                :xlink-href (site/prefix "/images/game/" image)
                :on-click #(dispatch [::events.ui/select-hex q r])}]
       (when (:unit/capturing unit)
         [:image {:x x :y y
                  :width tiles/width :height tiles/height
                  :xlink-href (site/prefix "/images/game/capturing.gif")}])
       [:image {:x x :y y
                :width tiles/width :height tiles/height
                :xlink-href (site/prefix "/images/game/health/" (:unit/count unit) ".png")}]])))

(defn tile-mask [{:as view-ctx :keys [conn]} q r]
  (let [[x y] (tiles/offset->pixel q r)
        show (or
              ;; No unit selected and tile contains current unit with no actions
              (and (not @(subs/unit-selected? conn))
                   @(subs/current-unit-at? conn q r)
                   (not @(subs/unit-can-act? conn q r)))

              ;; Unit selected and tile is a valid attack, repair, or move target
              (and @(subs/unit-selected? conn)
                   (not @(subs/selected? conn q r))
                   (not @(subs/enemy-in-range-of-selected? conn q r))
                   (not (and @(subs/repairable-friend-in-range-of-selected? conn q r)
                             @(subs/selected-can-field-repair? conn)
                             @(subs/has-repairable-armor-type? conn q r)))
                   (not @(subs/valid-destination-for-selected? conn q r))))]
    [:image {:visibility (if show "visible" "hidden")
             :x x :y y
             :width tiles/width :height tiles/height
             :xlink-href (site/prefix "/images/game/mask.png")}]))

(defn terrain-image [terrain]
  (let [color-name (-> terrain
                       (get-in [:terrain/owner :faction/color])
                       (or :none)
                       name)]
    ;; TODO: return placeholder if terrain image is not found
    (some-> terrain
            (get-in [:terrain/type :terrain-type/image])
            (string/replace "COLOR" color-name))))

(defn terrain-tile [view-ctx terrain q r]
  (let [[x y] (tiles/offset->pixel q r)
        image (terrain-image terrain)]
    [:image {:x x :y y
             :width tiles/width :height tiles/height
             :xlink-href (site/prefix "/images/game/" image)}]))

(defn tile [{:as view-ctx :keys [dispatch]} terrain]
  (let [{:keys [terrain/q terrain/r]} terrain]
    ^{:key (str q "," r)}
    [:g {:on-click #(dispatch [::events.ui/select-hex q r])
         :on-mouse-enter #(dispatch [::events.ui/hover-hex-enter q r])
         :on-mouse-leave #(dispatch [::events.ui/hover-hex-leave q r])}
     [terrain-tile view-ctx terrain q r]
     [tile-border view-ctx q r]
     [board-unit view-ctx q r]
     [tile-mask view-ctx q r]]))

(defn tiles [{:as view-ctx :keys [conn]}]
  (into [:g]
        (for [terrain @(subs/terrains conn)]
          [tile view-ctx terrain])))

(defn board [{:as view-ctx :keys [conn]}]
  [:svg#board {:width @(subs/map-width-px conn)
               :height @(subs/map-height-px conn)}
   [tiles view-ctx]])

(defn faction-credits [{:as view-ctx :keys [conn translate]}]
  (let [{:keys [faction/credits]} @(subs/current-faction conn)
        {:keys [map/credits-per-base]} @(subs/game-map conn)
        income @(subs/current-income conn)]
    [:p#faction-credits
     [:strong (str credits " " (translate @(subs/ui-language conn) :credits-label))]
     [:span.text-muted.pull-right
      (str "+" income)]]))

(defn copy-url-link [{:as view-ctx :keys [conn translate]}]
  (let [clipboard (atom nil)
        text-fn (fn [] js/window.location)]
    (r/create-class
     {:component-did-mount
      (fn [this]
        (reset! clipboard (js/Clipboard. (r/dom-node this) #js {"text" text-fn})))
      :component-will-unmount
      (fn [this]
        (.destroy @clipboard)
        (reset! clipboard nil))
      :reagent-render
      (fn [this]
        [:a {:href "#" :on-click #(.preventDefault %)}
         (translate @(subs/ui-language conn) :copy-game-url-link)])})))

(defn end-turn-alert [{:as view-ctx :keys [conn dispatch translate]}]
  [:> js/ReactBootstrap.Modal {:show @(subs/show-end-turn-alert? conn)
                               :on-hide #(dispatch [::events.ui/hide-end-turn-alert])}
   [:> js/ReactBootstrap.Modal.Body
    (translate @(subs/ui-language conn) :end-turn-alert)]
   [:> js/ReactBootstrap.Modal.Footer
    [:div.btn.btn-default {:on-click (fn [e]
                                       (.preventDefault e)
                                       (dispatch [::events.ui/end-turn])
                                       (dispatch [::events.ui/hide-end-turn-alert]))}
     (translate @(subs/ui-language conn) :end-turn-confirm)]
    [:div.btn.btn-default {:on-click #(dispatch [::events.ui/hide-end-turn-alert])}
     (translate @(subs/ui-language conn) :cancel-button)]]])

(defn language-picker [{:as view-ctx :keys [conn dispatch translate]}]
  (with-let [hide-picker (fn [ev]
                           (when ev (.preventDefault ev))
                           (dispatch [::events.ui/hide-language-picker]))
             selected-language (r/atom :en)
             select-language #(reset! selected-language (.-target.value %))
             set-language (fn [ev]
                               (.preventDefault ev)
                               (dispatch [::events.ui/change-language @selected-language])
                               (dispatch [::events.ui/hide-language-picker]))]
    [:> js/ReactBootstrap.Modal {:show @(subs/picking-language? conn)
                                 :on-hide hide-picker}
     [:> js/ReactBootstrap.Modal.Header {:close-button true}
      [:> js/ReactBootstrap.Modal.Title
       (translate @(subs/ui-language conn) :select-language-label)]]
     [:> js/ReactBootstrap.Modal.Body
      [:form
       [:div.form-group
        [:label {:for "language"}
         (translate @(subs/ui-language conn) :available-languages-label)]
        (into [:select.form-control {:id "language"
                                     :value (or @selected-language
                                                "")
                                     :on-change select-language}]
              (for [[language-code {:keys [language-name]}] data/dicts]
                [:option {:value language-code}
                 language-name]))
        [:> js/ReactBootstrap.Modal.Footer
         [:button.btn.btn-primary {:on-click set-language}
          (translate @(subs/ui-language conn) :save-button)]
         [:button.btn.btn-default {:on-click hide-picker}
          (translate @(subs/ui-language conn) :cancel-button)]]]]]]))

(defn faction-status [{:as view-ctx :keys [conn dispatch translate]}]
  (let [{:keys [app/show-copy-link]} @(subs/app conn)
        {:keys [game/round]} @(subs/game conn)
        base-count @(subs/current-base-count conn)]
    [:div#faction-status
     ;; TODO: make link red
     [:a {:href "#" :on-click (fn [e]
                                (.preventDefault e)
                                (if @(subs/available-moves-left? conn)
                                  (dispatch [::events.ui/show-end-turn-alert])
                                  (dispatch [::events.ui/end-turn])))}
      (translate @(subs/ui-language conn) :end-turn-link)]
     (when show-copy-link
       [:span " · " [copy-url-link view-ctx]])
     [:div.pull-right
      [:a {:href "#"
           :on-click (fn [e]
                       (.preventDefault e)
                       (dispatch [::events.ui/show-new-game-settings]))}
       (translate @(subs/ui-language conn) :new-game-link)]
      " · "
      (str (translate @(subs/ui-language conn) :round-label) " " round)
      " · "
      [:a {:href "#" :on-click (fn [e]
                                (.preventDefault e)
                                (if @(subs/available-moves-left? conn)
                                  (dispatch [::events.ui/show-language-picker])
                                  (dispatch [::events.ui/end-turn])))}
       (translate @(subs/ui-language conn) :language-label)]]]))

(defn faction-actions [{:as view-ctx :keys [conn dispatch translate]}]
  ;; TODO: replace query with something from subs ns
  (let [[round current-color] (-> @(posh/q '[:find ?round ?current-color
                                             :where
                                             [?g :game/round ?round]
                                             [?g :game/current-faction ?f]
                                             [?f :faction/color ?current-color]]
                                           conn)
                                  first)
        {:keys [faction/credits]} @(subs/current-faction conn)]
    [:div#faction-actions
     (when @(subs/selected-can-move-to-targeted? conn)
       [:p
        [:button.btn.btn-primary.btn-block
         {:on-click #(dispatch [::events.ui/move-selected-unit])}
         (translate @(subs/ui-language conn) :move-unit-button)]])
     (when @(subs/selected-can-build? conn)
       [:p
        [:button.btn.btn-primary.btn-block
         {:on-click #(dispatch [::events.ui/show-unit-picker])}
         (translate @(subs/ui-language conn) :build-unit-button)]])
     (when @(subs/selected-can-attack-targeted? conn)
       [:p
        [:button.btn.btn-danger.btn-block
         {:on-click #(dispatch [::events.ui/attack-targeted])}
         (translate @(subs/ui-language conn) :attack-unit-button)]])
     (when @(subs/selected-can-repair? conn)
       [:p
        [:button.btn.btn-success.btn-block
         {:on-click #(dispatch [::events.ui/repair-selected])}
         (translate @(subs/ui-language conn) :repair-unit-button)]])
     (when @(subs/selected-can-repair-targeted? conn)
       [:p
        [:button.btn.btn-success.btn-block
         {:on-click #(dispatch [::events.ui/repair-targeted])}
         (translate @(subs/ui-language conn) :field-repair-button)]])
     (when @(subs/selected-can-capture? conn)
       [:p
        [:button.btn.btn-primary.btn-block
         {:on-click #(dispatch [::events.ui/capture-selected])}
         (translate @(subs/ui-language conn) :capture-base-button)]])
     ;; TODO: cleanup conditionals
     ;; TODO: make help text a separate component
     (when (not (or @(subs/selected-can-move? conn)
                    @(subs/selected-can-build? conn)
                    @(subs/selected-can-attack? conn)
                    @(subs/selected-can-repair? conn)
                    @(subs/selected-can-capture? conn)))
       [:p.hidden-xs.hidden-sm
        (translate @(subs/ui-language conn) :select-unit-or-base-tip)])
     (when (and
            (or @(subs/selected-can-move? conn)
                @(subs/selected-can-attack? conn)
                @(subs/selected-can-repair? conn))
            (not
             (or @(subs/selected-can-move-to-targeted? conn)
                 @(subs/selected-can-attack-targeted? conn)
                 @(subs/selected-can-repair-targeted? conn))))
       [:p.hidden-xs.hidden-sm
        (translate @(subs/ui-language conn) :select-target-or-destination-tip)])
     ;; TODO: only display when starting faction is active
     (when (and (= round 1)
                (not @(subs/selected-hex conn)))
       [:p.hidden-xs.hidden-sm
        {:dangerouslySetInnerHTML {:__html (translate @(subs/ui-language conn) :multiplayer-tip)}}])]))

(defn faction-list [{:as view-ctx :keys [conn dispatch translate]}]
  (into [:ul.list-group]
        (for [faction @(subs/factions conn)]
          (let [faction-eid (e faction)
                color-label (-> faction
                                :faction/color
                                name
                                (str "-name")
                                keyword)
                active (= faction-eid @(subs/current-faction-eid conn))
                li-class (if active
                           "list-group-item active"
                           "list-group-item")
                icon-class (if (:faction/ai faction)
                             "fa fa-fw fa-laptop clickable"
                             "fa fa-fw fa-user clickable")]
            [:li {:class li-class}
             (translate @(subs/ui-language conn) color-label)
             " "
             (when active
               [:span.fa.fa-angle-double-left
                {:aria-hidden true}])
             [:div.pull-right
              [:span
               {:class icon-class
                :aria-hidden true
                :on-click #(dispatch [::events.ui/configure-faction faction])
                :title (translate @(subs/ui-language conn) :configure-faction-tip)}]]]))))

(defn status-info [{:as view-ctx :keys [conn translate]}]
  [:div
   (let [[sel-q sel-r] @(subs/selected-hex conn)
         [tar-q tar-r] @(subs/targeted-hex conn)
         [sel-mc sel-at sel-ar] @(subs/selected-terrain-effects conn)
         [tar-mc tar-at tar-ar] @(subs/targeted-terrain-effects conn)
         [hover-q hover-r] @(subs/hover-hex conn)]
     [:span
      (translate @(subs/ui-language conn) :selected-label)
      (if sel-q
        [:span
         [:abbr {:title (translate @(subs/ui-language conn) :tile-coordinates-label)
                 :style {:cursor "inherit"}}
          (str sel-q "," sel-r)]
         (if sel-mc ;; If selected doesn't contain a unit
           [:span
            " ("
            [:abbr {:title (translate @(subs/ui-language conn) :terrain-effects-label)
                    :style {:cursor "inherit"}}
             (str sel-mc "," sel-at "," sel-ar)]
            ")"])]
        [:span " -"])
      " • "
      (translate @(subs/ui-language conn) :targeted-label)
      (if tar-q
        [:span
         [:abbr {:title (translate @(subs/ui-language conn) :tile-coordinates-label)
                 :style {:cursor "inherit"}}
          (str tar-q "," tar-r)]
         " ("
         [:abbr {:title (translate @(subs/ui-language conn) :terrain-effects-label)
                 :style {:cursor "inherit"}}
          (str tar-mc "," tar-at "," tar-ar)]
         ")"]
        [:span " -"])
      [:span.hidden-xs.hidden-sm
       " • "
       (translate @(subs/ui-language conn) :hover-tile-location)
       (if hover-q
         (str hover-q "," hover-r)
         "-")]])])

(def armor-type-abbrevs
  {:unit-type.armor-type/personnel "P"
   :unit-type.armor-type/armored "A"})

;; TODO: cleanup unit-picker
(defn unit-picker [{:as view-ctx :keys [conn dispatch translate]}]
  (let [unit-types @(subs/available-unit-types conn)
        cur-faction @(subs/current-faction conn)
        color (name (:faction/color cur-faction))
        hide-picker #(dispatch [::events.ui/hide-unit-picker])]
    [:> js/ReactBootstrap.Modal {:show @(subs/picking-unit? conn)
                                 :on-hide hide-picker}
     [:> js/ReactBootstrap.Modal.Header {:close-button true}
      [:> js/ReactBootstrap.Modal.Title
       (translate @(subs/ui-language conn) :build-title)]]
     [:> js/ReactBootstrap.Modal.Body
      [:> js/ReactBootstrap.Table {:bordered true
                                   :striped true
                                   :condensed true
                                   :hover true}
       [:thead>tr
        [:th ""]
        [:th.text-center {:style {:width "12%"}}
         (translate :armor-type-label)]
        [:th.text-center {:style {:width "12%"}}
         (translate :movement-label)]
        [:th.text-center {:style {:width "12%"}}
         (translate :armor-label)]
        [:th.text-center {:style {:width "12%"}}
         (translate :range-label)]
        [:th.text-center {:style {:width "12%"}}
         (translate :attack-label)]
        [:th.text-center {:style {:width "12%"}}
         (translate :field-repair-label)]]
       (into [:tbody]
             (for [unit-type unit-types]
               (let [;; TODO: replace with unit-type-image
                     color-or-grey (if (:affordable unit-type)
                                     color
                                     "unavailable")
                     image (->> (string/replace (:unit-type/image unit-type)
                                                "COLOR" color-or-grey)
                                (str "/images/game/"))
                     media-class (if (:affordable unit-type)
                                   "media text-left"
                                   "media text-left text-muted")
                     {:keys [unit-type/id
                             unit-type/cost
                             unit-type/movement
                             unit-type/armor
                             unit-type/armor-type
                             unit-type/can-capture
                             unit-type/capturing-armor
                             unit-type/min-range
                             unit-type/max-range]} unit-type
                     unit-label (-> id
                                    name
                                    (str "-name")
                                    keyword)
                     armor-type-abbrev (armor-type-abbrevs armor-type)]
                 [:tr.text-center.clickable
                  {:on-click #(when (:affordable unit-type)
                                (dispatch [::events.ui/hide-unit-picker])
                                (dispatch [::events.ui/build-unit id]))}
                  [:td>div {:class media-class}
                   [:div.media-left.media-middle
                    [:img {:src image}]]
                   [:div.media-body
                    [:h4.media-heading (translate @(subs/ui-language conn) unit-label)]
                    (str (translate :unit-cost-label) cost)]]
                  [:td (case armor-type
                         :unit-type.armor-type/personnel
                         [:abbr {:title (translate :personnel-name)
                                 :style {:cursor "inherit"}}
                          armor-type-abbrev]

                         :unit-type.armor-type/armored
                         [:abbr {:title (translate :armored-name)
                                 :style {:cursor "inherit"}}
                          armor-type-abbrev])]
                  [:td movement]
                  [:td (if can-capture
                         [:abbr {:title (str (translate :while-capturing-label)
                                             capturing-armor)
                                 :style {:cursor "inherit"}}
                          armor]
                         [:abbr {:title (translate :unit-cannot-capture-bases-label)
                                 :style {:cursor "inherit"}}
                          armor])]
                  [:td min-range "-" max-range]
                  (into [:td]
                        (for [unit-strength (:unit-type/strengths unit-type)]
                          (let [{:keys [unit-strength/armor-type
                                        unit-strength/attack]} unit-strength
                                armor-type-abbrev (armor-type-abbrevs armor-type)]
                            [:div (str armor-type-abbrev ": " attack)])))
                  [:td (string/join ", "
                                    (for [can-repair (:unit-type/can-repair unit-type)]
                                      (armor-type-abbrevs can-repair "")))]])))]]
     [:> js/ReactBootstrap.Modal.Footer
      [:div.btn.btn-default {:on-click hide-picker}
       (translate :cancel-button)]]]))

(defn faction-settings [{:as views-ctx :keys [conn dispatch translate]}]
  (with-let [faction (subs/faction-to-configure conn)
             faction-color (subs/faction-color-name faction)
             selected-player-type (r/atom nil)
             hide-settings (fn [ev]
                             (when ev (.preventDefault ev))
                             (dispatch [::events.ui/hide-faction-settings]))
             select-player-type #(reset! selected-player-type (.-target.value %))
             set-player-type (fn [ev]
                               (.preventDefault ev)
                               (when-let [player-type-id (->> (or @selected-player-type :human)
                                                              (keyword 'zetawar.players))]
                                 (reset! selected-player-type nil)
                                 (dispatch [::events.ui/set-faction-player-type @faction player-type-id]))
                               (dispatch [::events.ui/hide-faction-settings]))]
    [:> js/ReactBootstrap.Modal {:show (some? @faction)
                                 :on-hide hide-settings}
     [:> js/ReactBootstrap.Modal.Header {:close-button true}
      [:> js/ReactBootstrap.Modal.Title
       (translate @(subs/ui-language conn) :configure-faction-title-prefix)
       (translate @(subs/ui-language conn) @faction-color)]]
     [:> js/ReactBootstrap.Modal.Body
      [:form
       [:div.form-group
        [:label {:for "player-type"}
         (translate @(subs/ui-language conn) :player-type-label)]
        (into [:select.form-control {:id "player-type"
                                     :value (or @selected-player-type
                                                (some-> @faction :faction/player-type name)
                                                "")
                                     :on-change select-player-type}]
              (for [[player-type-id {:keys [description ai]}] players/player-types]
                [:option {:value (name player-type-id)}
                 description]))
        [:> js/ReactBootstrap.Modal.Footer
         [:button.btn.btn-primary {:on-click set-player-type}
          (translate @(subs/ui-language conn) :save-button)]
         [:button.btn.btn-default {:on-click hide-settings}
          (translate @(subs/ui-language conn) :cancel-button)]]]]]]))

;; TODO: move default-scenario-id to data ns?
(defn new-game-settings [{:as view-ctx :keys [conn dispatch translate]}]
  (with-let [default-scenario-id :sterlings-aruba-multiplayer
             selected-scenario-id (r/atom default-scenario-id)
             hide-settings (fn [ev]
                             (when ev (.preventDefault ev))
                             (dispatch [::events.ui/hide-new-game-settings]))
             select-scenario #(reset! selected-scenario-id (keyword (.-target.value %)))
             start-new-game #(do
                               (.preventDefault %)
                               (dispatch [::events.ui/start-new-game @selected-scenario-id])
                               (reset! selected-scenario-id default-scenario-id)
                               (dispatch [::events.ui/hide-new-game-settings]))]
    [:> js/ReactBootstrap.Modal {:show @(subs/configuring-new-game? conn)
                                 :on-hide hide-settings}
     [:> js/ReactBootstrap.Modal.Header {:close-button true}
      [:> js/ReactBootstrap.Modal.Title
       (translate @(subs/ui-language conn) :new-game-title)]]
     [:> js/ReactBootstrap.Modal.Body
      [:form
       [:div.form-group
        [:label {:for "scenario-id"}
         (translate @(subs/ui-language conn) :scenario-label)]
        (into [:select.form-control {:id "scenario-id"
                                     :selected (some-> @selected-scenario-id name)
                                     :on-change select-scenario}]
              (for [[scenario-id {:keys [description]}] data/scenarios]
                [:option {:value (name scenario-id)}
                 description]))
        [:> js/ReactBootstrap.Modal.Footer
         [:button.btn.btn-primary {:on-click start-new-game}
          (translate @(subs/ui-language conn) :start-button)]
         [:button.btn.btn-default {:on-click hide-settings}
          (translate @(subs/ui-language conn) :cancel-button)]]]]]]))

(defn alert [{:as view-ctx :keys [conn dispatch]}]
  (let [{:keys [app/alert-message app/alert-type]} @(subs/app conn)
        alert-class (str "alert alert-" (some-> alert-type name))]
    (when alert-message
      [:div.row
       [:div.col-md-12
        [:div {:class alert-class}
         [:button.close {:type :button
                         :aria-label "Close"
                         :on-click #(dispatch [::events.ui/hide-alert])}
          [:span {:aria-hidden true} "×"]]
         alert-message]]])))

(defn game-interface [view-ctx]
  [:div.row
   [:div.col-md-2
    [faction-credits view-ctx]
    [faction-list view-ctx]
    [faction-actions view-ctx]]
   [:div.col-md-10
    [faction-status view-ctx]
    [board view-ctx]
    [status-info view-ctx]]])

(defn app-root [{:as view-ctx :keys [conn dispatch translate]}]
  [:div
   [new-game-settings view-ctx]
   [faction-settings view-ctx]
   [unit-picker view-ctx]
   [language-picker view-ctx]
   [end-turn-alert view-ctx]
   ;; TODO: break win dialog out into it's own component
   ;; TODO: add continue + start new game buttons to win dialog
   [:> js/ReactBootstrap.Modal {:show @(subs/show-win-message? conn)
                                :on-hide #(dispatch [::events.ui/hide-win-message])}
    [:> js/ReactBootstrap.Modal.Header
     [:> js/ReactBootstrap.Modal.Title
      (translate @(subs/ui-language conn) :win-title)]]
    [:> js/ReactBootstrap.Modal.Body
     {:dangerouslySetInnerHTML {:__html (translate @(subs/ui-language conn) :win-body)}}]
    [:> js/ReactBootstrap.Modal.Footer
     [:button.btn.btn-default {:on-click #(dispatch [::events.ui/hide-win-message])}
      (translate @(subs/ui-language conn) :close-button)]]]
   (navbar "Game")
   [:div.container
    [alert view-ctx]
    [game-interface view-ctx]]
   (footer)])
