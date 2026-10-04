(ns jepsen.d-engine.node-logs-test
  (:require [clojure.test :refer [deftest is testing]]
            [jepsen.checker :as checker]
            [jepsen.control :as c]
            [jepsen.d_engine.node-logs :as node-logs]
            [jepsen.nemesis :as nemesis]))

(def esc (str (char 27)))

(deftest strip-ansi-removes-colour-codes
  (is (= "ERROR d_engine_core: Fatal error from SM worker"
         (node-logs/strip-ansi
          (str esc "[31mERROR" esc "[0m d_engine_core: Fatal error from SM worker")))))

(deftest log-lines-drops-blank-lines-and-colours
  (is (= ["a" "b"] (node-logs/log-lines (str "a\n\n" esc "[2mb" esc "[0m\n"))))
  (is (= [] (node-logs/log-lines nil)))
  (is (= [] (node-logs/log-lines ""))))

(deftest panic-blocks-pairs-the-location-with-the-message
  (is (= ["thread 'x' panicked at src/a.rs:1:2: | boom"
          "thread 'y' panicked at src/b.rs:3:4: | bang"]
         (node-logs/panic-blocks
          ["thread 'x' panicked at src/a.rs:1:2:" "boom" "--"
           "thread 'y' panicked at src/b.rs:3:4:" "bang"]))))

(deftest panic-blocks-keeps-a-panic-whose-message-line-is-missing
  (is (= ["thread 'x' panicked at src/a.rs:1:2:"]
         (node-logs/panic-blocks ["thread 'x' panicked at src/a.rs:1:2:"]))))

(deftest panic-blocks-ignores-other-lines
  (is (= [] (node-logs/panic-blocks ["Follower -> Candidate (term 2)" "--" "note: run with RUST_BACKTRACE"]))))

(deftest findings-lists-fatal-lines-and-panics-per-node
  (is (= [{:node "node1" :kind :fatal :text "Fatal error from RaftLog"}
          {:node "node1" :kind :panic :text "p"}
          {:node "node2" :kind :fatal :text "(fatal)"}]
         (node-logs/findings {"node2" {:fatal ["(fatal)"] :panics []}
                              "node1" {:fatal ["Fatal error from RaftLog"] :panics ["p"]}})))
  (is (= [] (node-logs/findings {"node1" {:fatal [] :panics []}}))))

(defn- scan-history [value]
  [{:type :invoke :f :read :process 0}
   {:type :info :f :scan-logs :process :nemesis :value nil}
   {:type :info :f :scan-logs :process :nemesis :value value}])

(defn- check [history]
  (checker/check (node-logs/checker) {} history {}))

(deftest checker-passes-a-clean-scan
  (let [result (check (scan-history {"node1" {:fatal [] :panics []}
                                     "node2" {:fatal [] :panics []}}))]
    (is (true? (:valid? result)))
    (is (= 2 (:nodes result)))))

(deftest checker-fails-on-a-fatal-error
  (let [result (check (scan-history {"node1" {:fatal [] :panics []}
                                     "node2" {:fatal ["Fatal error from RaftLog"] :panics []}}))]
    (is (false? (:valid? result)))
    (is (= 1 (:count result)))
    (is (= "node2" (-> result :findings first :node)))))

(deftest checker-fails-on-a-panic
  (is (false? (:valid? (check (scan-history {"node1" {:fatal [] :panics ["thread 'x' panicked at a.rs:1:1: | boom"]}}))))))

(deftest checker-is-unknown-when-no-scan-was-recorded
  (testing "the run ended before the scan"
    (is (= :unknown (:valid? (check [{:type :invoke :f :read :process 0}])))))
  (testing "the scan failed: its completion carries no result map"
    (is (= :unknown (:valid? (check (scan-history nil)))))))

(defn- node [commit]
  {:fatal [] :panics [] :commit commit})

(deftest checker-reports-the-commit-the-nodes-run
  (let [result (check (scan-history {"node1" (node "ce65a10") "node2" (node "ce65a10")}))]
    (is (true? (:valid? result)))
    (is (= ["ce65a10"] (:commits result)))))

(deftest checker-fails-when-nodes-run-different-commits
  (let [result (check (scan-history {"node1" (node "ce65a10") "node2" (node "641110b")}))]
    (is (false? (:valid? result)))
    (is (= "nodes run different commits" (:error result)))
    (is (= #{"ce65a10" "641110b"} (set (:commits result))))))

(deftest checker-ignores-unknown-commits
  (testing "one known commit and unknown ones: valid"
    (let [result (check (scan-history {"node1" (node "ce65a10") "node2" (node "unknown")}))]
      (is (true? (:valid? result)))))
  (testing "only unknown commits: valid, nothing to compare"
    (is (true? (:valid? (check (scan-history {"node1" (node "unknown")}))))))
  (testing "a scan without any commit field (older result format): valid"
    (is (true? (:valid? (check (scan-history {"node1" {:fatal [] :panics []}})))))))

(deftest checker-keeps-the-commits-when-a-fatal-error-fails-the-run
  (let [result (check (scan-history {"node1" {:fatal ["Fatal error from X"] :panics [] :commit "ce65a10"}}))]
    (is (false? (:valid? result)))
    (is (= ["ce65a10"] (:commits result)))))

(deftest checker-caps-the-findings-it-reports
  (let [many   (vec (repeat 50 "Fatal error from X"))
        result (check (scan-history {"node1" {:fatal many :panics []}}))]
    (is (= 50 (:count result)))
    (is (= 20 (count (:findings result))))))

(defn- recording-nemesis [calls]
  (reify nemesis/Nemesis
    (setup! [this _] this)
    (invoke! [_ _ op] (swap! calls conj (:f op)) (assoc op :value :inner))
    (teardown! [_ _])))

(deftest with-log-scan-passes-other-operations-to-the-wrapped-nemesis
  (let [calls (atom [])
        n     (node-logs/with-log-scan (recording-nemesis calls))
        op    (nemesis/invoke! n {:nodes ["node1"]} {:type :info :f :start-partition})]
    (is (= :inner (:value op)))
    (is (= [:start-partition] @calls))))

(deftest with-log-scan-scans-every-node-and-the-shared-log-once
  (let [calls (atom [])
        n     (node-logs/with-log-scan (recording-nemesis calls))
        test  {:nodes ["node1" "node2" "node3"]}]
    (with-redefs [c/on-nodes        (fn [t f] (into {} (map (fn [node] [node (f t node)]) (:nodes t))))
                  node-logs/scan-node (fn [node shared?] {:node node :shared? shared?})]
      (let [op (nemesis/invoke! n test node-logs/scan-op)]
        (is (= [] @calls) "the wrapped nemesis never sees the scan")
        (is (= {"node1" {:node "node1" :shared? true}
                "node2" {:node "node2" :shared? false}
                "node3" {:node "node3" :shared? false}}
               (:value op)))))))
