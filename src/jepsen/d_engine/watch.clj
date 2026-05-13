(ns jepsen.d_engine.watch
  "Watch workload: verifies key-change notifications are delivered in order.
   One writer writes monotonically increasing values to a single key; watcher
   threads subscribe via the Watch streaming RPC. The checker confirms:
   - no backward jumps within a stream window (events arrive in strictly
     increasing order, reflecting Raft's total commit ordering)"
  (:require [clojure.tools.logging :refer [warn]]
            [jepsen [checker   :as checker]
                    [client    :as client]
                    [generator :as gen]]
            [jepsen.d_engine.client :as grpc])
  (:import [d_engine.client ClientApi$WatchRequest
                             ClientApi$WatchEventType
                             RaftClientServiceGrpc]
           [io.grpc Status$Code StatusRuntimeException]
           [java.util.concurrent TimeUnit]))

(def watch-key 0)
(def next-write (atom 0))

(def ^:private watch-window-ms 2000)

(defn- collect-events
  "Opens a Watch stream on ch for watch-key. Drains PUT events until the
  watch-window-ms deadline fires (DEADLINE_EXCEEDED), then returns a vector
  of decoded long values. Returns [] on any other stream error."
  [ch]
  (try
    (let [req  (-> (ClientApi$WatchRequest/newBuilder)
                   (.setClientId 99)
                   (.setKey (grpc/encode-u64 watch-key))
                   (.build))
          stub (-> (RaftClientServiceGrpc/newBlockingStub ch)
                   (.withDeadlineAfter watch-window-ms TimeUnit/MILLISECONDS))
          iter (.watch stub req)]
      (loop [events []]
        (let [[more? events']
              (try
                (if (.hasNext iter)
                  (let [resp (.next iter)]
                    (if (= (.getEventType resp) ClientApi$WatchEventType/WATCH_EVENT_TYPE_PUT)
                      (let [v (grpc/decode-u64 (.getValue resp))]
                        [true (if v (conj events v) events)])
                      [true events]))
                  [false events])
                (catch StatusRuntimeException e
                  (when-not (= (.getCode (.getStatus e)) Status$Code/DEADLINE_EXCEEDED)
                    (warn "watch stream error" (str (.getStatus e))))
                  [false events]))]
          (if more?
            (recur events')
            events'))))
    (catch Exception e
      (warn "collect-events error" (str e))
      [])))

(defrecord WatchClient [endpoints channels]
  client/Client

  (open! [this test node]
    (assoc this :channels (grpc/open-all-channels endpoints)))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :write
      (let [res (grpc/put! channels watch-key (:value op))]
        (case (:type res)
          :ok   (assoc op :type :ok)
          :info (assoc op :type :info :error (:error res))
          :fail (assoc op :type :fail :error (:error res))))

      :watch
      (let [ch     (rand-nth channels)
            events (collect-events ch)]
        (assoc op :type :ok :value events))))

  (teardown! [this test])

  (close! [this test]
    (when channels (grpc/close-all-channels channels))))

(defn write-op [_ _]
  {:type :invoke :f :write :value (swap! next-write inc)})

(defn watch-op [_ _]
  {:type :invoke :f :watch :value nil})

(defn checker []
  ; Verifies that within each individual Watch stream window, events arrive in
  ; strictly increasing order. Cross-window ordering is not checked: the server
  ; re-sends the current key value on each new subscription (a replay from its
  ; internal buffer), so the same value legitimately appears at the start of
  ; consecutive windows.
  (reify checker/Checker
    (check [_ test history opts]
      (let [written-count (->> history
                               (filter #(and (= :write (:f %)) (#{:ok :info} (:type %))))
                               count)
            watch-ops     (->> history
                               (filter #(and (= :watch (:f %)) (= :ok (:type %))))
                               (group-by :process))
            bad-order     (for [[proc ops] watch-ops
                                op         ops
                                :let       [events (->> (:value op) (remove nil?) vec)]
                                [a b]      (partition 2 1 events)
                                :when      (>= a b)]
                            {:process proc :prev a :next b})]
        (cond-> {:valid?        (empty? bad-order)
                 :written-count written-count}
          (seq bad-order) (assoc :ordering-errors (vec (take 10 bad-order))))))))

(defn workload [opts]
  (reset! next-write (quot (System/currentTimeMillis) 1000))
  {:client          (WatchClient. (:endpoints opts) nil)
   :checker         (checker)
   :generator       (gen/reserve 1 write-op watch-op)
   :final-generator (gen/once watch-op)})
