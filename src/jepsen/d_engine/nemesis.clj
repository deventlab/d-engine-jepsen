(ns jepsen.d_engine.nemesis
  "Nemesis implementations for d-engine testing"
  (:require [clojure.tools.logging :refer [info]]
            [jepsen [control :as c]
                    [db :as db]
                    [nemesis :as nemesis]
                    [generator :as gen]]
            [jepsen.nemesis.combined :as nc]))

(defn leader-plus-one-nemesis
  "Kills the current leader plus one other randomly chosen node, leaving a
  third node alive. This is the specific pair a quorum-durability violation
  requires (the leader plus the follower whose ack completed quorum) —
  unlike killing every node, it doesn't zero out cluster availability, so
  the workload keeps producing observable history throughout the fault."
  [db]
  (reify
    nemesis/Reflection
    (fs [this] #{:kill-leader-plus-one :start-all})

    nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (case (:f op)
        :kill-leader-plus-one
        (let [leader (first (db/primaries db test))
              pool   (remove #{leader} (:nodes test))
              target (vec (if leader
                            (cons leader (take 1 (shuffle pool)))
                            (take 2 (shuffle (:nodes test)))))
              res    (c/on-nodes test target (partial db/kill! db))]
          (assoc op :value res))

        :start-all
        (assoc op :value (c/on-nodes test (:nodes test) (partial db/start! db)))))

    (teardown! [this test])))

(defn leader-plus-one-package
  "Nemesis+generator package that repeatedly kills the leader plus one other
  node, alternating with restarting everyone. Used for the durability test:
  needs the cluster to stay mostly available (unlike an :all kill) while
  still hitting the exact node pair a quorum-durability bug requires.

  Killing 2 of 3 nodes breaks quorum just as completely as killing all 3 —
  the cluster is 100% unavailable until it recovers, regardless of which
  node was left alive. So unlike a minority kill (where jepsen.nemesis.combined's
  usual flip-flop+stagger pattern is fine — the cluster keeps serving
  through it), the gap between :start-all and the next kill must be a
  guaranteed dwell long enough for real election + catch-up, not a random
  stagger draw that can land near zero (confirmed happening: 15:47:01.388
  kill -> 15:47:01.604 start, 0.2s later, cluster never got to serve
  anything — every op failed with connection-refused for the whole run)."
  [{:keys [db interval recovery-interval]
    :or   {interval 5 recovery-interval 20}}]
  (let [kill  {:type :info, :f :kill-leader-plus-one, :value nil}
        start {:type :info, :f :start-all, :value nil}]
    {:generator       (gen/cycle
                        [kill
                         (gen/sleep interval)
                         start
                         (gen/sleep recovery-interval)])
     :final-generator start
     :nemesis         (leader-plus-one-nemesis db)
     :perf            #{{:name  "leader+1 kill"
                          :start #{:kill-leader-plus-one}
                          :stop  #{:start-all}
                          :color "#E9A4A0"}}}))

(defn nemesis-package
  "Constructs a nemesis package for d-engine, instantiating only the requested fault types.

  Uses nc/nemesis-packages selectively to avoid unconditional setup! of all nemeses
  (clock nemesis installs build-essential, bitflip downloads a binary — both fail in
  our Docker environment which has no apt lists and no internet access from nodes).

  Supported faults: :partition, :kill, :pause. When opts has :lazyfs set,
  :kill uses leader-plus-one targeting instead of the generic minority/all
  kill (see leader-plus-one-package) — killing everyone starves the test of
  observable history, and a random minority mostly misses the node pair a
  quorum-durability violation actually requires."
  [opts]
  (let [faults  (set (:faults opts))
        lazyfs? (:lazyfs opts)
        pkgs    (cond-> []
                  (:partition faults) (conj (nc/partition-package opts))
                  (:pause faults)     (conj (nc/db-package (assoc opts :faults #{:pause})))
                  (:kill faults)      (conj (if lazyfs?
                                              (leader-plus-one-package opts)
                                              (nc/db-package (assoc opts :faults #{:kill})))))]
    (nc/compose-packages pkgs)))

