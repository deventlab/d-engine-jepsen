(ns jepsen.d_engine.bank
  "Bank workload: concurrent transfers under faults must preserve total balance.
   Encodes 3 account balances as a single packed u64 for atomic CAS."
  (:require [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [jepsen.d_engine.client :as grpc]))

(def bank-key     1)
(def n-accounts   3)
(def init-balance 1000)

(defn pack [a b c]
  (bit-or (bit-shift-left (long a) 42)
          (bit-shift-left (long b) 21)
          (long c)))

(defn unpack [packed]
  [(bit-and (bit-shift-right packed 42) 0x1FFFFF)
   (bit-and (bit-shift-right packed 21) 0x1FFFFF)
   (bit-and packed 0x1FFFFF)])

(def init-packed (pack init-balance init-balance init-balance))

(defrecord BankClient [endpoints channels]
  client/Client

  (open! [this test node]
    (assoc this :channels (grpc/open-all-channels endpoints)))

  (setup! [this test]
    (grpc/put! channels bank-key init-packed))

  (invoke! [this test op]
    (case (:f op)
      :read
      (let [res (grpc/lget channels bank-key)]
        (case (:type res)
          :ok   (let [packed (or (:value res) init-packed)]
                  (assoc op :type :ok :value (zipmap (range n-accounts) (unpack packed))))
          :info (assoc op :type :info :error (:error res))
          :fail (assoc op :type :fail :error (:error res))))

      :transfer
      (let [{:keys [from to amount]} (:value op)]
        (loop [attempts 0]
          (if (> attempts 50)
            (assoc op :type :fail :error :too-many-retries)
            (let [res (grpc/lget channels bank-key)]
              (case (:type res)
                :info (assoc op :type :info :error (:error res))
                :fail (assoc op :type :fail :error (:error res))
                :ok
                (let [packed   (or (:value res) init-packed)
                      bals     (vec (unpack packed))
                      from-bal (nth bals from)]
                  (if (< from-bal amount)
                    (assoc op :type :fail :error :insufficient-funds)
                    (let [new-bals   (-> bals (update from - amount) (update to + amount))
                          new-packed (apply pack new-bals)
                          cas-res    (grpc/cas! channels bank-key packed new-packed)]
                      (case (:type cas-res)
                        :ok   (if (:swapped cas-res)
                                (assoc op :type :ok)
                                (recur (inc attempts)))
                        :info (assoc op :type :info :error (:error cas-res))
                        :fail (assoc op :type :fail :error (:error cas-res)))))))))))))

  (teardown! [this test])

  (close! [this test]
    (when channels (grpc/close-all-channels channels))))

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
  {:client    (BankClient. (:endpoints opts) nil) ; channels populated in open!
   :checker   (bank-checker)
   :generator (gen/mix [t r])})
