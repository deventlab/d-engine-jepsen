(ns jepsen.d_engine.membership
  "Membership workload: verifies dynamic cluster expansion under fault injection.

   Modes
   -----
   :promotable     (default) — node4 + node5 join as Promotable Learners (status=1).
                   3+2=5 voters → both auto-promote via BatchPromote ConfChange.
                   Checker: safety invariants + liveness (4 and 5 eventually in members).

   :readonly       — node4 + node5 join with ReadOnly status (status=2).
                   Never promoted to Voters regardless of how many join.
                   Checker: safety invariants + 4/5 never appear in members.

   :single-learner — only node4 joins a 3-node cluster (3+1=4, even → batch_size=0).
                   Cannot promote; after stale_learner_threshold (default 300s) the
                   leader submits BatchRemove and node4 is expelled.
                   Checker: safety invariants + node4 never in members + eventually
                   leaves learners. Requires TIME_LIMIT >= 420."
  (:require [clojure.set :as set]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker   :as checker]
                    [client    :as client]
                    [control   :as c]
                    [generator :as gen]
                    [nemesis   :as nemesis]]
            [jepsen.d_engine.client :as grpc]
            [jepsen.d_engine.db     :as db])
  (:import [d_engine.client ClientApi$WatchMembershipRequest
                             RaftClientServiceGrpc]
           [io.grpc Status$Code StatusRuntimeException]
           [java.util.concurrent TimeUnit]))

(def ^:private watch-window-ms 3000)
(def ^:private write-key 0)
(def next-write (atom 0))

;; ── Snapshot collection ───────────────────────────────────────────────────────

(defn- collect-snapshots
  "Opens a WatchMembership stream on ch. Drains snapshots until the
  watch-window-ms deadline fires (DEADLINE_EXCEEDED), then returns a vector
  of {:members [...] :learners [...] :committed-index n}.
  Returns [] on any other stream error."
  [ch]
  (try
    (let [req  (-> (ClientApi$WatchMembershipRequest/newBuilder)
                   (.setClientId 99)
                   (.build))
          stub (-> (RaftClientServiceGrpc/newBlockingStub ch)
                   (.withDeadlineAfter watch-window-ms TimeUnit/MILLISECONDS))
          iter (.watchMembership stub req)]
      (loop [snapshots []]
        (let [[more? snapshots']
              (try
                (if (.hasNext iter)
                  (let [snap (.next iter)]
                    [true (conj snapshots
                                {:members        (vec (.getMembersList snap))
                                 :learners        (vec (.getLearnersList snap))
                                 :committed-index (.getCommittedIndex snap)})])
                  [false snapshots])
                (catch StatusRuntimeException e
                  (when-not (= (.getCode (.getStatus e)) Status$Code/DEADLINE_EXCEEDED)
                    (warn "watch-membership stream error" (str (.getStatus e))))
                  [false snapshots]))]
          (if more?
            (recur snapshots')
            snapshots'))))
    (catch Exception e
      (warn "collect-snapshots error" (str e))
      [])))

;; ── Client ───────────────────────────────────────────────────────────────────

(defrecord MembershipClient [endpoints channels]
  client/Client

  (open! [this test node]
    (assoc this :channels (grpc/open-all-channels endpoints)))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :write
      (let [res (grpc/put! channels write-key (:value op))]
        (case (:type res)
          :ok   (assoc op :type :ok)
          :info (assoc op :type :info :error (:error res))
          :fail (assoc op :type :fail :error (:error res))))

      :watch-membership
      (let [ch    (rand-nth channels)
            snaps (collect-snapshots ch)]
        (assoc op :type :ok :value snaps))))

  (teardown! [this test])

  (close! [this test]
    (when channels (grpc/close-all-channels channels))))

;; ── Membership nemesis ───────────────────────────────────────────────────────

(defn- process-running? [node]
  (try (c/on node (c/exec :pgrep :-f "demo")) true
       (catch Exception _ false)))

(defn membership-nemesis []
  "A nemesis that starts learner nodes via SSH, retrying if d-engine crashes
   on leader discovery (e.g. immediately after a partition heal)."
  (reify nemesis/Nemesis
    (setup! [this test] this)

    (invoke! [this test op]
      (case (:f op)
        :join-node
        (let [{:keys [node config]} (if (string? (:value op))
                                      {:node (:value op) :config nil}
                                      (:value op))]
          (try
            (loop [attempt 1]
              (info "membership-nemesis: starting" node
                    (if config (str "config=" config) "(default config)")
                    (when (> attempt 1) (str "attempt " attempt)))
              (c/on node (db/start! node config))
              (Thread/sleep 3000)
              (if (process-running? node)
                (assoc op :type :info :value {:node node :result :started :attempt attempt})
                (if (< attempt 5)
                  (do (warn "membership-nemesis:" node "crashed (attempt" attempt "), retrying in 5s")
                      (Thread/sleep 5000)
                      (recur (inc attempt)))
                  (do (warn "membership-nemesis:" node "failed after 5 attempts")
                      (assoc op :type :info :value {:node node :result :failed})))))
            (catch Exception e
              (warn "membership-nemesis: error starting" node (str e))
              (assoc op :type :info :value {:node node :result :error :error (str e)}))))

        (assoc op :type :info :value :not-handled)))

    (teardown! [this test] this)))

;; ── Nemesis generators ────────────────────────────────────────────────────────

(defn- nemesis-gen [mode]
  ;; No trailing sleep: the generator completes after all joins fire,
  ;; allowing d_engine.clj to sequence fault injection afterwards.
  (case mode
    :promotable
    (gen/phases
      (gen/sleep 10)
      {:type :info :f :join-node :value {:node "node4"}}
      (gen/sleep 5)
      {:type :info :f :join-node :value {:node "node5"}})

    :readonly
    (gen/phases
      (gen/sleep 10)
      {:type :info :f :join-node :value {:node "node4" :config "/app/config/n4-readonly"}}
      (gen/sleep 10)
      {:type :info :f :join-node :value {:node "node5" :config "/app/config/n5-readonly"}})

    :single-learner
    (gen/phases
      (gen/sleep 30)
      {:type :info :f :join-node :value {:node "node4"}})))

;; ── Client generators ─────────────────────────────────────────────────────────

(defn write-op [_ _]
  {:type :invoke :f :write :value (swap! next-write inc)})

(defn watch-membership-op [_ _]
  {:type :invoke :f :watch-membership :value nil})

;; ── Checker helpers ───────────────────────────────────────────────────────────

(defn- snaps-from
  "All watch-membership :ok snapshots flattened in history order."
  [history]
  (->> history
       (filter #(and (= :watch-membership (:f %)) (= :ok (:type %))))
       (mapcat :value)))

(defn- check-safety
  "Safety invariants shared across all modes."
  [history]
  (let [watch-ops (->> history
                       (filter #(and (= :watch-membership (:f %)) (= :ok (:type %))))
                       (group-by :process))
        bad-index
        (for [[proc ops] watch-ops
              op         ops
              :let       [snaps (:value op)]
              [a b]      (partition 2 1 snaps)
              :when      (> (:committed-index a) (:committed-index b))]
          {:process proc
           :prev-index (:committed-index a)
           :next-index (:committed-index b)})

        bad-overlap
        (for [[proc ops] watch-ops
              op         ops
              snap       (:value op)
              :let       [overlap (set/intersection
                                    (set (:members snap))
                                    (set (:learners snap)))]
              :when      (seq overlap)]
          {:process proc :snapshot snap :overlap (vec overlap)})

        bad-empty
        (for [[proc ops] watch-ops
              op         ops
              snap       (:value op)
              :when      (empty? (:members snap))]
          {:process proc :snapshot snap})]
    {:bad-index   (vec (take 10 bad-index))
     :bad-overlap (vec (take 10 bad-overlap))
     :bad-empty   (vec (take 10 bad-empty))}))

(defn- check-promotable
  "Liveness: node4 and node5 must eventually appear in members."
  [snaps]
  (let [node4? (some #(some #{4} (:members %)) snaps)
        node5? (some #(some #{5} (:members %)) snaps)]
    (cond-> {}
      (not node4?) (assoc :node4-not-promoted true)
      (not node5?) (assoc :node5-not-promoted true))))

(defn- check-readonly
  "Safety: node4 and node5 must never appear in members."
  [snaps]
  (let [bad (filter #(or (some #{4} (:members %))
                         (some #{5} (:members %)))
                    snaps)]
    (if (seq bad)
      {:readonly-nodes-promoted (vec (take 5 bad))}
      {})))

(defn- check-single-learner
  "Safety: node4 never in members.
   Liveness: node4 appears in learners, then eventually disappears (BatchRemove).
   Requires TIME_LIMIT >= 420s to observe BatchRemove."
  [snaps]
  (let [in-members?  #(some #{4} (:members %))
        in-learners? #(some #{4} (:learners %))
        promoted     (filter in-members? snaps)
        appeared?    (some in-learners? snaps)
        indexed      (map-indexed vector snaps)
        last-in-idx  (reduce (fn [acc [i s]] (if (in-learners? s) i acc)) nil indexed)
        removed?     (when last-in-idx
                       (some (fn [[i s]]
                               (and (> i last-in-idx)
                                    (not (in-learners? s))
                                    (not (in-members? s))))
                             indexed))]
    (cond-> {}
      (seq promoted)                  (assoc :node4-promoted           (vec (take 3 promoted)))
      (not appeared?)                 (assoc :node4-never-appeared     true)
      (and appeared? (not removed?))  (assoc :node4-not-removed        true))))

;; ── Checker ───────────────────────────────────────────────────────────────────

(defn checker [mode]
  (reify checker/Checker
    (check [_ test history opts]
      (let [safety       (check-safety history)
            snaps        (snaps-from history)
            window-count (->> history
                              (filter #(and (= :watch-membership (:f %))
                                           (= :ok (:type %))))
                              count)
            mode-errors  (case mode
                           :promotable     (check-promotable snaps)
                           :readonly       (check-readonly snaps)
                           :single-learner (check-single-learner snaps))
            valid?       (and (empty? (:bad-index safety))
                              (empty? (:bad-overlap safety))
                              (empty? (:bad-empty safety))
                              (empty? mode-errors))]
        (cond-> {:valid?       valid?
                 :mode         (name mode)
                 :window-count window-count}
          (seq (:bad-index safety))   (assoc :index-errors   (:bad-index safety))
          (seq (:bad-overlap safety)) (assoc :overlap-errors (:bad-overlap safety))
          (seq (:bad-empty safety))   (assoc :empty-errors   (:bad-empty safety))
          (seq mode-errors)           (merge mode-errors))))))

;; ── Workload ─────────────────────────────────────────────────────────────────

(defn workload [opts]
  (let [mode (keyword (get opts :membership-mode "promotable"))]
    (reset! next-write (quot (System/currentTimeMillis) 1000))
    {:client                    (MembershipClient. (:endpoints opts) nil)
     :checker                   (checker mode)
     :generator                 (gen/reserve 1 write-op watch-membership-op)
     :final-generator           (gen/once watch-membership-op)
     :membership-nemesis        (membership-nemesis)
     :membership-nem-generator  (nemesis-gen mode)}))
