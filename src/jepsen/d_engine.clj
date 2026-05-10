(ns jepsen.d_engine
  (:require
   [clojure.tools.logging :refer [info]]
   [clojure.string :as str]
   [jepsen [checker :as checker]
            [cli :as cli]
            [client :as client]
            [generator :as gen]
            [independent :as independent]
            [tests :as tests]]
   [jepsen.checker.timeline :as timeline]
   [knossos.model :as model]
   [slingshot.slingshot :refer [try+]]
   [clojure.java.shell :as shell]
   [jepsen.d_engine.bank    :as bank]
   [jepsen.d_engine.set     :as set-workload]
   [jepsen.d_engine.append  :as append-workload]
   [jepsen.d_engine.db      :as db-module]
   [jepsen.d_engine.nemesis :as d-nemesis]))

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

(defn ctl-command [cmd & args]
  (let [result (apply shell/sh cmd args)]
    (info "cmd:" (pr-str (cons cmd args))
          "exit:" (:exit result)
          "out:"  (:out result))
    (if (zero? (:exit result))
      (:out result)
      (throw (ex-info "Command failed"
                      {:exit (:exit result)
                       :err  (:err result)
                       :out  (:out result)})))))

(defn parse-long-nil [s]
  (when s
    (try (-> s str/trim Long/parseLong)
         (catch NumberFormatException _ nil))))

(defn register-client [cmd endpoints]
  (reify client/Client
    (open!    [this test node] this)
    (setup!   [_ _])
    (teardown![_ _])
    (close!   [_ _])
    (invoke!  [_ test op]
      (let [[k v]       (:value op)
            crash-type  (if (= :read (:f op)) :fail :info)]
        (try+
          (case (:f op)
            :read  (let [res (parse-long-nil
                               (ctl-command cmd "--endpoints" endpoints "lget" (str k)))]
                     (if res
                       (assoc op :type :ok, :value (independent/tuple k res))
                       (assoc op :type :fail, :error :not-found)))
            :write (do (ctl-command cmd "--endpoints" endpoints "put" (str k) (str v))
                       (assoc op :type :ok)))
          (catch clojure.lang.ExceptionInfo e
            (let [err (str (ex-message e) " " (pr-str (ex-data e)))]
              (cond
                (re-find #"(?i)cluster unavailable|timeout|deadline exceeded|not leader" err)
                (assoc op :type crash-type :error [:unavailable err])
                :else
                (assoc op :type crash-type :error [:unknown err])))))))))

(defn register-workload [opts]
  {:client  (register-client (:command opts) (:endpoints opts))
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
    "bank"   (bank/workload opts)
    "set"    (set-workload/workload opts)
    "append" (append-workload/workload opts)
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
               :kill      {:targets [:all]}
               :interval  (:nemesis-interval opts)})
        gen (->> (:generator wl)
                 (gen/stagger (/ 1 (:rate opts)))
                 (gen/nemesis
                   (gen/phases
                     (gen/sleep 5)
                     (:generator nem)))
                 (gen/time-limit (:time-limit opts)))]
    (merge tests/noop-test
           opts
           {:name      (str "d-engine-" (:workload opts "register"))
            :ssh       {:private-key-path        "/root/.ssh/id_rsa"
                        :strict-host-key-checking false}
            :db        db
            :client    (:client wl)
            :nemesis   (:nemesis nem)
            :checker   (:checker wl)
            :generator gen})))

;; ========== CLI ==========

(def cli-opts
  [[nil "--command CMD" "CLI binary path"
    :default  "client-usage-standalone-demo"
    :parse-fn identity
    :validate [(complement empty?) "command cannot be empty."]]

   [nil "--endpoints ENDPOINTS" "d-engine endpoints (comma-separated)"
    :default  "http://node1:9081/,http://node2:9082/,http://node3:9083/"
    :parse-fn identity
    :validate [(complement empty?) "endpoints cannot be empty."]]

   ["-w" "--workload NAME" "Workload: register (default), bank, set"
    :default  "register"
    :parse-fn identity
    :validate [#{"register" "bank" "set" "append"} "must be one of: register, bank, set, append"]]

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
