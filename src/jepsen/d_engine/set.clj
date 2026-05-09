(ns jepsen.d_engine.set
  "Set workload: every acknowledged add must survive faults and appear in reads.
   Encodes a set of integers 0-62 as a u64 bitmask; CAS ensures atomic updates."
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [slingshot.slingshot :refer [try+]]))

(def set-key 42)

(defn ctl! [cmd endpoints & args]
  (let [result (apply shell/sh cmd "--endpoints" endpoints (map str args))]
    (if (zero? (:exit result))
      (str/trim (:out result))
      (throw (ex-info "ctl failed" {:err (:err result) :exit (:exit result)})))))

(defn parse-long-safe [s]
  (when (and s (not (str/blank? s)))
    (try (Long/parseLong s)
         (catch NumberFormatException _ nil))))

(defn decode-set [packed]
  (->> (range 63)
       (filter #(not= 0 (bit-and packed (bit-shift-left 1 %))))
       set))

(defrecord SetClient [cmd endpoints]
  client/Client

  (open! [this test node] this)

  (setup! [this test]
    (ctl! cmd endpoints "put" (str set-key) "0"))

  (invoke! [this test op]
    (try+
      (case (:f op)
        :add
        (loop [attempts 0]
          (if (> attempts 50)
            (assoc op :type :fail :error :too-many-retries)
            (let [current (or (parse-long-safe (ctl! cmd endpoints "lget" (str set-key))) 0)
                  new-val (bit-or current (bit-shift-left 1 (long (:value op))))
                  result  (ctl! cmd endpoints "cas" (str set-key) (str current) (str new-val))]
              (if (= result "true")
                (assoc op :type :ok)
                (recur (inc attempts))))))

        :read
        (let [packed (or (parse-long-safe (ctl! cmd endpoints "lget" (str set-key))) 0)]
          (assoc op :type :ok :value (decode-set packed))))

      (catch clojure.lang.ExceptionInfo e
        (let [err (str (ex-message e) " " (pr-str (ex-data e)))]
          (cond
            (re-find #"(?i)cluster unavailable|timeout|deadline exceeded|not leader" err)
            (assoc op :type :fail :error [:unavailable err])
            :else
            (assoc op :type :fail :error [:unknown err]))))))

  (teardown! [this test])

  (close! [this test]))

(defn add-op [_ _] {:type :invoke :f :add :value (rand-int 30)})
(defn read-op [_ _] {:type :invoke :f :read :value nil})

(defn workload [opts]
  {:client    (SetClient. (:command opts) (:endpoints opts))
   :checker   (checker/set-full)
   :generator (gen/mix [add-op read-op])})
