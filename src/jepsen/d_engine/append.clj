(ns jepsen.d_engine.append
  "Append workload: checks list-append histories for anomalies using Elle.
   Encodes an ordered sequence as a packed u64 (8 bits x 7 slots, values 1-127).
   Uses CAS-based atomic append; Elle detects ordering anomalies (G-single, etc.)."
  (:require [jepsen [client :as client]
                    [generator :as gen]]
            [jepsen.tests.cycle.append :as append]
            [jepsen.d_engine.client :as grpc]))

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

(defrecord AppendClient [endpoints ch]
  client/Client

  (open! [this test node]
    (assoc this :ch (grpc/open-channel (grpc/find-endpoint endpoints node))))

  (setup! [this test]
    (doseq [k (range n-keys)]
      (grpc/put! ch k 0)))

  (invoke! [this test op]
    (let [[mop-type k v] (first (:value op))]
      (case mop-type
        :r
        (let [res (grpc/lget ch k)]
          (case (:type res)
            :ok   (assoc op :type :ok :value [[:r k (decode (or (:value res) 0))]])
            :info (assoc op :type :info :error (:error res))
            :fail (assoc op :type :fail :error (:error res))))

        :append
        (loop [attempts 0]
          (if (> attempts 50)
            (assoc op :type :fail :error :too-many-retries)
            (let [res (grpc/lget ch k)]
              (case (:type res)
                :info (assoc op :type :info :error (:error res))
                :fail (assoc op :type :fail :error (:error res))
                :ok
                (let [packed (or (:value res) 0)
                      slots  (slot-count packed)]
                  (if (>= slots n-slots)
                    (assoc op :type :fail :error :list-full)
                    (let [new-packed (encode-append packed slots v)
                          cas-res    (grpc/cas! ch k packed new-packed)]
                      (case (:type cas-res)
                        :ok   (if (:swapped cas-res)
                                (assoc op :type :ok)
                                (recur (inc attempts)))
                        :info (assoc op :type :info :error (:error cas-res))
                        :fail (assoc op :type :fail :error (:error cas-res)))))))))))))

  (teardown! [this test])

  (close! [this test]
    (when ch (grpc/close-channel ch))))

(defn append-op [_ _]
  {:type :invoke :f :txn
   :value [[:append (rand-int n-keys) (swap! next-val inc)]]})

(defn read-op [_ _]
  {:type :invoke :f :txn
   :value [[:r (rand-int n-keys) nil]]})

(defn workload [opts]
  {:client    (AppendClient. (:endpoints opts) nil)
   :checker   (append/checker {:consistency-models [:sequential]
                               :anomalies          [:G-single]})
   :generator (gen/mix [append-op read-op])})
