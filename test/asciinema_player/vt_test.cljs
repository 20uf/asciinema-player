(ns asciinema-player.vt-test
  (:require-macros [cljs.test :refer (is deftest testing)])
  (:require [cljs.test]
            [asciinema-player.vt :as vt]))

(defn test-event [initial-state input expected-state expected-actions]
  (is (= (vt/parse initial-state input) [expected-state expected-actions])))

(defn test-high-events
  ([initial-state] (test-high-events initial-state []))
  ([initial-state exit-actions]
   (doseq [input (range 0x80 (inc 0x8f))]
     (test-event initial-state input :ground (concat exit-actions [vt/execute])))

   (test-event initial-state 0x90 :dcs-entry (concat exit-actions [vt/clear]))

   (doseq [input (range 0x91 (inc 0x97))]
     (test-event initial-state input :ground (concat exit-actions [vt/execute])))

   (test-event initial-state 0x98 :sos-pm-apc-string exit-actions)

   (doseq [input (range 0x99 (inc 0x9a))]
     (test-event initial-state input :ground (concat exit-actions [vt/execute])))

   (test-event initial-state 0x9b :csi-entry (concat exit-actions [vt/clear]))
   (test-event initial-state 0x9c :ground exit-actions)
   (test-event initial-state 0x9d :osc-string (concat exit-actions [vt/osc-start]))
   (test-event initial-state 0x9e :sos-pm-apc-string exit-actions)
   (test-event initial-state 0x9f :sos-pm-apc-string exit-actions)))

(deftest parse-test
  (testing "all"
    (doseq [state (keys vt/states)
            input (range (inc 0x9f))]
      (is (not= (vt/parse state input) nil))))

  (testing "ground"
    (doseq [input (range 0x00 (inc 0x1a))]
      (test-event :ground input :ground [vt/execute]))

    (test-event :ground 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :ground input :ground [vt/execute]))

    (doseq [input (range 0x20 (inc 0x7f))]
      (test-event :ground input :ground [vt/print]))

    (test-high-events :ground))

  (testing "escape"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :escape input :escape [vt/execute]))

    (test-event :escape 0x18 :ground [vt/execute])
    (test-event :escape 0x19 :escape [vt/execute])

    (test-event :escape 0x1a :ground [vt/execute])
    (test-event :escape 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :escape input :escape [vt/execute]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :escape input :escape-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x4f))]
      (test-event :escape input :ground [vt/esc-dispatch]))

    (test-event :escape 0x50 :dcs-entry [vt/clear])

    (doseq [input (range 0x51 (inc 0x57))]
      (test-event :escape input :ground [vt/esc-dispatch]))

    (test-event :escape 0x58 :sos-pm-apc-string [])
    (test-event :escape 0x59 :ground [vt/esc-dispatch])
    (test-event :escape 0x5a :ground [vt/esc-dispatch])
    (test-event :escape 0x5b :csi-entry [vt/clear])
    (test-event :escape 0x5c :ground [vt/esc-dispatch])
    (test-event :escape 0x5d :osc-string [vt/osc-start])
    (test-event :escape 0x5e :sos-pm-apc-string [])
    (test-event :escape 0x5f :sos-pm-apc-string [])

    (doseq [input (range 0x60 (inc 0x7e))]
      (test-event :escape input :ground [vt/esc-dispatch]))

    (test-event :escape 0x7f :escape [vt/ignore])

    (test-high-events :escape))

  (testing "escape-intermediate"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :escape-intermediate input :escape-intermediate [vt/execute]))

    (test-event :escape-intermediate 0x18 :ground [vt/execute])
    (test-event :escape-intermediate 0x19 :escape-intermediate [vt/execute])
    (test-event :escape-intermediate 0x1a :ground [vt/execute])
    (test-event :escape-intermediate 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :escape-intermediate input :escape-intermediate [vt/execute]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :escape-intermediate input :escape-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x7e))]
      (test-event :escape-intermediate input :ground [vt/esc-dispatch]))

    (test-event :escape-intermediate 0x7f :escape-intermediate [vt/ignore])

    (test-high-events :escape-intermediate))

  (testing "csi-entry"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :csi-entry input :csi-entry [vt/execute]))

    (test-event :csi-entry 0x18 :ground [vt/execute])
    (test-event :csi-entry 0x19 :csi-entry [vt/execute])
    (test-event :csi-entry 0x1a :ground [vt/execute])
    (test-event :csi-entry 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :csi-entry input :csi-entry [vt/execute]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :csi-entry input :csi-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x39))]
      (test-event :csi-entry input :csi-param [vt/param]))

    (test-event :csi-entry 0x3a :csi-ignore [])
    (test-event :csi-entry 0x3b :csi-param [vt/param])

    (doseq [input (range 0x3c (inc 0x3f))]
      (test-event :csi-entry input :csi-param [vt/collect]))

    (doseq [input (range 0x40 (inc 0x7e))]
      (test-event :csi-entry input :ground [vt/csi-dispatch]))

    (test-event :csi-entry 0x7f :csi-entry [vt/ignore])

    (test-high-events :csi-entry))

  (testing "csi-param"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :csi-param input :csi-param [vt/execute]))

    (test-event :csi-param 0x18 :ground [vt/execute])
    (test-event :csi-param 0x19 :csi-param [vt/execute])
    (test-event :csi-param 0x1a :ground [vt/execute])
    (test-event :csi-param 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :csi-param input :csi-param [vt/execute]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :csi-param input :csi-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x39))]
      (test-event :csi-param input :csi-param [vt/param]))

    (test-event :csi-param 0x3a :csi-ignore [])
    (test-event :csi-param 0x3b :csi-param [vt/param])

    (doseq [input (range 0x3c (inc 0x3f))]
      (test-event :csi-param input :csi-ignore []))

    (doseq [input (range 0x40 (inc 0x7e))]
      (test-event :csi-param input :ground [vt/csi-dispatch]))

    (test-event :csi-param 0x7f :csi-param [vt/ignore])

    (test-high-events :csi-param))

  (testing "csi-intermediate"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :csi-intermediate input :csi-intermediate [vt/execute]))

    (test-event :csi-intermediate 0x18 :ground [vt/execute])
    (test-event :csi-intermediate 0x19 :csi-intermediate [vt/execute])
    (test-event :csi-intermediate 0x1a :ground [vt/execute])
    (test-event :csi-intermediate 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :csi-intermediate input :csi-intermediate [vt/execute]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :csi-intermediate input :csi-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x3f))]
      (test-event :csi-intermediate input :csi-ignore []))

    (doseq [input (range 0x40 (inc 0x7e))]
      (test-event :csi-intermediate input :ground [vt/csi-dispatch]))

    (test-event :csi-intermediate 0x7f :csi-intermediate [vt/ignore])

    (test-high-events :csi-intermediate))

  (testing "csi-ignore"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :csi-ignore input :csi-ignore [vt/execute]))

    (test-event :csi-ignore 0x18 :ground [vt/execute])
    (test-event :csi-ignore 0x19 :csi-ignore [vt/execute])
    (test-event :csi-ignore 0x1a :ground [vt/execute])
    (test-event :csi-ignore 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :csi-ignore input :csi-ignore [vt/execute]))

    (doseq [input (range 0x20 (inc 0x3f))]
      (test-event :csi-ignore input :csi-ignore [vt/ignore]))

    (doseq [input (range 0x40 (inc 0x7e))]
      (test-event :csi-ignore input :ground []))

    (test-event :csi-ignore 0x7f :csi-ignore [vt/ignore])

    (test-high-events :csi-ignore))

  (testing "dcs-entry"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :dcs-entry input :dcs-entry [vt/ignore]))

    (test-event :dcs-entry 0x18 :ground [vt/execute])
    (test-event :dcs-entry 0x19 :dcs-entry [vt/ignore])
    (test-event :dcs-entry 0x1a :ground [vt/execute])
    (test-event :dcs-entry 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :dcs-entry input :dcs-entry [vt/ignore]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :dcs-entry input :dcs-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x39))]
      (test-event :dcs-entry input :dcs-param [vt/param]))

    (test-event :dcs-entry 0x3a :dcs-ignore [])
    (test-event :dcs-entry 0x3b :dcs-param [vt/param])

    (doseq [input (range 0x3c (inc 0x3f))]
      (test-event :dcs-entry input :dcs-param [vt/collect]))

    (doseq [input (range 0x40 (inc 0x7e))]
      (test-event :dcs-entry input :dcs-passthrough [vt/hook]))

    (test-event :dcs-entry 0x7f :dcs-entry [vt/ignore])

    (test-high-events :dcs-entry))

  (testing "dcs-param"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :dcs-param input :dcs-param [vt/ignore]))

    (test-event :dcs-param 0x18 :ground [vt/execute])
    (test-event :dcs-param 0x19 :dcs-param [vt/ignore])
    (test-event :dcs-param 0x1a :ground [vt/execute])
    (test-event :dcs-param 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :dcs-param input :dcs-param [vt/ignore]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :dcs-param input :dcs-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x39))]
      (test-event :dcs-param input :dcs-param [vt/param]))

    (test-event :dcs-param 0x3a :dcs-ignore [])
    (test-event :dcs-param 0x3b :dcs-param [vt/param])

    (doseq [input (range 0x3c (inc 0x3f))]
      (test-event :dcs-param input :dcs-ignore []))

    (doseq [input (range 0x40 (inc 0x7e))]
      (test-event :dcs-param input :dcs-passthrough [vt/hook]))

    (test-event :dcs-param 0x7f :dcs-param [vt/ignore])

    (test-high-events :dcs-param))

  (testing "dcs-intermediate"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :dcs-intermediate input :dcs-intermediate [vt/ignore]))

    (test-event :dcs-intermediate 0x18 :ground [vt/execute])
    (test-event :dcs-intermediate 0x19 :dcs-intermediate [vt/ignore])
    (test-event :dcs-intermediate 0x1a :ground [vt/execute])
    (test-event :dcs-intermediate 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :dcs-intermediate input :dcs-intermediate [vt/ignore]))

    (doseq [input (range 0x20 (inc 0x2f))]
      (test-event :dcs-intermediate input :dcs-intermediate [vt/collect]))

    (doseq [input (range 0x30 (inc 0x3f))]
      (test-event :dcs-intermediate input :dcs-ignore []))

    (doseq [input (range 0x40 (inc 0x7e))]
      (test-event :dcs-intermediate input :dcs-passthrough [vt/hook]))

    (test-event :dcs-intermediate 0x7f :dcs-intermediate [vt/ignore])

    (test-high-events :dcs-intermediate))

  (testing "dcs-passthrough"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :dcs-passthrough input :dcs-passthrough [vt/put]))

    (test-event :dcs-passthrough 0x18 :ground [vt/unhook vt/execute])
    (test-event :dcs-passthrough 0x19 :dcs-passthrough [vt/put])
    (test-event :dcs-passthrough 0x1a :ground [vt/unhook vt/execute])
    (test-event :dcs-passthrough 0x1b :escape [vt/unhook vt/clear])

    (doseq [input (range 0x1c (inc 0x7e))]
      (test-event :dcs-passthrough input :dcs-passthrough [vt/put]))

    (test-event :dcs-passthrough 0x7f :dcs-passthrough [vt/ignore])

    (test-high-events :dcs-passthrough [vt/unhook]))

  (testing "dcs-ignore"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :dcs-ignore input :dcs-ignore [vt/ignore]))

    (test-event :dcs-ignore 0x18 :ground [vt/execute])
    (test-event :dcs-ignore 0x19 :dcs-ignore [vt/ignore])
    (test-event :dcs-ignore 0x1a :ground [vt/execute])
    (test-event :dcs-ignore 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x7f))]
      (test-event :dcs-ignore input :dcs-ignore [vt/ignore]))

    (test-high-events :dcs-ignore))

  (testing "osc-string"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :osc-string input :osc-string [vt/ignore]))

    (test-event :osc-string 0x18 :ground [vt/osc-end vt/execute])
    (test-event :osc-string 0x19 :osc-string [vt/ignore])
    (test-event :osc-string 0x1a :ground [vt/osc-end vt/execute])
    (test-event :osc-string 0x1b :escape [vt/osc-end vt/clear])

    (doseq [input (range 0x1c (inc 0x1f))]
      (test-event :osc-string input :osc-string [vt/ignore]))

    (doseq [input (range 0x20 (inc 0x7f))]
      (test-event :osc-string input :osc-string [vt/osc-put]))

    (test-high-events :osc-string [vt/osc-end]))

  (testing "sos-pm-apc-string"
    (doseq [input (range 0x00 (inc 0x17))]
      (test-event :sos-pm-apc-string input :sos-pm-apc-string [vt/ignore]))

    (test-event :sos-pm-apc-string 0x18 :ground [vt/execute])
    (test-event :sos-pm-apc-string 0x19 :sos-pm-apc-string [vt/ignore])
    (test-event :sos-pm-apc-string 0x1a :ground [vt/execute])
    (test-event :sos-pm-apc-string 0x1b :escape [vt/clear])

    (doseq [input (range 0x1c (inc 0x7f))]
      (test-event :sos-pm-apc-string input :sos-pm-apc-string [vt/ignore]))

    (test-high-events :sos-pm-apc-string)))

(deftest make-vt-test
  (let [vt (vt/make-vt 80 24)]
    (is (= (:tabs vt) #{8 16 24 32 40 48 56 64 72}))
    (is (= (-> vt :parser :collect-chars) []))
    (is (= (-> vt :parser :param-chars) [])))
  (let [vt (vt/make-vt 20 5)]
    (is (= (:tabs vt) #{8 16}))))

(deftest print-test
  (let [vt (vt/make-vt 4 3)]

    (testing "printing within single line"
      (let [{:keys [lines cursor]} (-> vt
                                       (vt/print 0x41)
                                       (vt/print 0x42)
                                       (vt/print 0x43))]
        (is (= lines [[[0x41 {}] [0x42 {}] [0x43 {}] [0x20 {}]]
                      [[0x20 {}] [0x20 {}] [0x20 {}] [0x20 {}]]
                      [[0x20 {}] [0x20 {}] [0x20 {}] [0x20 {}]]]))
        (is (= cursor {:x 3 :y 0 :visible true}))))

    (testing "printing on the right edge of the line"
      (let [{:keys [lines cursor]} (-> vt
                                       (vt/print 0x41)
                                       (vt/print 0x42)
                                       (vt/print 0x43)
                                       (vt/print 0x44))]
        (is (= lines [[[0x41 {}] [0x42 {}] [0x43 {}] [0x44 {}]]
                      [[0x20 {}] [0x20 {}] [0x20 {}] [0x20 {}]]
                      [[0x20 {}] [0x20 {}] [0x20 {}] [0x20 {}]]]))
        (is (= cursor {:x 0 :y 1 :visible true}))))

    (testing "printing on the bottom right edge of the screen"
      (let [{:keys [lines cursor]} (-> vt
                                       (vt/print 0x41)
                                       (vt/print 0x41)
                                       (vt/print 0x41)
                                       (vt/print 0x41)
                                       (vt/print 0x42)
                                       (vt/print 0x42)
                                       (vt/print 0x42)
                                       (vt/print 0x42)
                                       (vt/print 0x43)
                                       (vt/print 0x43)
                                       (vt/print 0x43)
                                       (vt/print 0x43)
                                       (vt/print 0x44)
                                       (vt/print 0x44))]
        (is (= lines [[[0x42 {}] [0x42 {}] [0x42 {}] [0x42 {}]]
                      [[0x43 {}] [0x43 {}] [0x43 {}] [0x43 {}]]
                      [[0x44 {}] [0x44 {}] [0x20 {}] [0x20 {}]]]))
        (is (= cursor {:x 2 :y 2 :visible true}))))))

(defn move-cursor [vt x y]
  (-> vt
      (assoc-in [:cursor :x] x)
      (assoc-in [:cursor :y] y)))

(defn test-ind [action]
  (let [vt (-> (vt/make-vt 4 3)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x43)
               (vt/print 0x43)
               (vt/print 0x43)
               (vt/print 0x43)
               (vt/print 0x44)
               (vt/print 0x44))]
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 0 0) action)]
      (is (= lines [[[0x42 {}] [0x42 {}] [0x42 {}] [0x42 {}]]
                    [[0x43 {}] [0x43 {}] [0x43 {}] [0x43 {}]]
                    [[0x44 {}] [0x44 {}] [0x20 {}] [0x20 {}]]]))
      (is (= x 0))
      (is (= y 1)))
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 1 1) action)]
      (is (= lines [[[0x42 {}] [0x42 {}] [0x42 {}] [0x42 {}]]
                    [[0x43 {}] [0x43 {}] [0x43 {}] [0x43 {}]]
                    [[0x44 {}] [0x44 {}] [0x20 {}] [0x20 {}]]]))
      (is (= x 1))
      (is (= y 2)))
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 2 2) action)]
      (is (= lines [[[0x43 {}] [0x43 {}] [0x43 {}] [0x43 {}]]
                    [[0x44 {}] [0x44 {}] [0x20 {}] [0x20 {}]]
                    [[0x20 {}] [0x20 {}] [0x20 {}] [0x20 {}]]]))
      (is (= x 2))
      (is (= y 2)))))

(defn test-nel [action]
  (let [vt (-> (vt/make-vt 4 3)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x43)
               (vt/print 0x43)
               (vt/print 0x43)
               (vt/print 0x43)
               (vt/print 0x44)
               (vt/print 0x44))]
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 0 0) action)]
      (is (= lines [[[0x42 {}] [0x42 {}] [0x42 {}] [0x42 {}]]
                    [[0x43 {}] [0x43 {}] [0x43 {}] [0x43 {}]]
                    [[0x44 {}] [0x44 {}] [0x20 {}] [0x20 {}]]]))
      (is (= x 0))
      (is (= y 1)))
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 1 1) action)]
      (is (= lines [[[0x42 {}] [0x42 {}] [0x42 {}] [0x42 {}]]
                    [[0x43 {}] [0x43 {}] [0x43 {}] [0x43 {}]]
                    [[0x44 {}] [0x44 {}] [0x20 {}] [0x20 {}]]]))
      (is (= x 0))
      (is (= y 2)))
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 2 2) action)]
      (is (= lines [[[0x43 {}] [0x43 {}] [0x43 {}] [0x43 {}]]
                    [[0x44 {}] [0x44 {}] [0x20 {}] [0x20 {}]]
                    [[0x20 {}] [0x20 {}] [0x20 {}] [0x20 {}]]]))
      (is (= x 0))
      (is (= y 2)))))

(defn test-hts [action]
  (let [vt (vt/make-vt 20 3)]
    (let [{tabs :tabs} (-> vt (move-cursor 0 0) action)]
      (is (= tabs #{8 16})))
    (let [{tabs :tabs} (-> vt (move-cursor 1 0) action)]
      (is (= tabs #{1 8 16})))
    (let [{tabs :tabs} (-> vt (move-cursor 11 0) action)]
      (is (= tabs #{8 11 16})))
    (let [{tabs :tabs} (-> vt (move-cursor 19 0) action)]
      (is (= tabs #{8 16 19})))))

(defn test-ri [action]
  (let [vt (-> (vt/make-vt 4 3)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x41)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x42)
               (vt/print 0x43)
               (vt/print 0x43))]
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 2 1) action)]
      (is (= lines [[[0x41 {}] [0x41 {}] [0x41 {}] [0x41 {}]]
                    [[0x42 {}] [0x42 {}] [0x42 {}] [0x42 {}]]
                    [[0x43 {}] [0x43 {}] [0x20 {}] [0x20 {}]]]))
      (is (= x 2))
      (is (= y 0)))
    (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 2 0) action)]
      (is (= lines [[[0x20 {}] [0x20 {}] [0x20 {}] [0x20 {}]]
                    [[0x41 {}] [0x41 {}] [0x41 {}] [0x41 {}]]
                    [[0x42 {}] [0x42 {}] [0x42 {}] [0x42 {}]]]))
      (is (= x 2))
      (is (= y 0)))))

(deftest execute-test
  (let [vt (vt/make-vt 4 3)]
    (testing "0x00 (NUL)"
      (is (= vt (vt/execute vt 0x00))))

    (testing "0x01 (SOH)"
      (is (= vt (vt/execute vt 0x01))))

    (testing "0x02 (STX)"
      (is (= vt (vt/execute vt 0x02))))

    (testing "0x03 (ETX)"
      (is (= vt (vt/execute vt 0x03))))

    (testing "0x04 (EOT)"
      (is (= vt (vt/execute vt 0x04))))

    (testing "0x05 (ENQ)"
      (is (= vt (vt/execute vt 0x05))))

    (testing "0x06 (ACK)"
      (is (= vt (vt/execute vt 0x06))))

    (testing "0x07 (BEL)"
      (is (= vt (vt/execute vt 0x07))))

    (testing "0x08 (BS)"
      (let [{{x :x y :y} :cursor} (-> vt (move-cursor 0 0) (vt/execute 0x08))]
        (is (= x 0))
        (is (= y 0)))
      (let [{{x :x y :y} :cursor} (-> vt (move-cursor 2 0) (vt/execute 0x08))]
        (is (= x 1))
        (is (= y 0)))
      (let [{{x :x y :y} :cursor} (-> vt (move-cursor 0 2) (vt/execute 0x08))]
        (is (= x 0))
        (is (= y 2))))

    (testing "0x09 (HT)"
      (let [vt (vt/make-vt 20 3)]
        (let [{{x :x y :y} :cursor} (-> vt (move-cursor 0 0) (vt/execute 0x09))]
          (is (= x 8))
          (is (= y 0)))
        (let [{{x :x y :y} :cursor} (-> vt (move-cursor 2 0) (vt/execute 0x09))]
          (is (= x 8))
          (is (= y 0)))
        (let [{{x :x y :y} :cursor} (-> vt (move-cursor 8 1) (vt/execute 0x09))]
          (is (= x 16))
          (is (= y 1)))
        (let [{{x :x y :y} :cursor} (-> vt (move-cursor 9 1) (vt/execute 0x09))]
          (is (= x 16))
          (is (= y 1)))
        (let [{{x :x y :y} :cursor} (-> vt (move-cursor 16 1) (vt/execute 0x09))]
          (is (= x 16))
          (is (= y 1)))
        (let [{{x :x y :y} :cursor} (-> vt (move-cursor 19 1) (vt/execute 0x09))]
          (is (= x 19))
          (is (= y 1)))))

    (testing "0x0a (LF), 0x85 (NEL)"
      (doseq [ch [0x0a 0x85]]
        (test-nel #(vt/execute % ch))))

    (testing "0x0b (VT), 0x0c (FF), 0x84 (IND)"
        (doseq [ch [0x0b 0x0c 0x84]]
          (test-ind #(vt/execute % ch))))

    (testing "0x0d (CR)"
      (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 0 1) (vt/execute 0x0d))]
        (is (= x 0))
        (is (= y 1)))
      (let [{lines :lines {x :x y :y} :cursor} (-> vt (move-cursor 2 1) (vt/execute 0x0d))]
        (is (= x 0))
        (is (= y 1))))

    (testing "0x88 (HTS)"
      (test-hts #(vt/execute % 0x88)))

    (testing "0x8d (RI)"
      (test-ri #(vt/execute % 0x8d)))))

(deftest collect-test
  (let [vt (vt/make-vt 4 3)]
    (is (= (-> vt (vt/collect 0x21) :parser :collect-chars) [0x21]))
    (is (= (-> vt (vt/collect 0x21) (vt/collect 0x22) :parser :collect-chars) [0x21 0x22]))))

(deftest param-test
  (let [vt (vt/make-vt 4 3)]
    (is (= (-> vt (vt/param 0x31) :parser :param-chars) [0x31]))
    (is (= (-> vt (vt/param 0x31) (vt/param 0x32) :parser :param-chars) [0x31 0x32]))))

(deftest esc-dispatch-test
  (testing "0x44 (IND)"
    (test-ind #(vt/esc-dispatch % 0x44)))

  (testing "0x45 (NEL)"
    (test-nel #(vt/esc-dispatch % 0x45)))

  (testing "0x48 (HTS)"
    (test-hts #(vt/esc-dispatch % 0x48)))

  (testing "0x4d (RI)"
    (test-ri #(vt/esc-dispatch % 0x4d))))

(deftest feed-random-rubbish-test
  (let [vt (vt/make-vt 80 24)
        vt (vt/feed vt (take 1000 (repeatedly #(rand-int 0xff))))]
    (is (not= nil (-> vt :parser :state)))))
