(ns jepsen.d-engine.coverage-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.checker :as checker]
            [jepsen.d_engine.coverage :as coverage]))

(def grudge {"node1" #{"node3"} "node3" #{"node1"}})

(defn start-invoke [leader-in-minority?]
  {:type :info :f :start-partition :process :nemesis :value grudge
   :leader-in-minority leader-in-minority?})

(defn start-ok [leader-in-minority?]
  (assoc (start-invoke leader-in-minority?) :value [:isolated grudge]))

(defn start-failed [leader-in-minority?]
  (assoc (start-invoke leader-in-minority?) :error "iptables failed"))

(def stop-invoke {:type :info :f :stop-partition :process :nemesis :value nil})
(def stop-ok     (assoc stop-invoke :value :network-healed))
(def stop-failed (assoc stop-invoke :error "iptables failed"))

(defn partition-healed
  "One start and one stop as a history records them: invocation, then completion."
  [leader-in-minority?]
  [(start-invoke leader-in-minority?) (start-ok leader-in-minority?) stop-invoke stop-ok])

(defn episodes [history]
  (coverage/healed-leader-partitions history))

(deftest counts-a-leader-partition-that-was-healed
  (is (= 1 (episodes (partition-healed true)))
      "invocation and completion of one start and one stop count once"))

(deftest counts-every-healed-episode
  (is (= 3 (episodes (concat (partition-healed true) (partition-healed true)
                             (partition-healed true))))))

(deftest ignores-a-partition-that-was-never-healed
  (is (= 0 (episodes [(start-invoke true) (start-ok true)])))
  (is (= 0 (episodes [(start-invoke true) (start-ok true) stop-invoke]))
      "a stop that was invoked but did not complete is not a heal"))

(deftest ignores-a-partition-that-was-never-installed
  (is (= 0 (episodes [(start-invoke true) (start-failed true) stop-invoke stop-ok]))
      "the start failed, so the later stop heals nothing")
  (is (= 0 (episodes [(start-invoke true) stop-invoke stop-ok]))
      "an invocation alone does not open a partition"))

(deftest ignores-a-heal-that-failed
  (is (= 0 (episodes [(start-invoke true) (start-ok true) stop-invoke stop-failed]))))

(deftest ignores-partitions-without-the-leader-in-the-minority
  (is (= 0 (episodes (concat (partition-healed false) (partition-healed false)))))
  (is (= 0 (episodes [{:type :info :f :start-partition :process :nemesis
                       :value [:isolated grudge]}
                      stop-invoke stop-ok]))
      "an operation that does not say where the leader was does not count"))

(deftest ignores-a-stop-with-no-open-partition
  (is (= 0 (episodes [stop-invoke stop-ok stop-invoke stop-ok]))))

(deftest only-leader-partitions-count-among-others
  (is (= 1 (episodes (concat (partition-healed false) (partition-healed true)
                             [{:type :invoke :f :read :process 0}])))))

(deftest checker-needs-at-least-one-healed-leader-partition
  (let [check #(checker/check (coverage/leader-partition-checker) {} % {})]
    (is (true? (:valid? (check (partition-healed true)))))
    (is (false? (:valid? (check (partition-healed false)))))
    (is (false? (:valid? (check []))))
    (is (false? (:valid? (check [(start-invoke true) (start-failed true) stop-invoke stop-ok]))))
    (is (= 2 (:leader-partitions (check (concat (partition-healed true)
                                                (partition-healed true))))))))
