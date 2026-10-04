(ns jepsen.d-engine.recovery-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.checker :as checker]
            [jepsen.d_engine.recovery :as recovery]))

(def ms 1000000)

(defn heal [f t] [{:process :nemesis, :type :info, :f f, :time (* t ms)}
                  {:process :nemesis, :type :info, :f f, :time (* (+ t 5) ms)}])
(defn try-write [t] {:process 0, :type :invoke, :f :write, :time (* t ms)})
(defn ok-write [t] {:process 0, :type :ok, :f :write, :time (* t ms)})
(defn ok-read [t] {:process 1, :type :ok, :f :read, :time (* t ms)})

(defn check [bound history]
  (checker/check (recovery/recovery-checker bound) {} history {}))

(deftest heal-time-is-the-completion-not-the-invocation
  (is (= [(* 105 ms)] (recovery/heal-times (heal :stop-partition 100)))))

(deftest recovery-is-measured-from-the-end-of-the-fault
  (let [h (concat [(try-write 50)] (heal :stop-partition 100)
                  [(try-write 110) (ok-write 130)])]
    (is (= [25.0] (recovery/recovery-times h)))))

(deftest reads-do-not-count-as-recovery
  (let [h (concat [(try-write 50)] (heal :stop-partition 100)
                  [(try-write 110) (ok-read 120) (ok-write 200)])]
    (is (= [95.0] (recovery/recovery-times h)))))

(deftest report-only-passes-without-a-bound
  (let [h (concat (heal :stop-partition 100) [(try-write 110) (ok-write 9000)])
        r (check nil h)]
    (is (true? (:valid? r)))
    (is (= 1 (:recoveries r)))))

(deftest slowest-recovery-over-the-bound-fails
  (let [h (concat (heal :start 100) [(try-write 110) (ok-write 2105)])]
    (is (true? (:valid? (check 3000 h))))
    (is (false? (:valid? (check 1000 h))))))

(deftest writes-tried-but-never-ok-fail
  (let [h (concat (heal :resume 100) [(try-write 110) (try-write 120)])]
    (is (false? (:valid? (check nil h))))))

(deftest fault-end-after-the-last-write-is-not-measured
  (let [h (concat [(try-write 50) (ok-write 60)] (heal :stop-partition 100))
        r (check nil h)]
    (is (= :unknown (:valid? r)))))

(deftest no-fault-end-is-unknown-not-a-pass
  (is (= :unknown (:valid? (check 1000 [(try-write 1) (ok-write 2)])))))

(deftest every-fault-end-is-measured
  (let [h (concat [(try-write 1)]
                  (heal :stop-partition 100) [(try-write 110) (ok-write 120)]
                  (heal :start-all 300) [(try-write 310) (ok-write 400)])]
    (is (= [15.0 95.0] (recovery/recovery-times h)))))
