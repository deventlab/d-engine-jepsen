(ns jepsen.d_engine.set
  "Set workload: every acknowledged add must survive faults and appear in reads.
   Encodes a set of integers 0-62 as a u64 bitmask; CAS ensures atomic updates."
  (:require [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [jepsen.d_engine.client :as grpc]))

(def set-key 42)

(defn decode-set [packed]
  (->> (range 63)
       (filter #(not= 0 (bit-and packed (bit-shift-left 1 %))))
       set))

(defrecord SetClient [endpoints channels]
  client/Client

  (open! [this test node]
    (assoc this :channels (grpc/open-all-channels endpoints)))

  (setup! [this test]
    (grpc/put! channels set-key 0))

  (invoke! [this test op]
    (case (:f op)
      :add
      (loop [attempts 0]
        (if (> attempts 50)
          (assoc op :type :fail :error :too-many-retries)
          (let [res (grpc/lget channels set-key)]
            (case (:type res)
              :info (assoc op :type :info :error (:error res))
              :fail (assoc op :type :fail :error (:error res))
              :ok
              (let [current (or (:value res) 0)
                    new-val (bit-or current (bit-shift-left 1 (long (:value op))))
                    cas-res (grpc/cas! channels set-key current new-val)]
                (case (:type cas-res)
                  :ok   (if (:swapped cas-res)
                          (assoc op :type :ok)
                          (recur (inc attempts)))
                  :info (assoc op :type :info :error (:error cas-res))
                  :fail (assoc op :type :fail :error (:error cas-res))))))))

      :read
      (let [res (grpc/lget channels set-key)]
        (case (:type res)
          :ok   (assoc op :type :ok :value (decode-set (or (:value res) 0)))
          :info (assoc op :type :info :error (:error res))
          :fail (assoc op :type :fail :error (:error res))))))

  (teardown! [this test])

  (close! [this test]
    (when channels (grpc/close-all-channels channels))))

(defn read-op [_ _] {:type :invoke :f :read :value nil})

(defn workload [opts]
  {:client          (SetClient. (:endpoints opts) nil)
   :checker         (checker/set-full)
   :generator       (gen/mix [(->> (range 63)
                                   (map (fn [v] {:type :invoke :f :add :value v})))
                               (repeatedly #(read-op nil nil))])
   :final-generator (gen/once read-op)})
