(ns jepsen.d_engine
  (:require
   [clojure.tools.logging :refer :all]
   [clojure.string :as str]
   [jepsen [checker :as checker]
    [cli :as cli]
    [client :as client]
    [control :as c]
    [db :as db]
    [generator :as gen]
    [independent :as independent]
    [nemesis :as nemesis]
    [tests :as tests]]
   [jepsen.checker.timeline :as timeline]
   [jepsen.control.util :as cu]
   [jepsen.d-engine.db :as d-db]
   [jepsen.d-engine.nemesis :as d-nemesis]
   [jepsen.d-engine.bank :as bank]
   [jepsen.d-engine.set :as set-workload]
   [jepsen.os.debian :as debian]
   [knossos.model :as model]
   [slingshot.slingshot :refer [try+]]))

;; ========== Operation Definition ==========
(defn r [_ _] {:type :invoke, :f :read, :value nil})
(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})

;; ========== Client Implementation ==========
(def ctl-bin (str "/opt/d-engine/bin/"
                  (or (System/getenv "D_ENGINE_CTL_BINARY")
                      "dengine_ctl-linux-amd64")))

(defn parse-long-nil
  "Parses a string to a Long. Returns nil on failure."
  [s]
  (when s
    (try
      (Long/parseLong (str/trim s))
      (catch NumberFormatException _ nil))))

(defrecord Client [session node endpoints]
  client/Client

  (open! [this test node]
    (info "Opening client for node:" node)
    (assoc this
           :session (c/session node)
           :node node))

  (setup! [this test])

  (invoke! [this test op]
    (let [[k v] (:value op)]
      (c/with-session node session
        (try+
         (case (:f op)
           :read
           (let [result (parse-long-nil
                         (c/exec ctl-bin :--endpoints endpoints :lget (str k)))]
             (if result
               (assoc op :type :ok :value (independent/tuple k result))
               (assoc op :type :fail :error :not-found)))

           :write
           (do
             (c/exec ctl-bin :--endpoints endpoints :put (str k) (str v))
             (assoc op :type :ok)
             )
           )

         (catch [:type :jepsen.control/nonzero-exit] e
           (let [err (str (:err e) (:out e))]
             (cond
               ;; Definite failures - operation definitely did not occur
               (re-find #"(?i)key not found" err)
               (assoc op :type :fail :error [:not-found err])

               (re-find #"(?i)connection refused|no route to host" err)
               (assoc op :type :fail :error [:connection-refused err])

               (re-find #"(?i)invalid argument" err)
               (assoc op :type :fail :error [:invalid-argument err])

               ;; Indefinite failures - operation outcome unknown
               ;; For writes: might have succeeded before crash
               ;; For reads: use :fail to be conservative
               (re-find #"(?i)cluster unavailable" err)
               (assoc op :type (if (= :read (:f op)) :fail :info)
                      :error [:cluster-unavailable err])

               (re-find #"(?i)timeout|deadline exceeded" err)
               (assoc op :type (if (= :read (:f op)) :fail :info)
                      :error [:timeout err])

               (re-find #"(?i)leader changed|not leader" err)
               (assoc op :type (if (= :read (:f op)) :fail :info)
                      :error [:leadership-change err])

               ;; Unknown errors - treat as indefinite for safety
               :else
               (assoc op :type (if (= :read (:f op)) :fail :info)
                      :error [:unknown err])
               )
             )
           )
        )
      )
    )
  )

  (teardown! [this test])

  (close! [this test]
    (when session
      (c/disconnect session))))

;; ========== Checker Implementation ==========
(defn split-history
  "Split History: Linear Reads vs. Normal Operations"
  [history]
  (group-by (fn [op] (if (= :lget (:f op)) :lget-ops :other-ops)) history))

(defn checker
  "Linearizability checker"
  [test history opts]
  (independent/checker
   (checker/compose
    {;; :perf   (checker/perf)
     :linear (checker/linearizable {:model     (model/cas-register)
                                    :algorithm :linear})
     :timeline (timeline/html)})))

(defn register-workload [opts]
  {:client    (Client. nil nil (:endpoints opts))
   :checker   (independent/checker
               (checker/compose
                {:linear   (checker/linearizable {:model     (model/cas-register)
                                                   :algorithm :linear})
                 :timeline (timeline/html)}))
   :generator (independent/concurrent-generator
               3
               (range 3)
               (fn [k]
                 (->> (gen/mix [r w])
                      (gen/stagger 1/2)
                      (gen/limit 40))))})

(defn workload [opts]
  (case (:workload opts)
    "bank" (bank/workload opts)
    "set"  (set-workload/workload opts)
    (register-workload opts)))

(defn test-spec
  [opts]
  (println "opts:" opts)
  (println "Time limit set to:" (:time-limit opts))
  (let [db      (d-db/db)
        nemesis (d-nemesis/nemesis-package
                 {:db        db
                  :nodes     (:nodes opts)
                  :faults    #{:partition :kill :pause}
                  :partition {:targets [:majority]}
                  :pause     {:targets [:all]}
                  :kill      {:targets [:all]}
                  :interval  5})
        wl      (workload opts)]
    (merge tests/noop-test
           opts
           {:name    (str "d-engine-" (:workload opts "register"))
            :os      debian/os
            :db      db
            :client  (:client wl)
            :nemesis (:nemesis nemesis)
            :checker (:checker wl)
            :generator (->> (:generator wl)
                            (gen/stagger 1/10)
                            (gen/nemesis (:generator nemesis))
                            (gen/time-limit (:time-limit opts)))})))

(def cli-opts
  "Additional command line options."
  [["-e" "--endpoints ENDPOINTS" "d-engine gRPC endpoints (comma-separated)"
    :default "http://node1:9081,http://node2:9082,http://node3:9083"
    :parse-fn identity
    :validate [(complement empty?) "Endpoints cannot be empty."]]
   ["-w" "--workload NAME" "Workload to run: register (default), bank, set"
    :default "register"
    :parse-fn identity
    :validate [#{"register" "bank" "set"} "must be one of: register, bank, set"]]])

(defn -main
  "handles command lien arguments"
  [& args]
  (cli/run!
   (merge (cli/single-test-cmd {:test-fn test-spec
                                :opt-spec cli-opts})
          (cli/serve-cmd))
   args))
