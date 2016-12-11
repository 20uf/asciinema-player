(ns asciinema.player.format.asciicast-v0
  (:require [schema.core :as s]
            [asciinema.vt :as vt]
            [asciinema.player.screen :as screen]))

(def Fragment vt/Fragment) ; TODO decouple from vt

(def Diff {(s/optional-key :cursor) {(s/optional-key :x) s/Num
                                     (s/optional-key :y) s/Num
                                     (s/optional-key :visible) s/Bool}
           (s/optional-key :lines) {(s/named s/Keyword "line number") [Fragment]}})

(def DiffFrame [(s/one s/Num "delay") (s/one Diff "diff")])

(def AsciicastV0 [DiffFrame])

(s/defrecord LegacyScreen
    [cursor :- {:x s/Num
                :y s/Num
                :visible s/Bool}
     lines :- {s/Num [Fragment]}])

(defn fix-line-diff-keys [line-diff]
  (into {} (map (fn [[k v]] [(js/parseInt (name k) 10) v]) line-diff)))

(defn reduce-v0-frame [[prev-time screen] [curr-time diff]]
  (let [diff (update diff :lines fix-line-diff-keys)]
    (vector (+ prev-time curr-time) (merge-with merge screen diff))))

(defn build-v0-frames [diffs]
  (let [screen (map->LegacyScreen {:lines (sorted-map)
                                   :cursor {:x 0 :y 0 :visible true}})]
    (reductions reduce-v0-frame [0 screen] diffs)))

(s/defn initialize-asciicast
  [asciicast :- AsciicastV0]
  (let [frame-0-lines (-> asciicast first last :lines)
        asciicast-width (->> frame-0-lines vals first (map #(count (first %))) (reduce +))
        asciicast-height (count frame-0-lines)]
    {:width asciicast-width
     :height asciicast-height
     :duration (reduce #(+ %1 (first %2)) 0 asciicast)
     :frames (build-v0-frames asciicast)}))

(extend-protocol screen/Screen
  LegacyScreen
  (lines [this]
    (vals (:lines this)))
  (cursor [this]
    (:cursor this)))
