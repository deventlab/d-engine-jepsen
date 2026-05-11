(ns jepsen.d_engine.client
  "Native gRPC client for d-engine.
   Replaces shell/sh CLI invocation with direct JVM gRPC calls, enabling
   precise :ok/:info/:fail Jepsen semantics and Jepsen-controlled deadlines."
  (:require [clojure.string :as str])
  (:import
   [d_engine.client ClientApi$ClientWriteRequest
                    ClientApi$ClientReadRequest
                    ClientApi$WriteCommand
                    ClientApi$WriteCommand$Insert
                    ClientApi$WriteCommand$CompareAndSwap
                    ClientApi$ReadConsistencyPolicy
                    RaftClientServiceGrpc]
   [d_engine.error Error$ErrorCode]
   [io.grpc ManagedChannel ManagedChannelBuilder Status$Code StatusRuntimeException]
   [io.grpc.netty.shaded.io.grpc.netty NettyChannelBuilder]
   [java.nio ByteBuffer]
   [java.util.concurrent TimeUnit]
   [com.google.protobuf ByteString]))

;; ── Encoding ────────────────────────────────────────────────────────────────

(defn ^ByteString encode-u64 [^long n]
  (let [buf (ByteBuffer/allocate 8)]
    (.putLong buf n)
    (ByteString/copyFrom (.array buf))))

(defn decode-u64 [^ByteString bs]
  (when (and bs (= 8 (.size bs)))
    (.getLong (ByteBuffer/wrap (.toByteArray bs)))))

;; ── Endpoint helpers ─────────────────────────────────────────────────────────

(defn find-endpoint
  "From a comma-separated endpoints string, return the host:port matching node.
   Falls back to the first endpoint if no match found.
   Input:  'http://node1:9081,http://node2:9082'  node='node1'
   Output: 'node1:9081'"
  [endpoints-str node]
  (let [clean #(-> % (str/replace #"^https?://" "") (str/replace #"/$" ""))
        eps   (str/split endpoints-str #",")]
    (or (some-> (->> eps (filter #(str/includes? % (str node))) first) clean)
        (clean (first eps)))))

;; ── Channel management ───────────────────────────────────────────────────────

(defn open-channel
  "Opens a plaintext gRPC channel to a single endpoint (host:port).
   The endpoint string must be 'host:port' without scheme."
  ^ManagedChannel [endpoint]
  (let [[host port] (-> endpoint
                        (clojure.string/replace #"^https?://" "")
                        (clojure.string/split #":" 2))]
    (-> (ManagedChannelBuilder/forAddress host (Integer/parseInt port))
        (.usePlaintext)
        (.build))))

(defn open-all-channels
  "Opens one channel per endpoint. Returns a vector of ManagedChannels.
   On node kill, application-level failover (with-failover) routes around the dead node."
  [endpoints-str]
  (let [clean #(-> % str/trim
                   (str/replace #"^https?://" "")
                   (str/replace #"/$" ""))
        eps   (->> (str/split endpoints-str #",") (map clean))]
    (mapv open-channel eps)))

(defn close-channel [^ManagedChannel ch]
  (-> ch (.shutdown) (.awaitTermination 5 TimeUnit/SECONDS)))

(defn close-all-channels [channels]
  (doseq [^ManagedChannel ch channels]
    (close-channel ch)))

;; ── Error classification ─────────────────────────────────────────────────────

(def ^:private retryable-codes
  #{Error$ErrorCode/CLUSTER_UNAVAILABLE
    Error$ErrorCode/NOT_LEADER
    Error$ErrorCode/LEADER_CHANGED
    Error$ErrorCode/CONNECTION_TIMEOUT
    Error$ErrorCode/RETRY_REQUIRED
    Error$ErrorCode/PROPOSE_FAILED})

(defn- classify-error-code [^Error$ErrorCode code]
  (cond
    (= code Error$ErrorCode/SUCCESS)     :ok
    (contains? retryable-codes code)     :info
    :else                                :fail))

;; ── Stub helpers ─────────────────────────────────────────────────────────────

(def ^:private client-id 42)
(def ^:private deadline-secs 5)

(defn- blocking-stub [^ManagedChannel ch]
  (-> (RaftClientServiceGrpc/newBlockingStub ch)
      (.withDeadlineAfter deadline-secs TimeUnit/SECONDS)))

;; ── Failover ─────────────────────────────────────────────────────────────────

(defn- with-failover
  "Tries op (fn [ch]) on each channel in shuffled order until a non-:info result.
   Shuffling spreads load across nodes; on node kill the dead channel is skipped
   after one timeout, and surviving nodes answer the operation."
  [channels op]
  (loop [[ch & remaining] (shuffle (vec channels))]
    (let [result (op ch)]
      (if (and (= :info (:type result)) (seq remaining))
        (recur remaining)
        result))))

;; ── Public API ───────────────────────────────────────────────────────────────

(defn put!
  "Write key→value. Returns :ok on success, :info if outcome unknown, :fail on definite error."
  [channels key val]
  (with-failover channels
    (fn [^ManagedChannel ch]
      (try
        (let [insert  (-> (ClientApi$WriteCommand$Insert/newBuilder)
                          (.setKey (encode-u64 key))
                          (.setValue (encode-u64 val))
                          (.build))
              cmd     (-> (ClientApi$WriteCommand/newBuilder)
                          (.setInsert insert)
                          (.build))
              req     (-> (ClientApi$ClientWriteRequest/newBuilder)
                          (.setClientId client-id)
                          (.setCommand cmd)
                          (.build))
              resp    (.handleClientWrite (blocking-stub ch) req)
              code    (.getError resp)]
          (if (= code Error$ErrorCode/SUCCESS)
            {:type :ok}
            {:type (classify-error-code code) :error (str code)}))
        (catch StatusRuntimeException e
          (if (= (.getCode (.getStatus e)) io.grpc.Status$Code/DEADLINE_EXCEEDED)
            {:type :info :error :deadline-exceeded}
            {:type :info :error (str (.getStatus e))}))
        (catch Exception e
          {:type :info :error (str e)})))))

(defn lget
  "Linearizable read of key. Returns {:type :ok :value v} or nil-value on KEY_NOT_EXIST,
   :info if outcome unknown, :fail on definite error."
  [channels key]
  (with-failover channels
    (fn [^ManagedChannel ch]
      (try
        (let [req  (-> (ClientApi$ClientReadRequest/newBuilder)
                       (.setClientId client-id)
                       (.addKeys (encode-u64 key))
                       (.setConsistencyPolicy ClientApi$ReadConsistencyPolicy/READ_CONSISTENCY_POLICY_LINEARIZABLE_READ)
                       (.build))
              resp (.handleClientRead (blocking-stub ch) req)
              code (.getError resp)]
          (cond
            (= code Error$ErrorCode/SUCCESS)
            (let [results (-> resp .getReadData .getResultsList)]
              {:type :ok :value (when (seq results)
                                  (decode-u64 (.getValue (first results))))})

            (= code Error$ErrorCode/KEY_NOT_EXIST)
            {:type :ok :value nil}

            :else
            {:type (classify-error-code code) :error (str code)}))
        (catch StatusRuntimeException e
          (if (= (.getCode (.getStatus e)) io.grpc.Status$Code/DEADLINE_EXCEEDED)
            {:type :info :error :deadline-exceeded}
            {:type :info :error (str (.getStatus e))}))
        (catch Exception e
          {:type :info :error (str e)})))))

(defn cas!
  "Atomic compare-and-swap. Returns {:type :ok :swapped true/false},
   :info if outcome unknown, :fail on definite error."
  [channels key expected new-val]
  (with-failover channels
    (fn [^ManagedChannel ch]
      (try
        (let [cas  (-> (ClientApi$WriteCommand$CompareAndSwap/newBuilder)
                       (.setKey (encode-u64 key))
                       (.setExpectedValue (encode-u64 expected))
                       (.setNewValue (encode-u64 new-val))
                       (.build))
              cmd  (-> (ClientApi$WriteCommand/newBuilder)
                       (.setCompareAndSwap cas)
                       (.build))
              req  (-> (ClientApi$ClientWriteRequest/newBuilder)
                       (.setClientId client-id)
                       (.setCommand cmd)
                       (.build))
              resp (.handleClientWrite (blocking-stub ch) req)
              code (.getError resp)]
          (cond
            (= code Error$ErrorCode/SUCCESS)
            {:type :ok :swapped (-> resp .getWriteResult .getSucceeded)}

            ;; CAS failure (expected mismatch) is a definite :ok false
            (= code Error$ErrorCode/STALE_OPERATION)
            {:type :ok :swapped false}

            :else
            {:type (classify-error-code code) :error (str code)}))
        (catch StatusRuntimeException e
          (if (= (.getCode (.getStatus e)) io.grpc.Status$Code/DEADLINE_EXCEEDED)
            {:type :info :error :deadline-exceeded}
            {:type :info :error (str (.getStatus e))}))
        (catch Exception e
          {:type :info :error (str e)})))))
