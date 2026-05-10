(ns jepsen.d_engine.append
  "Append workload: checks list-append histories for anomalies using Elle.
   Encodes an ordered sequence as a packed u64 (8 bits x 7 slots, values 1-127).
   Uses CAS-based atomic append; Elle detects ordering anomalies (G-single, etc.)."
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [jepsen [client :as client]
                    [generator :as gen]]
            [jepsen.tests.cycle.append :as append]
            [slingshot.slingshot :refer [try+]]))

(def n-keys    3)
(def n-slots   7)   ; 7 slots x 8 bits = 56 bits, safe within signed long
(def slot-bits 8)

;; Global counter ensures each appended value is unique per key across the run.
(def next-val (atom 0))

(defn decode [packed]
  (->> (range n-slots)
       (map #(bit-and (bit-shift-right (long packed) (* % slot-bits)) 0xFF))
       (take-while pos?)
       vec))

(defn slot-count [packed]
  (count (decode packed)))

(defn encode-append [packed slot v]
  (bit-or (long packed) (bit-shift-left (long v) (* slot slot-bits))))

(defn ctl! [cmd endpoints & args]
  (let [result (apply shell/sh cmd "--endpoints" endpoints (map str args))]
    (if (zero? (:exit result))
      (str/trim (:out result))
      (throw (ex-info "ctl failed" {:err (:err result) :exit (:exit result)})))))

(defn parse-long-safe [s]
  (when (and s (not (str/blank? s)))
    (try (Long/parseLong s)
         (catch NumberFormatException _ nil))))

(defrecord AppendClient [cmd endpoints]
  client/Client

  (open! [this test node] this)

  (setup! [this test]
    (doseq [k (range n-keys)]
      (ctl! cmd endpoints "put" (str k) "0")))

  (invoke! [this test op]
    (try+
      (let [[mop-type k v] (first (:value op))]
        (case mop-type
          :r
          (let [packed (or (parse-long-safe (ctl! cmd endpoints "lget" (str k))) 0)]
            (assoc op :type :ok :value [[:r k (decode packed)]]))

          :append
          (loop [attempts 0]
            (if (> attempts 50)
              (assoc op :type :fail :error :too-many-retries)
              (let [packed (or (parse-long-safe (ctl! cmd endpoints "lget" (str k))) 0)
                    slots  (slot-count packed)]
                (if (>= slots n-slots)
                  (assoc op :type :fail :error :list-full)
                  (let [new-packed (encode-append packed slots v)
                        result     (ctl! cmd endpoints "cas"
                                         (str k) (str packed) (str new-packed))]
                    (if (= result "true")
                      (assoc op :type :ok)
                      (recur (inc attempts))))))))))

      (catch clojure.lang.ExceptionInfo e
        (let [err (str (ex-message e) " " (pr-str (ex-data e)))]
          (cond
            (re-find #"(?i)cluster unavailable|timeout|deadline exceeded|not leader" err)
            (assoc op :type :info :error [:unavailable err])
            :else
            (assoc op :type :info :error [:unknown err]))))))

  (teardown! [this test])
  (close! [this test]))

(defn append-op [_ _]
  {:type :invoke :f :txn
   :value [[:append (rand-int n-keys) (swap! next-val inc)]]})

(defn read-op [_ _]
  {:type :invoke :f :txn
   :value [[:r (rand-int n-keys) nil]]})

(defn workload [opts]
  {:client    (AppendClient. (:command opts) (:endpoints opts))
   :checker   (append/checker {:consistency-models [:sequential]
                               :anomalies          [:G-single]})
   :generator (gen/mix [append-op read-op])})
