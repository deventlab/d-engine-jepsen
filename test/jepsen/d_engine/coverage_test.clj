(ns jepsen.d-engine.coverage-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.checker :as checker]
            [jepsen.d_engine.coverage :as coverage]))

(defn start [leader-in-minority?]
  {:type :info :f :start-partition :process :nemesis :leader-in-minority leader-in-minority?})

(def stop {:type :info :f :stop-partition :process :nemesis})

(defn episodes [history]
  (coverage/healed-leader-partitions history))

(deftest counts-a-leader-partition-that-was-healed
  (is (= 1 (episodes [(start true) (start true) stop stop]))
      "invocation and completion of one start and one stop count once"))

(deftest counts-every-healed-episode
  (is (= 3 (episodes [(start true) stop (start true) stop (start true) stop]))))

(deftest ignores-a-partition-that-was-never-healed
  (is (= 0 (episodes [(start true) (start true)]))))

(deftest ignores-partitions-without-the-leader-in-the-minority
  (is (= 0 (episodes [(start false) stop (start false) stop])))
  (is (= 0 (episodes [{:type :info :f :start-partition :process :nemesis} stop]))
      "an operation that does not say where the leader was does not count"))

(deftest ignores-a-stop-with-no-open-partition
  (is (= 0 (episodes [stop stop]))))

(deftest only-leader-partitions-count-among-others
  (is (= 1 (episodes [(start false) stop (start true) stop {:type :invoke :f :read :process 0}]))))

(deftest checker-needs-at-least-one-healed-leader-partition
  (let [check #(checker/check (coverage/leader-partition-checker) {} % {})]
    (is (true? (:valid? (check [(start true) stop]))))
    (is (false? (:valid? (check [(start false) stop]))))
    (is (false? (:valid? (check []))))
    (is (= 2 (:leader-partitions (check [(start true) stop (start true) stop]))))))
