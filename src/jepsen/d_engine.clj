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
   [jepsen.d_engine.membership :as membership-workload]
   [jepsen.d_engine.db         :as db-module]
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

(defrecord RegisterClient [endpoints channels]
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
        (let [res (grpc/lget channels k)]
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

(defn register-workload [opts]
  {:client  (RegisterClient. (:endpoints opts) nil) ; channels populated in open!
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
                       (gen/limit 40))))})

;; ========== Workload dispatch ==========

(defn workload [opts]
  (case (:workload opts)
    "bank"       (bank/workload opts)
    "set"        (set-workload/workload opts)
    "append"     (append-workload/workload opts)
    "watch"      (watch-workload/workload opts)
    "membership" (membership-workload/workload opts)
    (register-workload opts)))

;; ========== Test spec ==========

(defn test-spec [opts]
  (let [wl  (workload opts)
        db  (db-module/db)
        nem (d-nemesis/nemesis-package
              {:db        db
               :nodes     (:nodes opts)
               :faults    (set (:faults opts))
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
              (gen/clients (:final-generator wl)))]
    (merge tests/noop-test
           opts
           {:name      (str "d-engine-" (:workload opts "register"))
            :ssh       {:private-key-path        "/root/.ssh/id_rsa"
                        :strict-host-key-checking false}
            :db        db
            :client    (:client wl)
            :nemesis   combined-nemesis
            :checker   (:checker wl)
            :generator gen})))

;; ========== CLI ==========

(def cli-opts
  [[nil "--endpoints ENDPOINTS" "d-engine endpoints (comma-separated)"
    :default  "http://node1:9081/,http://node2:9082/,http://node3:9083/"
    :parse-fn identity
    :validate [(complement empty?) "endpoints cannot be empty."]]

   ["-w" "--workload NAME" "Workload: register (default), bank, set, append, watch, membership"
    :default  "register"
    :parse-fn identity
    :validate [#{"register" "bank" "set" "append" "watch" "membership"}
               "must be one of: register, bank, set, append, watch, membership"]]

   [nil "--membership-mode MODE"
    "Membership workload mode: promotable (default), readonly, single-learner"
    :default  "promotable"
    :parse-fn identity
    :validate [#{"promotable" "readonly" "single-learner"}
               "must be one of: promotable, readonly, single-learner"]]

   [nil "--faults FAULTS" "Nemesis faults (comma-separated: partition,kill,pause / all / none)"
    :default  [:partition]
    :parse-fn parse-nemesis-spec]

   [nil "--rate RATE" "Target ops/sec"
    :default  10
    :parse-fn read-string
    :validate [pos? "rate must be positive"]]

   [nil "--nemesis-interval SECS" "Seconds between nemesis operations"
    :default  10
    :parse-fn read-string
    :validate [pos? "nemesis-interval must be positive"]]])

(defn -main [& args]
  (cli/run!
    (merge (cli/single-test-cmd {:test-fn  test-spec
                                 :opt-spec cli-opts})
           (cli/serve-cmd))
    args))
