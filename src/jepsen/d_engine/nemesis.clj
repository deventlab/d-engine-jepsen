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

(defn leader-minority
  "Splits nodes into a two-node minority that holds the leader plus one random
  follower, and the remaining majority. Returns nil when no leader is known."
  [leader nodes]
  (when leader
    (let [follower (rand-nth (vec (remove #{leader} nodes)))
          minority [leader follower]]
      {:minority minority
       :majority (vec (remove (set minority) nodes))})))

(defn leader-partition-op
  "The next :start-partition operation of the leader partition fault. It cuts the
  current leader and one follower off from the other nodes: the leader keeps one
  reachable follower but no quorum. That is the shape in which a leader that
  counts stale replication progress as a fresh quorum answer keeps its lease and
  keeps serving reads.

  The operation records the leader and the minority, and whether the leader
  really was in the minority. While an election is running no leader is known;
  the partition is still installed, with a random pair, but does not count as
  coverage (see jepsen.d_engine.coverage)."
  [db test]
  (let [nodes  (:nodes test)
        leader (first (db/primaries db test))
        split  (leader-minority leader nodes)
        pair   (or (:minority split) (vec (take 2 (shuffle (vec nodes)))))
        others (or (:majority split) (vec (remove (set pair) nodes)))]
    {:type               :info
     :f                  :start-partition
     :value              (nemesis/complete-grudge [pair others])
     :leader             leader
     :minority           pair
     :leader-in-minority (boolean split)}))

(defn leader-partition-package
  "Repeatedly partitions the leader plus one follower away from the other nodes,
  then heals. Needs five nodes (a two-node side must be a minority). Reuses the
  stock partition nemesis, which applies whatever grudge the operation carries."
  [{:keys [db interval]
    :or   {interval 10}}]
  (let [stop {:type :info, :f :stop-partition, :value nil}]
    {:generator       (gen/cycle
                        [(gen/limit 1 (fn [test _ctx] (leader-partition-op db test)))
                         (gen/sleep interval)
                         stop
                         (gen/sleep interval)])
     :final-generator stop
     :nemesis         (nc/partition-nemesis db)
     :perf            #{{:name  "leader partition"
                         :start #{:start-partition}
                         :stop  #{:stop-partition}
                         :color "#E9DCA0"}}}))

(defn nemesis-package
  "Constructs a nemesis package for d-engine, instantiating only the requested fault types.

  Uses nc/nemesis-packages selectively to avoid unconditional setup! of all nemeses
  (clock nemesis installs build-essential, bitflip downloads a binary — both fail in
  our Docker environment which has no apt lists and no internet access from nodes).

  Supported faults: :partition, :kill, :pause, :leader-partition, :leader-pause.
  When opts has :lazyfs set, :kill uses leader-plus-one targeting instead of the
  generic minority/all kill (see leader-plus-one-package) — killing everyone
  starves the test of observable history, and a random minority mostly misses
  the node pair a quorum-durability violation actually requires.

  :pause freezes every node together, so the cluster never elects a new leader
  while it lasts. :leader-pause freezes only the leader: the others elect a new
  one, and the old leader wakes up believing it may still be in charge — the
  case a read lease has to survive. :partition and :leader-partition (and :pause
  and :leader-pause) install the same operations and can not be combined."
  [opts]
  (let [faults    (set (:faults opts))
        lazyfs?   (:lazyfs opts)
        ;; Jepsen's db nemesis claims :kill, :pause, :start and :resume however
        ;; few faults it was built for, so two db packages can not be composed.
        ;; Kill and pause therefore go through one.
        db-faults (cond-> #{}
                    (or (:pause faults) (:leader-pause faults)) (conj :pause)
                    (and (:kill faults) (not lazyfs?))          (conj :kill))
        db-opts   (cond-> (assoc opts :faults db-faults)
                    (:leader-pause faults) (assoc :pause {:targets [:primaries]}))
        pkgs      (cond-> []
                    (:partition faults)        (conj (nc/partition-package opts))
                    (:leader-partition faults) (conj (leader-partition-package opts))
                    (seq db-faults)            (conj (nc/db-package db-opts))
                    (and (:kill faults) lazyfs?) (conj (leader-plus-one-package opts)))]
    (nc/compose-packages pkgs)))

