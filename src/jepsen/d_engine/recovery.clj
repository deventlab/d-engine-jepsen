(ns jepsen.d_engine.recovery
  "Measures how long the cluster takes to accept a write again after a fault ends.

  The workload keeps writing while the nemesis injects faults, so the history
  already says when each fault ended and when the next write succeeded. Nothing
  is added to the run; the checker only reads the history.

  Without a bound the result is a report. With one, a run whose slowest recovery
  exceeds it is invalid, and so is a run in which writes were tried after a fault
  ended and none ever succeeded."
  (:require [jepsen.checker :as checker]))

(def heal-fs
  "Nemesis operations that end a fault: partition healed, killed nodes restarted,
  paused nodes resumed."
  #{:stop-partition :start :resume :start-all})

(def write-fs
  "Client operations that count as a write."
  #{:write})

(defn- nanos->ms [ns]
  (/ (double ns) 1e6))

(defn heal-times
  "Times (nanoseconds) at which a fault ended, in history order. The nemesis logs
  each operation twice, invocation then completion; the completion is the moment
  the fault is really over, so only that one counts."
  [history]
  (:times
   (reduce (fn [{:keys [pending times] :as state} op]
             (if (and (not (integer? (:process op)))
                      (heal-fs (:f op)))
               (if (= pending (:f op))
                 {:pending nil, :times (conj times (:time op))}
                 (assoc state :pending (:f op)))
               state))
           {:pending nil, :times []}
           history)))

(defn- client-writes [history type]
  (filter #(and (integer? (:process %))
                (= type (:type %))
                (write-fs (:f %)))
          history))

(defn recovery-times
  "For each fault end, milliseconds until the first write that completed after it
  (nil if writes were tried after it and none succeeded). Fault ends after the
  last write was tried are left out: nothing could have measured them."
  [history]
  (let [heals     (heal-times history)
        oks       (sort (map :time (client-writes history :ok)))
        last-try  (reduce max -1 (map :time (client-writes history :invoke)))]
    (vec (for [t heals
               :when (< t last-try)]
           (when-let [ok (first (filter #(> % t) oks))]
             (nanos->ms (- ok t)))))))

(defn recovery-checker
  "bound-ms nil: report only. Unknown when no fault ended during the run, since
  there was nothing to measure."
  [bound-ms]
  (reify checker/Checker
    (check [_ test history opts]
      (let [times (recovery-times history)
            slow  (remove nil? times)
            worst (when (seq slow) (apply max slow))
            base  {:bound-ms     bound-ms
                   :recoveries   (count times)
                   :max-ms       worst
                   :recovery-ms  times}]
        (cond
          (empty? times)
          (assoc base :valid? :unknown
                      :error "no fault ended while writes were being tried")

          (some nil? times)
          (assoc base :valid? false
                      :error "writes were tried after a fault ended and none succeeded")

          (and bound-ms (> worst bound-ms))
          (assoc base :valid? false
                      :error (str "slowest recovery " worst " ms exceeds the bound"))

          :else
          (assoc base :valid? true))))))
