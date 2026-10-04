(ns jepsen.d_engine.coverage
  "Checks that the fault a run was meant to inject really happened.

  Fault injection is random. A short run can finish without ever producing the
  situation it is named after, and a checker that saw nothing wrong would pass it
  all the same. A run that did not cover its fault is reported as invalid, so a
  pass always means \"the fault happened and nothing broke\"."
  (:require [jepsen.checker :as checker]))

(defn healed-leader-partitions
  "Number of partitions that cut the leader and a follower off from the other
  nodes (:leader-in-minority true on the start operation) and were healed later.
  Starts and stops each appear twice in a history (invocation and completion).
  Only a completion that reports success counts: the partitioner answers a start
  with [:isolated grudge] and a stop with :network-healed. An invocation, or a
  completion of an operation that failed, keeps the value it was invoked with."
  [history]
  (:episodes
   (reduce (fn [{:keys [open episodes] :as state} op]
             (cond
               (and (= :start-partition (:f op))
                    (= :isolated (first (:value op)))
                    (:leader-in-minority op))
               (assoc state :open true)

               (and (= :stop-partition (:f op))
                    (= :network-healed (:value op))
                    open)
               {:open false, :episodes (inc episodes)}

               :else state))
           {:open false, :episodes 0}
           history)))

(defn leader-partition-checker
  "Valid when at least one leader partition was installed and healed."
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [episodes (healed-leader-partitions history)]
        (if (pos? episodes)
          {:valid? true, :leader-partitions episodes}
          {:valid? false
           :leader-partitions 0
           :error "no partition with the leader in a two-node minority was installed and healed"})))))
