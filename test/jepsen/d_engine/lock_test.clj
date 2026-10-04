(ns jepsen.d-engine.lock-test
  (:require [clojure.test :refer [deftest is]]
            [jepsen.checker :as checker]
            [jepsen.d_engine.lock :as lock]))

(def acquire {:type :invoke, :f :acquire, :process 0, :value nil})
(def release {:type :invoke, :f :release, :process 0, :value nil})

(defn recording-cas
  "A cas that answers with the given results in order and records its calls."
  [calls & results]
  (let [rs (atom results)]
    (fn [expected new]
      (swap! calls conj [expected new])
      (let [r (first @rs)]
        (swap! rs rest)
        r))))

(deftest owner-token-is-per-thread-not-per-process
  (let [test {:concurrency 3}]
    (is (= 1 (lock/owner-token test {:process 0})))
    (is (= 2 (lock/owner-token test {:process 1})))
    (is (= 1 (lock/owner-token test {:process 3})))))

(deftest acquire-swaps-free-for-the-token
  (let [calls (atom [])
        held  (atom false)
        r     (lock/run-op (recording-cas calls {:type :ok :swapped true}) held acquire 5)]
    (is (= :ok (:type r)))
    (is (true? @held))
    (is (= [[0 5]] @calls))))

(deftest acquire-of-a-held-lock-fails-and_gives_back_only_our_token
  (let [calls (atom [])
        held  (atom false)
        r     (lock/run-op (recording-cas calls {:type :ok :swapped false} {:type :ok :swapped false})
                           held acquire 5)]
    (is (= :fail (:type r)))
    (is (false? @held))
    (is (= [[0 5] [5 0]] @calls) "the second call can only free the token 5")))

(deftest unconfirmed-acquire-is-a-failure-not-unknown
  (doseq [res [{:type :info :error :deadline-exceeded} {:type :fail :error "x"}]]
    (let [calls (atom [])
          held  (atom false)
          r     (lock/run-op (recording-cas calls res res) held acquire 5)]
      (is (= :fail (:type r)))
      (is (false? @held))
      (is (= [[0 5] [5 0]] @calls)
          "the acquire may have gone through, so the token is given back"))))

(deftest acquire-while-holding-fails-without-touching-the-store
  (let [calls (atom [])
        held  (atom true)
        r     (lock/run-op (recording-cas calls) held acquire 5)]
    (is (= :fail (:type r)))
    (is (= :already-held (:error r)))
    (is (empty? @calls))))

(deftest release-without-the-lock-fails-without-touching-the-store
  (let [calls (atom [])
        r     (lock/run-op (recording-cas calls) (atom false) release 5)]
    (is (= :fail (:type r)))
    (is (= :not-held (:error r)))
    (is (empty? @calls))))

(deftest release-is-always-ok-and-ends-the-hold
  (doseq [res [{:type :ok :swapped true} {:type :ok :swapped false}
               {:type :info :error :deadline-exceeded} {:type :fail :error "x"}]]
    (let [calls (atom [])
          held  (atom true)
          r     (lock/run-op (recording-cas calls res) held release 5)]
      (is (= :ok (:type r)) (str res))
      (is (false? @held))
      (is (= [[5 0]] @calls)))))

(defn history [& ops]
  (vec (map-indexed (fn [i o] (assoc o :index i :time (* i 1000000))) ops)))

(defn ev [type f process] {:type type, :f f, :process process, :value nil})

(defn check [h]
  (checker/check (:checker (lock/workload {:endpoints ""})) {} h {}))

(deftest the-mutex-checker-accepts-a-handover
  (is (true? (:valid? (check (history (ev :invoke :acquire 0) (ev :ok :acquire 0)
                                      (ev :invoke :release 0) (ev :ok :release 0)
                                      (ev :invoke :acquire 1) (ev :ok :acquire 1)))))))

(deftest the-mutex-checker-rejects-two-holders
  (is (false? (:valid? (check (history (ev :invoke :acquire 0) (ev :ok :acquire 0)
                                       (ev :invoke :acquire 1) (ev :ok :acquire 1)))))))
