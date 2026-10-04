(ns jepsen.d_engine
  (:require
   [clojure.tools.logging :refer [info]]
   [clojure.string :as str]
   [jepsen [checker :as checker]
            [cli :as cli]
            [client :as client]
            [generator :as gen]
            [independent :as independent]
            [nemesis :as nemesis]
            [tests :as tests]]
   [jepsen.checker.timeline :as timeline]
   [knossos.model :as model]
   [jepsen.d_engine.client     :as grpc]
   [jepsen.d_engine.bank       :as bank]
   [jepsen.d_engine.set        :as set-workload]
   [jepsen.d_engine.append     :as append-workload]
   [jepsen.d_engine.watch      :as watch-workload]
   [jepsen.d_engine.scan-watch :as scan-watch-workload]
   [jepsen.d_engine.membership :as membership-workload]
   [jepsen.d_engine.db         :as db-module]
   [jepsen.d_engine.coverage   :as coverage]
   [jepsen.d_engine.node-logs  :as node-logs]
   [jepsen.d_engine.lock       :as lock-workload]
   [jepsen.d_engine.recovery   :as recovery]
   [jepsen.d_engine.nemesis    :as d-nemesis]))

;; ========== Nemesis spec ==========

(def special-nemeses
  {:none []
   :all  [:partition :kill :pause]})

(defn parse-nemesis-spec [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))

;; ========== Register workload ==========

(defn r [_ _] {:type :invoke, :f :read,  :value nil})
(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})

(defrecord RegisterClient [endpoints channels read-policy]
  client/Client
  (open! [this test node]
    (assoc this :channels (grpc/open-all-channels endpoints)))
  (setup!    [_ _])
  (teardown! [_ _])
  (close! [this _]
    (when channels (grpc/close-all-channels channels)))
  (invoke! [_ test op]
    (let [[k v] (:value op)]
      (case (:f op)
        :read
        (let [res (grpc/lget channels k read-policy)]
          (case (:type res)
            :ok   (assoc op :type :ok :value (independent/tuple k (:value res)))
            :info (assoc op :type :fail :error (:error res))
            :fail (assoc op :type :fail :error (:error res))))
        :write
        (let [res (grpc/put! channels k v)]
          (case (:type res)
            :ok   (assoc op :type :ok)
            :info (assoc op :type :info :error (:error res))
            :fail (assoc op :type :fail :error (:error res))))))))

(defn register-workload
  "Reads and writes of independent registers, checked for linearizability.
  read-policy is how reads are asked to be served: :linearizable (default) or
  :lease (the leader answers from its own state while its lease is valid)."
  ([opts] (register-workload opts :linearizable))
  ([opts read-policy]
   {:client  (RegisterClient. (:endpoints opts) nil read-policy) ; channels populated in open!
    :checker (independent/checker
               (checker/compose
                {:linear   (checker/linearizable {:model     (model/cas-register)
                                                  :algorithm :auto})
                 :timeline (timeline/html)}))
    :generator (independent/concurrent-generator
                 3 (range 3)
                 (fn [k]
                   (->> (gen/mix [r w])
                        (gen/stagger 1/2)
                        (gen/limit 40))))}))

;; ========== Workload dispatch ==========

(defn workload [opts]
  (case (:workload opts)
    "bank"       (bank/workload opts)
    "set"        (set-workload/workload opts)
    "append"     (append-workload/workload opts)
    "watch"      (watch-workload/workload opts)
    "scan-watch" (scan-watch-workload/workload opts)
    "membership" (membership-workload/workload opts)
    "register-lease" (register-workload opts :lease)
    "lock"       (lock-workload/workload opts)
    (register-workload opts)))

;; ========== Test spec ==========

(defn check-five-voters!
  "Fails fast on option combinations a five-voter run cannot honor."
  [opts]
  (when (:five-voters opts)
    (when (:lazyfs opts)
      (throw (ex-info "--five-voters cannot be combined with --lazyfs" {})))
    (when (not= 5 (count (:nodes opts)))
      (throw (ex-info "--five-voters needs exactly five --node arguments"
                      {:nodes (:nodes opts)})))))

(defn check-faults!
  "Fails fast on fault combinations that install the same operations twice, or
  that need more nodes than the run has."
  [opts]
  (let [faults (set (:faults opts))]
    (doseq [[a b] [[:partition :leader-partition] [:pause :leader-pause]]]
      (when (and (faults a) (faults b))
        (throw (ex-info (str a " and " b " install the same operations and can not be combined")
                        {:faults faults}))))
    (when (and (faults :leader-partition) (not= 5 (count (:nodes opts))))
      (throw (ex-info ":leader-partition needs exactly five --node arguments"
                      {:nodes (:nodes opts)})))))

(defn test-spec [opts]
  (check-five-voters! opts)
  (check-faults! opts)
  (let [wl  (workload opts)
        db  (db-module/db)
        nem (d-nemesis/nemesis-package
              {:db        db
               :nodes     (:nodes opts)
               :faults    (set (:faults opts))
               :lazyfs    (:lazyfs opts)
               :partition {:targets [:majority :primaries]}
               :pause     {:targets [:all]}
               :kill      {:targets [:minority]}
               :interval  (:nemesis-interval opts)})
        [combined-nemesis combined-nem-gen]
        (if-let [m-nem (:membership-nemesis wl)]
          (let [regular-nem (:nemesis nem)]
            [(reify nemesis/Nemesis
               (setup! [this test]
                 (nemesis/setup! regular-nem test)
                 (nemesis/setup! m-nem test)
                 this)
               (invoke! [this test op]
                 (if (= :join-node (:f op))
                   (nemesis/invoke! m-nem test op)
                   (nemesis/invoke! regular-nem test op)))
               (teardown! [this test]
                 (nemesis/teardown! regular-nem test)
                 (nemesis/teardown! m-nem test)
                 this))
             ;; Membership joins run first (stable window), then fault injection.
             ;; gen/mix would fire join-node right after stop-partition, before
             ;; the cluster has a stable leader — causing immediate d-engine crashes.
             (gen/phases
               (:membership-nem-generator wl)
               (:generator nem))])
          [(:nemesis nem) (:generator nem)])
        gen (gen/phases
              (->> (:generator wl)
                   (gen/stagger (/ 1 (:rate opts)))
                   (gen/nemesis
                     (gen/phases
                       (gen/sleep 5)
                       combined-nem-gen))
                   (gen/time-limit (:time-limit opts)))
              (gen/log "Healing cluster")
              (gen/nemesis (:final-generator nem))
              (gen/log "Waiting for recovery")
              (gen/sleep 10)
              (gen/clients (:final-generator wl))
              ;; Last: look for fatal errors in the node logs of this whole run.
              (gen/log "Scanning node logs")
              (gen/nemesis node-logs/scan-op))
        faults (set (:faults opts))]
    (merge tests/noop-test
           opts
           {:name      (str "d-engine-" (:workload opts "register")
                            (when (:lazyfs opts) "-lazyfs")
                            (when (:five-voters opts) "-five"))
            :ssh       {:private-key-path        "/root/.ssh/id_rsa"
                        :strict-host-key-checking false}
            :lazyfs    (:lazyfs opts)
            :db        db
            :client    (:client wl)
            :nemesis   (node-logs/with-log-scan combined-nemesis)
            :checker   (checker/compose
                         (cond-> {:workload  (:checker wl)
                                  :node-logs (node-logs/checker)}
                           ;; A run that never put the leader in a two-node
                           ;; minority did not test what it is named after.
                           (faults :leader-partition)
                           (assoc :leader-partition (coverage/leader-partition-checker))
                           ;; Only when asked: with no fault ended the checker
                           ;; is :unknown, which would change other runs.
                           (:recovery-bound-ms opts)
                           (assoc :recovery (recovery/recovery-checker (:recovery-bound-ms opts)))))
            :generator gen})))

;; ========== CLI ==========

(def cli-opts
  [[nil "--endpoints ENDPOINTS" "d-engine endpoints (comma-separated)"
    :default  "http://node1:9081/,http://node2:9082/,http://node3:9083/"
    :parse-fn identity
    :validate [(complement empty?) "endpoints cannot be empty."]]

   ["-w" "--workload NAME" "Workload: register (default), register-lease, lock, bank, set, append, watch, scan-watch, membership"
    :default  "register"
    :parse-fn identity
    :validate [#{"register" "register-lease" "lock" "bank" "set" "append" "watch" "scan-watch" "membership"}
               "must be one of: register, register-lease, lock, bank, set, append, watch, scan-watch, membership"]]

   [nil "--membership-mode MODE"
    "Membership workload mode: promotable (default), readonly, single-learner"
    :default  "promotable"
    :parse-fn identity
    :validate [#{"promotable" "readonly" "single-learner"}
               "must be one of: promotable, readonly, single-learner"]]

   [nil "--faults FAULTS" "Nemesis faults (comma-separated: partition,kill,pause,leader-partition,leader-pause / all / none)"
    :default  [:partition]
    :parse-fn parse-nemesis-spec]

   [nil "--rate RATE" "Target ops/sec"
    :default  10
    :parse-fn read-string
    :validate [pos? "rate must be positive"]]

   [nil "--nemesis-interval SECS" "Seconds between nemesis operations"
    :default  10
    :parse-fn read-string
    :validate [pos? "nemesis-interval must be positive"]]

   [nil "--recovery-bound-ms MS"
    "Measure how long a write takes to succeed after each fault ends; fail the run above MS (use a huge value to only report)"
    :parse-fn read-string
    :validate [pos? "recovery-bound-ms must be positive"]]

   [nil "--lazyfs" "Mount each node's data dir under lazyfs, and make the kill nemesis lose unfsynced writes (simulated power loss)"
    :default false]

   [nil "--five-voters"
    "Run five nodes, all voters from the start (needs five --node arguments), instead of the default three-voter cluster"
    :default false]])

(defn -main [& args]
  (cli/run!
    (merge (cli/single-test-cmd {:test-fn  test-spec
                                 :opt-spec cli-opts})
           (cli/serve-cmd))
    args))
