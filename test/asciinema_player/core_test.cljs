(ns asciinema-player.core-test
  (:require-macros [cljs.test :refer (is deftest testing)])
  (:require [cljs.test]
            [asciinema-player.core :as c]))

(deftest make-player-test
  (let [make-player #(c/make-player "https://..." %)]
    (let [player (make-player {})]
      (is (= (:width player) nil))
      (is (= (:height player) nil))
      (is (= (:asciicast-url player) "https://..."))
      (is (= (:duration player) 0))
      (is (= (:start-at player) 0))
      (is (= (:current-time player) 0))
      (is (= (:theme player) "asciinema"))
      (is (= (:font-size player) "small"))
      (is (= (:speed player) 1))
      (is (= (:auto-play player) nil))
      (is (= (:loop player) nil)))
    (let [player (make-player {:width 100 :height 40 :speed 3 :theme "tango" :font-size "big" :loop true :auto-play true :author "me"})]
      (is (= (:width player) 100))
      (is (= (:height player) 40))
      (is (= (:speed player) 3))
      (is (= (:theme player) "tango"))
      (is (= (:font-size player) "big"))
      (is (= (:loop player) true))
      (is (= (:auto-play player) true))
      (is (= (:author player) "me")))
    (let [player (make-player {:start-at 15})]
      (is (= (:start-at player) 15))
      (is (= (:current-time player) 15))
      (is (= (:auto-play player) nil)))
    (let [player (make-player {:start-at 15 :auto-play false})]
      (is (= (:start-at player) 15))
      (is (= (:current-time player) 15))
      (is (= (:auto-play player) false)))
    (let [player (make-player {:snapshot [[["foo" {}] ["bar" {:fg 1}]] [["baz" {:bg 2}]]]})]
      (is (= (:lines player) [[["foo" {}] ["bar" {:fg 1}]] [["baz" {:bg 2}]]])))))

(deftest update-screen-test
  (let [frame-fn #(update-in % [:cursor :y] inc)
        state {:lines {2 :a}
               :cursor {:y 5}
               :frame-fn frame-fn}
        changes {:lines {1 :b 3 :d}
                 :cursor {:x 1 :y 2 :visible true}
                 :unknown true}]
    (is (= (c/update-screen state changes) {:lines {1 :b 3 :d}
                                            :cursor {:x 1 :y 3 :visible true}
                                            :frame-fn frame-fn}))))

(deftest new-position-test
  (is (= (c/new-position 2 5 -3) 0.0))
  (is (= (c/new-position 2 5 -1) 0.2))
  (is (= (c/new-position 2 5 4) 1.0))
  (is (= (c/new-position 2 5 2) 0.8)))

(deftest screen-state-at-test
  (let [frames [[5 :foo] [3 :bar] [4 :baz]]]
    (is (= (c/screen-state-at frames 0) nil))
    (is (= (c/screen-state-at frames 1) nil))
    (is (= (c/screen-state-at frames 2) nil))
    (is (= (c/screen-state-at frames 3) nil))
    (is (= (c/screen-state-at frames 4) nil))
    (is (= (c/screen-state-at frames 5) :foo))
    (is (= (c/screen-state-at frames 6) :foo))
    (is (= (c/screen-state-at frames 7) :foo))
    (is (= (c/screen-state-at frames 8) :bar))
    (is (= (c/screen-state-at frames 9) :bar))
    (is (= (c/screen-state-at frames 10) :bar))
    (is (= (c/screen-state-at frames 11) :bar))
    (is (= (c/screen-state-at frames 12) :baz))
    (is (= (c/screen-state-at frames 13) :baz))))

(deftest next-frames-test
  (let [frames [[2 :a] [4 :b] [6 :c]]]
    (is (= (c/next-frames frames 0) [[2 :a] [4 :b] [6 :c]]))
    (is (= (c/next-frames frames 1) [[1 :a] [4 :b] [6 :c]]))
    (is (= (c/next-frames frames 2) [[4 :b] [6 :c]]))
    (is (= (c/next-frames frames 3) [[3 :b] [6 :c]]))
    (is (= (c/next-frames frames 4) [[2 :b] [6 :c]]))
    (is (= (c/next-frames frames 5) [[1 :b] [6 :c]]))
    (is (= (c/next-frames frames 6) [[6 :c]]))
    (is (= (c/next-frames frames 11) [[1 :c]]))
    (is (= (c/next-frames frames 12) []))
    (is (= (c/next-frames frames 13) []))))

(deftest fix-diffs-test
  (let [diffs [[1.2 {:lines {:0 [["foo" {:fg 1}]] :1 [["bar" {:bg 2}]]} :cursor {:x 1 :y 2 :visible false}}]]]
    (is (= (c/fix-diffs diffs) [[1.2 {:lines {0 [["foo" {:fg 1}]] 1 [["bar" {:bg 2}]]} :cursor {:x 1 :y 2 :visible false}}]]))))
