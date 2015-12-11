(ns asciinema-player.vt
  (:refer-clojure :exclude [print])
  (:require-macros [asciinema-player.vt :refer [events]])
  (:require [asciinema-player.util :refer [adjust-to-range]]
            [cljs.core.match :refer-macros [match]]))


;; References:
;; http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
;; http://www.inwap.com/pdp10/ansicode.txt
;; http://manpages.ubuntu.com/manpages/lucid/man7/urxvt.7.html
;; http://en.wikipedia.org/wiki/ANSI_escape_code
;; http://vt100.net/emu/dec_ansi_parser
;; http://ttssh2.sourceforge.jp/manual/en/about/ctrlseq.html
;; http://real-world-systems.com/docs/ANSIcode.html
;; http://www.shaels.net/index.php/propterm/documents
;; http://vt100.net/docs/vt102-ug/chapter5.html

(def space 0x20)
(def default-attrs {})

(defn cell [ch char-attrs]
  [ch char-attrs])

(defn empty-cell [char-attrs]
  (cell space char-attrs))

(defn empty-line
  ([width] (empty-line width default-attrs))
  ([width char-attrs]
   (vec (repeat width (empty-cell char-attrs)))))

(defn empty-screen
  ([width height] (empty-screen width height default-attrs))
  ([width height char-attrs]
   (let [line (empty-line width char-attrs)]
     (vec (repeat height line)))))

(defn default-tabs [width]
  (apply sorted-set (range 8 width 8)))

(def initial-saved-cursor {:cursor {:x 0 :y 0}
                           :char-attrs {}
                           :origin-mode false
                           :auto-wrap-mode true})

(defn make-vt [width height]
  {:width width
   :height height
   :top-margin 0
   :bottom-margin (dec height)
   :parser {:state :ground
            :intermediate-chars []
            :param-chars []}
   :tabs (default-tabs width)
   :cursor {:x 0 :y 0 :visible true}
   :char-attrs {}
   :insert-mode false
   :auto-wrap-mode true
   :new-line-mode false
   :next-print-wraps false
   :origin-mode false
   :lines (empty-screen width height)
   :saved initial-saved-cursor})

;; helper functions

(defn show-cursor
  ([vt] (show-cursor vt true))
  ([vt show?]
   (assoc-in vt [:cursor :visible] show?)))

(defn set-margin [vt top bottom]
  (assoc vt :top-margin top :bottom-margin bottom))

(defn scroll-up [{:keys [width top-margin bottom-margin char-attrs] :as vt}]
  (update-in vt [:lines] (fn [lines]
                           (vec (concat
                                 (take top-margin lines)
                                 (subvec lines (inc top-margin) (inc bottom-margin))
                                 [(empty-line width char-attrs)]
                                 (drop (inc bottom-margin) lines))))))

(defn move-cursor-to-col [vt x]
  (-> vt
      (assoc-in [:cursor :x] x)
      (assoc :next-print-wraps false)))

(defn move-cursor-to-row [vt y]
  (-> vt
      (assoc-in [:cursor :y] y)
      (assoc :next-print-wraps false)))

(defn move-cursor-to-home [{:keys [origin-mode top-margin] :as vt}]
  (let [top (if origin-mode top-margin 0)]
    (-> vt
        (move-cursor-to-col 0)
        (move-cursor-to-row top))))

(defn move-cursor-down [{:keys [bottom-margin height] {y :y} :cursor :as vt}]
  (let [last-row (dec height)]
    (cond (= y bottom-margin) (scroll-up vt)
          (< y last-row) (move-cursor-to-row vt (inc y))
          :else vt)))

(defn switch-to-alternate-buffer [{:keys [primary-buffer width height char-attrs] :as vt}]
  (if-not primary-buffer
    (assoc vt
           :primary-buffer (:lines vt)
           :lines (empty-screen width height char-attrs))
    vt))

(defn switch-to-primary-buffer [{:keys [primary-buffer] :as vt}]
  (if primary-buffer
    (-> vt
        (assoc :lines primary-buffer)
        (dissoc :primary-buffer))
    vt))

(defn save-cursor [{{:keys [x y]} :cursor :keys [char-attrs origin-mode auto-wrap-mode] :as vt}]
  (assoc vt :saved {:cursor {:x x :y y}
                    :char-attrs char-attrs
                    :origin-mode origin-mode
                    :auto-wrap-mode auto-wrap-mode}))

(defn restore-cursor [{{:keys [cursor char-attrs origin-mode auto-wrap-mode]} :saved :as vt}]
  (-> vt
      (assoc :char-attrs char-attrs
             :origin-mode origin-mode
             :auto-wrap-mode auto-wrap-mode)
      (update-in [:cursor] merge cursor)))

(defn set-mode [vt intermediate param]
  (match [intermediate param]
         [nil 4] (assoc vt :insert-mode true)
         [nil 20] (assoc vt :new-line-mode true)
         [0x3f 6] (-> vt (assoc :origin-mode true) move-cursor-to-home)
         [0x3f 7] (assoc vt :auto-wrap-mode true)
         [0x3f 25] (show-cursor vt)
         [0x3f 47] (switch-to-alternate-buffer vt)
         [0x3f 1047] (switch-to-alternate-buffer vt)
         [0x3f 1048] (save-cursor vt)
         [0x3f 1049] (-> vt save-cursor switch-to-alternate-buffer)
         :else vt))

(defn reset-mode [vt intermediate param]
  (match [intermediate param]
         [nil 4] (assoc vt :insert-mode false)
         [nil 20] (assoc vt :new-line-mode false)
         [0x3f 6] (-> vt (assoc :origin-mode false) move-cursor-to-home)
         [0x3f 7] (assoc vt :auto-wrap-mode false)
         [0x3f 25] (show-cursor vt false)
         [0x3f 47] (switch-to-primary-buffer vt)
         [0x3f 1047] (switch-to-primary-buffer vt)
         [0x3f 1048] (restore-cursor vt)
         [0x3f 1049] (-> vt switch-to-primary-buffer restore-cursor)
         :else vt))

;; control functions

(defn scroll-down [{:keys [width top-margin bottom-margin char-attrs] :as vt}]
  (update-in vt [:lines] (fn [lines]
                           (vec (concat
                                 (take top-margin lines)
                                 [(empty-line width char-attrs)]
                                 (subvec lines top-margin bottom-margin)
                                 (drop (inc bottom-margin) lines))))))

(defn execute-bs [{{:keys [x]} :cursor :as vt}]
  (move-cursor-to-col vt (max (dec x) 0)))

(defn move-cursor-to-next-tab [{{:keys [x]} :cursor :keys [tabs width] :as vt} n]
  (let [n (dec n)
        right-margin (dec width)
        next-tabs (drop-while (partial >= x) tabs)
        new-x (nth next-tabs n right-margin)]
    (move-cursor-to-col vt new-x)))

(defn move-cursor-to-prev-tab [{{:keys [x]} :cursor :keys [tabs width] :as vt} n]
  (let [n (dec n)
        prev-tabs (take-while (partial > x) tabs)
        new-x (nth (reverse prev-tabs) n 0)]
    (move-cursor-to-col vt new-x)))

(defn execute-ht [vt]
  (move-cursor-to-next-tab vt 1))

(defn execute-cr [vt]
  (move-cursor-to-col vt 0))

(defn execute-lf [{:keys [new-line-mode] :as vt}]
  (let [vt (move-cursor-down vt)]
    (if new-line-mode
      (execute-cr vt)
      vt)))

(defn execute-nel [vt]
  (-> vt move-cursor-down execute-cr))

(defn execute-hts [{{x :x} :cursor :as vt}]
  (if (pos? x)
    (update-in vt [:tabs] conj x)
    vt))

(defn execute-ri [{:keys [top-margin] {y :y} :cursor :as vt}]
  (cond (= y top-margin) (scroll-down vt)
        (> y 0) (move-cursor-to-row vt (dec y))
        :else vt))

(defn execute-decaln [{:keys [width height] :as vt}]
  (assoc vt :lines (vec (repeat height (vec (repeat width [0x45 {}]))))))

(defn execute-sc [vt]
  (save-cursor vt))

(defn execute-rc [vt]
  (restore-cursor vt))

(defn split-coll [elem coll]
  (loop [coll coll
         parts []
         part []]
    (if-let [e (first coll)]
      (if (= e elem)
        (recur (rest coll) (conj parts part) [])
        (recur (rest coll) parts (conj part e)))
      (if (seq part)
        (conj parts part)
        parts))))

(defn reduce-param [chars]
  (let [digits (map #(- % 0x30) chars)
        components (map * (reverse digits) (iterate (partial * 10) 1))]
    (reduce + 0 components)))

(defn get-intermediate [vt n]
  (get-in vt [:parser :intermediate-chars n]))

(defn get-params [vt]
  (let [chars (get-in vt [:parser :param-chars])
        groups (split-coll 0x3b chars)]
    (map reduce-param groups)))

(defn get-param [vt n default]
  (let [v (nth (get-params vt) n 0)]
    (if (zero? v)
      default
      v)))

(defn execute-ich [{{:keys [x y]} :cursor :keys [width char-attrs] :as vt}]
  (let [n (get-param vt 0 1)]
    (update-in vt [:lines y] (fn [line]
                               (vec (take width (concat (take x line)
                                                        (repeat n [space char-attrs])
                                                        (drop x line))))))))

(defn execute-cuu [{:keys [top-margin] {:keys [y]} :cursor :as vt}]
  (let [n (get-param vt 0 1)
        new-y (if (< y top-margin)
                (max 0 (- y n))
                (max top-margin (- y n)))]
    (move-cursor-to-row vt new-y)))

(defn execute-cud [{{y :y} :cursor :keys [bottom-margin height] :as vt}]
  (let [n (get-param vt 0 1)
        new-y (if (> y bottom-margin)
                (min (dec height) (+ y n))
                (min bottom-margin (+ y n)))]
    (move-cursor-to-row vt new-y)))

(defn execute-cuf [{{x :x} :cursor :keys [width] :as vt}]
  (let [n (get-param vt 0 1)
        new-x (min (+ x n) (dec width))]
    (move-cursor-to-col vt new-x)))

(defn execute-cub [{{x :x} :cursor :keys [width] :as vt}]
  (let [n (get-param vt 0 1)
        new-x (max (- x n) 0)]
    (move-cursor-to-col vt new-x)))

(defn execute-cnl [vt]
  (-> vt
      execute-cud
      (move-cursor-to-col 0)))

(defn execute-cpl [vt]
  (-> vt
      execute-cuu
      (move-cursor-to-col 0)))

(defn execute-cha [{width :width :as vt}]
  (let [n (get-param vt 0 1)
        new-x (if (<= n width) (dec n) (dec width))]
    (move-cursor-to-col vt new-x)))

(defn top-limit [{:keys [origin-mode top-margin] :as vt}]
  (if origin-mode top-margin 0))

(defn bottom-limit [{:keys [origin-mode bottom-margin height] :as vt}]
  (if origin-mode bottom-margin (dec height)))

(defn adjust-y-to-limits [vt y]
  (let [top (top-limit vt)
        bottom (bottom-limit vt)]
    (adjust-to-range (+ top y) top bottom)))

(defn execute-cup [{:keys [width height] :as vt}]
  (let [ps1 (get-param vt 0 1)
        ps2 (get-param vt 1 1)
        new-x (adjust-to-range (dec ps2) 0 (dec width))
        new-y (adjust-y-to-limits vt (dec ps1))]
    (-> vt
        (assoc :next-print-wraps false)
        (move-cursor-to-col new-x)
        (move-cursor-to-row new-y))))

(defn execute-cht [vt]
  (let [n (get-param vt 0 1)]
    (move-cursor-to-next-tab vt n)))

(defn clear-line-right [line x char-attrs]
  (vec (concat (take x line)
               (repeat (- (count line) x) (empty-cell char-attrs)))))

(defn clear-to-end-of-screen [{{:keys [x y]} :cursor :keys [width height char-attrs] :as vt}]
  (update-in vt [:lines] (fn [lines]
                           (let [top-lines (take y lines)
                                 curr-line (clear-line-right (nth lines y) x char-attrs)
                                 bottom-lines (repeat (- height y 1) (empty-line width char-attrs))]
                             (vec (concat top-lines [curr-line] bottom-lines))))))

(defn clear-line-left [line x char-attrs]
  (vec (concat (repeat (inc x) (empty-cell char-attrs))
               (drop (inc x) line))))

(defn clear-to-beginning-of-screen [{{:keys [x y]} :cursor :keys [width height char-attrs] :as vt}]
  (update-in vt [:lines] (fn [lines]
                           (let [top-lines (repeat y (empty-line width char-attrs))
                                 curr-line (clear-line-left (nth lines y) x char-attrs)
                                 bottom-lines (drop (inc y) lines)]
                             (vec (concat top-lines [curr-line] bottom-lines))))))

(defn clear-screen [{:keys [width height char-attrs] :as vt}]
  (assoc-in vt [:lines] (empty-screen width height char-attrs)))

(defn execute-ed [vt]
  (let [n (get-param vt 0 0)]
    (condp = n
      0 (clear-to-end-of-screen vt)
      1 (clear-to-beginning-of-screen vt)
      2 (clear-screen vt)
      vt)))

(defn execute-el [{:keys [width height char-attrs] {:keys [x y]} :cursor :as vt}]
  (let [n (get-param vt 0 0)]
    (update-in vt [:lines y] (fn [line]
                               (condp = n
                                 0 (clear-line-right line x char-attrs)
                                 1 (clear-line-left line x char-attrs)
                                 2 (empty-line width char-attrs)
                                 line)))))

(defn execute-su [{:keys [width height char-attrs] :as vt}]
  (let [n (min (get-param vt 0 1) height)]
    (update-in vt [:lines] (fn [lines]
                             (vec (concat
                                   (drop n lines)
                                   (repeat n (empty-line width char-attrs))))))))

(defn execute-sd [{:keys [width height char-attrs] :as vt}]
  (let [n (min (get-param vt 0 1) height)]
    (update-in vt [:lines] (fn [lines]
                             (vec (concat
                                   (repeat n (empty-line width char-attrs))
                                   (take (- height n) lines)))))))

(defn execute-il [{:keys [width height char-attrs] {y :y} :cursor :as vt}]
  (let [n (min (get-param vt 0 1) (- height y))]
    (update-in vt [:lines] (fn [lines]
                             (vec (take height (concat
                                                (take y lines)
                                                (repeat n (empty-line width char-attrs))
                                                (drop y lines))))))))

(defn scroll-up-lines [lines n filler]
  (let [n (min n (count lines))]
    (concat
     (drop n lines)
     (repeat n filler))))

(defn execute-dl [{:keys [bottom-margin width height char-attrs] {y :y} :cursor :as vt}]
  (let [n (get-param vt 0 1)
        filler (empty-line width char-attrs)]
    (update-in vt [:lines] (fn [lines]
                             (vec (if (<= y bottom-margin)
                                    (concat
                                     (take y lines)
                                     (scroll-up-lines (subvec lines y (inc bottom-margin)) n filler)
                                     (drop (inc bottom-margin) lines))
                                    (concat
                                     (take y lines)
                                     (scroll-up-lines (drop y lines) n filler))))))))

(defn execute-dch [{{:keys [x y]} :cursor :keys [char-attrs] :as vt}]
  (let [n (get-param vt 0 1)]
    (update-in vt [:lines y] (fn [line]
                               (vec (concat
                                     (take x line)
                                     (drop (+ x n) line)
                                     (repeat n (empty-cell char-attrs))))))))

(defn execute-ctc [{{:keys [x]} :cursor :as vt}]
  (let [n (get-param vt 0 0)]
    (condp = n
      0 (update-in vt [:tabs] conj x)
      2 (update-in vt [:tabs] disj x)
      5 (update-in vt [:tabs] empty)
      vt)))

(defn execute-ech [{{:keys [x y]} :cursor :keys [width char-attrs] :as vt}]
  (let [n (min (get-param vt 0 1) (- width x))]
    (update-in vt [:lines y] (fn [line]
                               (vec (concat
                                     (take x line)
                                     (repeat n (empty-cell char-attrs))
                                     (drop (+ x n) line)))))))

(defn execute-cbt [vt]
  (let [n (get-param vt 0 1)]
    (move-cursor-to-prev-tab vt n)))

(defn execute-tbc [{{:keys [x]} :cursor :as vt}]
  (let [n (get-param vt 0 0)]
    (condp = n
      0 (update-in vt [:tabs] disj x)
      3 (update-in vt [:tabs] empty)
      vt)))

(defn execute-sm [vt]
  (let [intermediate (get-intermediate vt 0)]
    (reduce #(set-mode %1 intermediate %2) vt (get-params vt))))

(defn execute-rm [vt]
  (let [intermediate (get-intermediate vt 0)]
    (reduce #(reset-mode %1 intermediate %2) vt (get-params vt))))

(defn reset-attrs [vt]
  (update-in vt [:char-attrs] empty))

(defn set-attr [vt attr-name value]
  (assoc-in vt [:char-attrs attr-name] value))

(defn unset-attr [vt attr-name]
  (update-in vt [:char-attrs] dissoc attr-name))

(defn execute-sgr [vt]
  (let [params (or (seq (get-params vt)) [0])]
    (loop [vt vt
           [p1 p2 p3 & _ :as params] params]
      (if p1
        (match [p1 p2 p3]
               [0  _ _] (recur (reset-attrs vt) (rest params))
               [1  _ _] (recur (set-attr vt :bold true) (rest params))
               [3  _ _] (recur (set-attr vt :italic true) (rest params))
               [4  _ _] (recur (set-attr vt :underline true) (rest params))
               [5  _ _] (recur (set-attr vt :blink true) (rest params))
               [7  _ _] (recur (set-attr vt :inverse true) (rest params))
               [21 _ _] (recur (unset-attr vt :bold) (rest params))
               [23 _ _] (recur (unset-attr vt :italic) (rest params))
               [24 _ _] (recur (unset-attr vt :underline) (rest params))
               [25 _ _] (recur (unset-attr vt :blink) (rest params))
               [27 _ _] (recur (unset-attr vt :inverse) (rest params))
               [(fg :guard #(<= 30 % 37)) _ _] (recur (set-attr vt :fg (- fg 30)) (rest params))
               [38 5 (fg :guard some?)] (recur (set-attr vt :fg fg) (drop 3 params))
               [39 _ _] (recur (unset-attr vt :fg) (rest params))
               [(bg :guard #(<= 40 % 47)) _ _] (recur (set-attr vt :bg (- bg 40)) (rest params))
               [48 5 (bg :guard some?)] (recur (set-attr vt :bg bg) (drop 3 params))
               [49 _ _] (recur (unset-attr vt :bg) (rest params))
               :else (recur vt (rest params)))
        vt))))

(defn execute-vpa [vt]
  (let [n (get-param vt 0 1)
        new-y (adjust-y-to-limits vt (dec n))]
    (move-cursor-to-row vt new-y)))

(defn execute-decstr [{:keys [height] :as vt}]
  (if (= (get-intermediate vt 0) 0x21)
    (-> vt
        show-cursor
        (set-margin 0 (dec height))
        (assoc :insert-mode false
               :origin-mode false
               :char-attrs {}
               :saved initial-saved-cursor))
    vt))

(defn execute-decstbm [{:keys [height] :as vt}]
  (let [top (dec (get-param vt 0 1))
        bottom (dec (get-param vt 1 height))]
    (if (< -1 top bottom height)
      (-> vt
          (set-margin top bottom)
          move-cursor-to-home)
      vt)))

;; parser actions

(defn ignore [vt input]
  vt)

(defn replace-char [line x cell]
  (assoc-in line [x] cell))

(defn insert-char [line x cell]
  (vec (concat
        (take x line)
        [cell]
        (take (- (count line) x 1) (drop x line)))))

(defn wrap [{{:keys [y]} :cursor :keys [height] :as vt}]
  (let [vt (move-cursor-to-col vt 0)]
    (if (= height (inc y))
      (scroll-up vt)
      (move-cursor-to-row vt (inc y)))))

(defn do-print [{:keys [width height char-attrs insert-mode] {:keys [x y]} :cursor :as vt} input]
  (let [cell (cell input char-attrs)]
    (if (= width (inc x))
      (-> vt
          (assoc-in [:lines y x] cell)
          (assoc :next-print-wraps true))
      (let [f (if insert-mode insert-char replace-char)]
        (-> vt
            (update-in [:lines y] f x cell)
            (move-cursor-to-col (inc x)))))))

(defn print [{:keys [auto-wrap-mode next-print-wraps] :as vt} input]
  (if (and auto-wrap-mode next-print-wraps)
    (do-print (wrap vt) input)
    (do-print vt input)))

(defn execute [vt input]
  (if-let [action (condp = input
                    0x08 execute-bs
                    0x09 execute-ht
                    0x0a execute-lf
                    0x0b execute-lf
                    0x0c execute-lf
                    0x0d execute-cr
                    0x84 execute-lf
                    0x85 execute-nel
                    0x88 execute-hts
                    0x8d execute-ri
                    nil)]
    (action vt)
    vt))

(defn clear [vt input]
  (update-in vt [:parser] merge {:intermediate-chars [] :param-chars []}))

(defn collect [vt input]
  (update-in vt [:parser :intermediate-chars] conj input))

(defn param [vt input]
  (update-in vt [:parser :param-chars] conj input))

(defn esc-dispatch [vt input]
  (if (<= 0x40 input 0x5f)
    (execute vt (+ input 0x40))
    (condp = input
      0x37 (execute-sc vt)
      0x38 (condp = (get-intermediate vt 0)
             nil (execute-rc vt)
             0x23 (execute-decaln vt)
             vt)
      0x63 (make-vt (:width vt) (:height vt))
      vt)))

(defn csi-dispatch [vt input]
  (if-let [action (condp = input
                    0x40 execute-ich
                    0x41 execute-cuu
                    0x42 execute-cud
                    0x43 execute-cuf
                    0x44 execute-cub
                    0x45 execute-cnl
                    0x46 execute-cpl
                    0x47 execute-cha
                    0x48 execute-cup
                    0x49 execute-cht
                    0x4a execute-ed
                    0x4b execute-el
                    0x4c execute-il
                    0x4d execute-dl
                    0x50 execute-dch
                    0x53 execute-su
                    0x54 execute-sd
                    0x57 execute-ctc
                    0x58 execute-ech
                    0x5a execute-cbt
                    0x60 execute-cha
                    0x61 execute-cuf
                    0x64 execute-vpa
                    0x65 execute-cuu
                    0x66 execute-cup
                    0x67 execute-tbc
                    0x68 execute-sm
                    0x6c execute-rm
                    0x6d execute-sgr
                    0x70 execute-decstr
                    0x72 execute-decstbm
                    nil)]
    (action vt)
    vt))

(defn hook [vt input]
  vt)

(defn put [vt input]
  vt)

(defn unhook [vt input]
  vt)

(defn osc-start [vt input]
  vt)

(defn osc-put [vt input]
  vt)

(defn osc-end [vt input]
  vt)

;; end actions

(def c0-prime? (events :0x00-0x17 0x19 :0x1C-0x1F))

(def anywhere-state {(events 0x18 0x1A :0x80-0x8F :0x91-0x97 0x99 0x9A) {:action execute, :transition :ground}
                     (events 0x9C) {:transition :ground}
                     (events 0x1B) {:transition :escape}
                     (events 0x98 0x9E 0x9F) {:transition :sos-pm-apc-string}
                     (events 0x90) {:transition :dcs-entry}
                     (events 0x9D) {:transition :osc-string}
                     (events 0x9B) {:transition :csi-entry}})

(def states {
  :ground {
    c0-prime? {:action execute}
    (events :0x20-0x7F :0xA0-0xFF) {:action print}
  }
  :escape {
    :on-enter clear
    c0-prime? {:action execute}
    (events :0x20-0x2F) {:action collect, :transition :escape-intermediate}
    (events :0x30-0x4F :0x51-0x57 0x59 0x5A 0x5C :0x60-0x7E) {:action esc-dispatch, :transition :ground}
    (events 0x5B) {:transition :csi-entry}
    (events 0x5D) {:transition :osc-string}
    (events 0x50) {:transition :dcs-entry}
    (events 0x58 0x5E 0x5F) {:transition :sos-pm-apc-string}
    (events 0x7f) {:action ignore}
  }
  :escape-intermediate {
    c0-prime? {:action execute}
    (events :0x20-0x2F) {:action collect}
    (events :0x30-0x7E) {:action esc-dispatch, :transition :ground}
    (events 0x7f) {:action ignore}
  }
  :csi-entry {
    :on-enter clear
    c0-prime? {:action execute}
    (events :0x40-0x7E) {:action csi-dispatch, :transition :ground}
    (events :0x30-0x39 0x3B) {:action param, :transition :csi-param}
    (events :0x3C-0x3F) {:action collect, :transition :csi-param}
    (events 0x3A) {:transition :csi-ignore}
    (events :0x20-0x2F) {:action collect, :transition :csi-intermediate}
    (events 0x7f) {:action ignore}
  }
  :csi-param {
    c0-prime? {:action execute}
    (events :0x30-0x39 0x3B) {:action param}
    (events 0x3A :0x3C-0x3F) {:transition :csi-ignore}
    (events :0x20-0x2F) {:action collect, :transition :csi-intermediate}
    (events :0x40-0x7E) {:action csi-dispatch, :transition :ground}
    (events 0x7f) {:action ignore}
  }
  :csi-intermediate {
    c0-prime? {:action execute}
    (events :0x20-0x2F) {:action collect}
    (events :0x40-0x7E) {:action csi-dispatch, :transition :ground}
    (events :0x30-0x3F) {:transition :csi-ignore}
    (events 0x7f) {:action ignore}
  }
  :csi-ignore {
    c0-prime? {:action execute}
    (events :0x20-0x3F) {:action ignore}
    (events :0x40-0x7E) {:transition :ground}
    (events 0x7f) {:action ignore}
  }
  :dcs-entry {
    :on-enter clear
    c0-prime? {:action ignore}
    (events :0x20-0x2F) {:action collect, :transition :dcs-intermediate}
    (events 0x3A) {:transition :dcs-ignore}
    (events :0x30-0x39 0x3B) {:action param, :transition :dcs-param}
    (events :0x3C-0x3F) {:action collect, :transition :dcs-param}
    (events :0x40-0x7E) {:transition :dcs-passthrough}
    (events 0x7f) {:action ignore}
  }
  :dcs-param {
    c0-prime? {:action ignore}
    (events :0x20-0x2F) {:action collect, :transition :dcs-intermediate}
    (events :0x30-0x39 0x3B) {:action param}
    (events 0x3A :0x3C-0x3F) {:transition :dcs-ignore}
    (events :0x40-0x7E) {:transition :dcs-passthrough}
    (events 0x7f) {:action ignore}
  }
  :dcs-intermediate {
    c0-prime? {:action ignore}
    (events :0x20-0x2F) {:action collect}
    (events :0x30-0x3F) {:transition :dcs-ignore}
    (events :0x40-0x7E) {:transition :dcs-passthrough}
    (events 0x7f) {:action ignore}
  }
  :dcs-passthrough {
    :on-enter hook
    c0-prime? {:action put}
    (events :0x20-0x7E) {:action put}
    (events 0x7f) {:action ignore}
    :on-exit unhook
  }
  :dcs-ignore {
    c0-prime? {:action ignore}
    (events :0x20-0x7f) {:action ignore}
  }
  :osc-string {
    :on-enter osc-start
    c0-prime? {:action ignore}
    (events :0x20-0x7F) {:action osc-put}
    (events 0x07) {:transition :ground} ; 0x07 is xterm non-ANSI variant of transition to :ground - THIS WON'T HAPPEN BECAUSE OF 0x07 IN ANYWHERE, REMOVE?
    :on-exit osc-end
  }
  :sos-pm-apc-string {
    c0-prime? {:action ignore}
    (events :0x20-0x7F) {:action ignore}
  }
})

(defn- get-transition [rules input]
  (some (fn [[pred cfg]] (when (pred input) cfg)) rules))

(defn parse [current-state input]
  (let [current-state-cfg (get states current-state)
        transition (or (get-transition anywhere-state input)
                       (get-transition current-state-cfg (if (>= input 0xa0) 0x41 input)))]
    (if transition
      (let [transition-action (:action transition)]
        (if-let [new-state (:transition transition)]
          (let [new-state-cfg (get states new-state)
                exit-action (:on-exit current-state-cfg)
                entry-action (:on-enter new-state-cfg)
                actions (remove nil? [exit-action transition-action entry-action])]
            [new-state actions])
          [current-state (if transition-action [transition-action] [])])))))

(defn execute-actions [vt actions input]
  (reduce (fn [vt f] (f vt input)) vt actions))

(defn feed-one [{{old-state :state} :parser :as vt} input]
  (let [[new-state actions] (parse old-state input)]
    (-> vt (assoc-in [:parser :state] new-state)
        (execute-actions actions input))))

(defn feed [vt inputs]
  (reduce (fn [vt input] (feed-one vt input)) vt inputs))

(defn feed-str [vt str]
  (let [codes (map #(.charCodeAt % 0) str)]
    (feed vt codes)))

(defn compact-line
  "Joins together all neighbouring cells having the same color attributes."
  [line]
  (let [[cell & cells] line]
    (loop [segments []
           chars [(first cell)]
           attrs (last cell)
           cells cells]
      (if-let [[char new-attrs] (first cells)]
        (if (= new-attrs attrs)
          (recur segments (conj chars char) attrs (rest cells))
          (recur (conj segments [(apply js/String.fromCharCode chars) attrs]) [char] new-attrs (rest cells)))
        (conj segments [(apply js/String.fromCharCode chars) attrs])))))

(defn compact-lines [lines]
  (map compact-line lines))
