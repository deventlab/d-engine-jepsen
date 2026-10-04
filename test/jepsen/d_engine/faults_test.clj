(ns jepsen.d-engine.faults-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.db :as db]
            [jepsen.generator :as gen]
            [jepsen.nemesis :as nemesis]
            [jepsen.generator.test :as gt]
            [jepsen.d_engine :as d-engine]
            [jepsen.d_engine.client :as grpc]
            [jepsen.d_engine.db :as db-module]
            [jepsen.d_engine.nemesis :as d-nemesis]))

(def five ["node1" "node2" "node3" "node4" "node5"])

(defn db-with-leader [leader]
  (reify
    db/DB
    (setup! [_ _ _])
    (teardown! [_ _ _])
    db/Primary
    (setup-primary! [_ _ _])
    (primaries [_ _] (if leader [leader] []))))

;; ---- leader partition ----

(deftest leader-minority-holds-the-leader-and-one-follower
  (dotimes [_ 50]
    (let [{:keys [minority majority]} (d-nemesis/leader-minority "node2" five)]
      (is (= 2 (count minority)))
      (is (some #{"node2"} minority))
      (is (= 3 (count majority)))
      (is (= (set five) (set (concat minority majority))))
      (is (empty? (filter (set minority) majority))))))

(deftest leader-minority-is-nil-without-a-leader
  (is (nil? (d-nemesis/leader-minority nil five))))

(deftest leader-partition-op-isolates-the-leader-with-a-follower
  (let [op (d-nemesis/leader-partition-op (db-with-leader "node3") {:nodes five})]
    (is (= :start-partition (:f op)))
    (is (= "node3" (:leader op)))
    (is (true? (:leader-in-minority op)))
    (is (some #{"node3"} (:minority op)))
    (is (= 2 (count (:minority op))))
    (let [grudge (:value op)]
      (doseq [m (:minority op)]
        (is (= (set (remove (set (:minority op)) five)) (set (get grudge m)))
            "a minority node refuses the three others"))
      (doseq [o (remove (set (:minority op)) five)]
        (is (= (set (:minority op)) (set (get grudge o)))
            "a majority node refuses the two minority nodes")))))

(deftest leader-partition-op-without-a-leader-does-not-count-as-coverage
  (let [op (d-nemesis/leader-partition-op (db-with-leader nil) {:nodes five})]
    (is (false? (:leader-in-minority op)))
    (is (nil? (:leader op)))
    (is (= 2 (count (:minority op))))))

(deftest leader-partition-generator-alternates-start-and-stop
  ;; Each operation shows up twice in a history (invocation and completion).
  (let [pkg (d-nemesis/leader-partition-package {:db (db-with-leader nil) :interval 0.01})
        ops (gt/perfect* (gt/n+nemesis-context 1)
                         (gen/nemesis (gen/limit 9 (:generator pkg))))
        fs  (->> ops (filter #(= :nemesis (:process %))) (map :f) (remove nil?) (dedupe))]
    (is (= [:start-partition :stop-partition :start-partition :stop-partition :start-partition]
           (take 5 fs)))))

;; ---- fault combinations ----

(defn- rejected? [opts]
  (try (d-engine/check-faults! opts) false
       (catch clojure.lang.ExceptionInfo _ true)))

(deftest check-faults-rejects-faults-that-install-the-same-operations
  (is (rejected? {:faults [:partition :leader-partition] :nodes five}))
  (is (rejected? {:faults [:pause :leader-pause] :nodes five})))

(deftest check-faults-leader-partition-needs-five-nodes
  (is (rejected? {:faults [:leader-partition] :nodes ["node1" "node2" "node3"]}))
  (is (not (rejected? {:faults [:leader-partition] :nodes five}))))

(deftest check-faults-accepts-ordinary-combinations
  (is (not (rejected? {:faults [:partition :kill :pause] :nodes ["node1" "node2" "node3"]})))
  (is (not (rejected? {:faults [:leader-pause] :nodes ["node1" "node2" "node3"]})))
  (is (not (rejected? {:faults [:leader-partition :kill :leader-pause] :nodes five})))
  (is (not (rejected? {:faults [] :nodes five}))))

;; ---- read policy ----

(deftest register-lease-asks-for-lease-reads
  (is (= :lease (:read-policy (:client (d-engine/workload {:workload "register-lease" :endpoints "x"})))))
  (is (= :linearizable (:read-policy (:client (d-engine/workload {:workload "register" :endpoints "x"})))))
  (is (= :linearizable (:read-policy (:client (d-engine/workload {:endpoints "x"}))))))

(deftest read-policies-map-to-different-protocol-values
  (is (= #{:linearizable :lease} (set (keys grpc/read-policies))))
  (is (not= (:linearizable grpc/read-policies) (:lease grpc/read-policies))))

(deftest lget-rejects-an-unknown-policy-before-touching-the-network
  (is (thrown? clojure.lang.ExceptionInfo (grpc/lget [] 1 :bogus))))

;; ---- fault combinations ----

(defn package-for [faults & {:as extra}]
  (d-nemesis/nemesis-package
    (merge {:db        (db-module/db)
            :nodes     ["node1" "node2" "node3"]
            :faults    faults
            :interval  10
            :partition {:targets [:majority :primaries]}
            :pause     {:targets [:all]}
            :kill      {:targets [:minority]}}
           extra)))

(deftest kill-and-pause-can-run-together
  ;; Two db packages claim the same operations and compose-packages rejects them.
  (doseq [faults [#{:kill} #{:pause} #{:kill :pause} #{:partition :kill :pause}
                  #{:kill :leader-pause}]]
    (is (some? (:nemesis (package-for faults))) (str faults))))

(deftest lazyfs-kill-and-pause-can-run-together
  (is (some? (:nemesis (package-for #{:kill :pause} :lazyfs true)))))

(deftest every-fault-the-run-asks-for-has-its-operations
  (let [fs-of (fn [faults] (nemesis/fs (:nemesis (package-for faults))))]
    (is (every? (fs-of #{:kill :pause}) [:kill :pause :start :resume]))
    (is (every? (fs-of #{:partition :kill :pause})
                [:start-partition :stop-partition :kill :pause]))))
