(ns jepsen.d-engine.bank
  "Bank workload: concurrent transfers under faults must preserve total balance.
   Encodes 3 account balances as a single packed u64 for atomic CAS."
  (:require [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [jepsen [checker :as checker]
                    [client :as client]
                    [control :as c]
                    [generator :as gen]]
            [jepsen.d-engine.db :as d-db]
            [slingshot.slingshot :refer [try+]]))

(def bank-key     1)
(def n-accounts   3)
(def init-balance 1000)

(def ctl-bin (str d-db/dir "/bin/" d-db/ctl-binary))

;; Pack 3 balances (21 bits each) into a single u64.
(defn pack [a b c]
  (bit-or (bit-shift-left (long a) 42)
          (bit-shift-left (long b) 21)
          (long c)))

(defn unpack [packed]
  [(bit-and (bit-shift-right packed 42) 0x1FFFFF)
   (bit-and (bit-shift-right packed 21) 0x1FFFFF)
   (bit-and packed 0x1FFFFF)])

(def init-packed (pack init-balance init-balance init-balance))

(defn parse-long-nil [s]
  (when s
    (try (Long/parseLong (str/trim s))
         (catch NumberFormatException _ nil))))

(defrecord BankClient [session node endpoints]
  client/Client

  (open! [this test node]
    (assoc this :session (c/session node) :node node))

  (setup! [this test]
    (c/with-session node session
      (c/exec ctl-bin :--endpoints endpoints :put (str bank-key) (str init-packed))))

  (invoke! [this test op]
    (c/with-session node session
      (try+
        (case (:f op)
          :read
          (let [packed (or (parse-long-nil
                             (c/exec ctl-bin :--endpoints endpoints :lget (str bank-key)))
                           init-packed)]
            (assoc op :type :ok :value (zipmap (range n-accounts) (unpack packed))))

          :transfer
          (let [{:keys [from to amount]} (:value op)]
            (loop [attempts 0]
              (if (> attempts 50)
                (assoc op :type :fail :error :too-many-retries)
                (let [packed   (or (parse-long-nil
                                     (c/exec ctl-bin :--endpoints endpoints
                                             :lget (str bank-key)))
                                   init-packed)
                      bals     (vec (unpack packed))
                      from-bal (nth bals from)]
                  (if (< from-bal amount)
                    (assoc op :type :fail :error :insufficient-funds)
                    (let [new-bals   (-> bals (update from - amount) (update to + amount))
                          new-packed (apply pack new-bals)
                          result     (str/trim
                                       (c/exec ctl-bin :--endpoints endpoints
                                               :cas (str bank-key)
                                               (str packed) (str new-packed)))]
                      (if (= result "true")
                        (assoc op :type :ok)
                        (recur (inc attempts)))))))))

        (catch [:type :jepsen.control/nonzero-exit] e
          (let [err (str (:err e) (:out e))]
            (cond
              (re-find #"(?i)cluster unavailable|timeout|deadline exceeded|not leader" err)
              (assoc op :type :info :error [:unavailable err])
              :else
              (assoc op :type :info :error [:unknown err])))))))

  (teardown! [this test])

  (close! [this test]
    (when session (c/disconnect session))))

(defn bank-checker []
  (let [expected (* n-accounts init-balance)]
    (reify checker/Checker
      (check [_ test history opts]
        (let [bad (->> history
                       (filter #(and (= :ok (:type %)) (= :read (:f %))))
                       (remove #(= expected (reduce + (vals (:value %))))))]
          {:valid?    (empty? bad)
           :bad-reads (take 10 bad)})))))

(defn r [_ _] {:type :invoke :f :read :value nil})
(defn t [_ _]
  {:type :invoke :f :transfer
   :value {:from   (rand-int n-accounts)
           :to     (rand-int n-accounts)
           :amount (inc (rand-int 5))}})

(defn workload [opts]
  {:client    (BankClient. nil nil (:endpoints opts))
   :checker   (bank-checker)
   :generator (gen/mix [t r])})
