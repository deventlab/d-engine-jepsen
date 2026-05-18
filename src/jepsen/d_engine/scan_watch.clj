(ns jepsen.d_engine.scan-watch
  "Scan-then-watch reconnection workload.

   Verifies three invariants under fault injection:
   1. No gap    — every committed PUT is eventually observed by at least one reader
                  (either in a scan snapshot or a subsequent watch event).
   2. No phantom — every value observed by a reader corresponds to a committed write.
   3. Revision monotonicity — within one watch session, event revisions strictly increase;
                              the first watch-event revision >= scan revision.

   Reconnect pattern under test (zero-race-window):
     loop {
       Step 1: register Watch FIRST  → server buffers events from this moment
       Step 2: scan_prefix           → snapshot@revision R
       Step 3: drain Watch buffer    → discard events where revision <= R (already in snapshot)
       Step 4: process new events    → revision > R
       // On CANCELED or stream error → repeat from Step 1
     }

   Writers: PUT /services/payment/node{1..N} with string value \"10.0.0.N:8080\".
            DELETE operations are also injected to exercise tombstone handling.
   Readers: run the scan-then-watch loop, recording every (key, value, revision) they see."
  (:require [clojure.string       :as str]
            [clojure.tools.logging :refer [info warn]]
            [jepsen [checker   :as checker]
                    [client    :as client]
                    [generator :as gen]]
            [jepsen.d_engine.client :as grpc])
  (:import [d_engine.client ClientApi$WatchRequest
                             ClientApi$WatchEventType
                             RaftClientServiceGrpc]
           [io.grpc Status$Code StatusRuntimeException]
           [java.util.concurrent TimeUnit]
           [com.google.protobuf ByteString]))



;; ── Constants ────────────────────────────────────────────────────────────────

(def ^:private prefix-str "/services/payment/")
(def ^:private prefix-bs  (ByteString/copyFromUtf8 prefix-str))
(def ^:private node-count 10)

;; watch stream deadline before we consider it idle and re-connect
(def ^:private watch-poll-ms 3000)

;; ── Encoding helpers ─────────────────────────────────────────────────────────

(defn- node-key
  "Returns ByteString for /services/payment/node{n}"
  ^ByteString [n]
  (ByteString/copyFromUtf8 (str prefix-str "node" n)))

(defn- node-val
  "Returns ByteString for 10.0.0.{n}:8080"
  ^ByteString [n]
  (ByteString/copyFromUtf8 (str "10.0.0." n ":8080")))

(defn- bs->str [^ByteString bs] (when bs (.toStringUtf8 bs)))

;; ── Watch helpers ─────────────────────────────────────────────────────────────

(defn- open-watch-stream
  "Opens a server-streaming Watch on ch for the given prefix.
   Returns the blocking Iterator, or nil on failure."
  [ch]
  (try
    (let [req  (-> (ClientApi$WatchRequest/newBuilder)
                   (.setClientId 77)
                   (.setKey prefix-bs)
                   (.setPrefix true)
                   (.build))
          stub (-> (RaftClientServiceGrpc/newBlockingStub ch)
                   (.withDeadlineAfter watch-poll-ms TimeUnit/MILLISECONDS))]
      (.watch stub req))
    (catch Exception e
      (warn "open-watch-stream error" (str e))
      nil)))

(defn- drain-watch-until-deadline
  "Drains events from iter until deadline or stream error.
   Returns a vector of {:key k :value v :revision r :type t} maps.
   Stops and returns on DEADLINE_EXCEEDED (expected — we use it as poll window)."
  [iter scan-revision]
  (loop [events []]
    (let [[continue? events']
          (try
            (if (.hasNext iter)
              (let [resp       (.next iter)
                    event-rev  (.getRevision resp)
                    event-type (.getEventType resp)]
                (cond
                  ;; CANCELED: server dropped events — signal caller to reconnect
                  (= event-type ClientApi$WatchEventType/WATCH_EVENT_TYPE_CANCELED)
                  (do (info "watch CANCELED — triggering reconnect")
                      [false events])

                  ;; skip events already covered by the scan snapshot
                  (<= event-rev scan-revision)
                  [true events]

                  ;; PUT
                  (= event-type ClientApi$WatchEventType/WATCH_EVENT_TYPE_PUT)
                  [true (conj events {:key      (bs->str (.getKey resp))
                                      :value    (bs->str (.getValue resp))
                                      :revision event-rev
                                      :type     :put})]

                  ;; DELETE
                  (= event-type ClientApi$WatchEventType/WATCH_EVENT_TYPE_DELETE)
                  [true (conj events {:key      (bs->str (.getKey resp))
                                      :revision event-rev
                                      :type     :delete})]

                  :else [true events]))
              [false events])
            (catch StatusRuntimeException e
              ;; DEADLINE_EXCEEDED is expected — poll window expired
              (when-not (= (.getCode (.getStatus e)) Status$Code/DEADLINE_EXCEEDED)
                (warn "watch stream error" (str (.getStatus e))))
              [false events])
            (catch Exception e
              (warn "watch drain error" (str e))
              [false events]))]
      (if continue?
        (recur events')
        events'))))

;; ── Client ────────────────────────────────────────────────────────────────────

(defrecord ScanWatchClient [endpoints channels]
  client/Client

  (open! [this test node]
    (assoc this :channels (grpc/open-all-channels endpoints)))

  (setup! [this test])

  (invoke! [this test op]
    (case (:f op)
      :put
      (let [{:keys [node]} (:value op)
            res (grpc/put-bytes! channels (node-key node) (node-val node))]
        (assoc op :type (:type res) :error (:error res)))

      :delete
      (let [{:keys [node]} (:value op)
            res (grpc/delete-bytes! channels (node-key node))]
        (assoc op :type (:type res) :error (:error res)))

      :scan-watch
      ;; Run one full scan-then-watch reconnect cycle.
      ;; Returns all observations (scan entries + watch events) with their revisions.
      (let [ch (rand-nth channels)]
        (if-let [iter (open-watch-stream ch)]
          ;; Step 1 succeeded — Watch stream open; now scan
          (let [scan-res (grpc/scan-prefix channels prefix-bs)]
            (if (= :ok (:type scan-res))
              (let [scan-rev  (:revision scan-res)
                    ;; Encode scan snapshot entries as observations
                    scan-obs  (mapv (fn [{:keys [key value]}]
                                      {:key      (bs->str key)
                                       :value    (bs->str value)
                                       :revision scan-rev
                                       :type     :put
                                       :source   :scan})
                                    (:entries scan-res))
                    ;; Step 3+4: drain watch events with revision > scan-rev
                    watch-obs (mapv #(assoc % :source :watch)
                                    (drain-watch-until-deadline iter scan-rev))]
                (assoc op
                       :type  :ok
                       :value {:scan-revision scan-rev
                               :observations  (into scan-obs watch-obs)}))
              ;; scan failed
              (assoc op :type :info :error (str "scan failed: " (:error scan-res)))))
          ;; Watch stream failed to open
          (assoc op :type :info :error "watch stream open failed")))))

  (teardown! [this test])

  (close! [this test]
    (when channels (grpc/close-all-channels channels))))

;; ── Generators ───────────────────────────────────────────────────────────────

(defn put-op [_ _]
  (let [n (inc (rand-int node-count))]
    {:type :invoke :f :put :value {:node n}}))

(defn delete-op [_ _]
  (let [n (inc (rand-int node-count))]
    {:type :invoke :f :delete :value {:node n}}))

(defn scan-watch-op [_ _]
  {:type :invoke :f :scan-watch :value nil})

;; ── Checker ───────────────────────────────────────────────────────────────────

(defn checker []
  (reify checker/Checker
    (check [_ test history opts]
      ;; Committed writes: all :ok :put ops (key + value pair that is durable)
      (let [committed-puts
            (->> history
                 (filter #(and (= :put (:f %)) (= :ok (:type %))))
                 (map (fn [op]
                        (let [n (-> op :value :node)]
                          {:key   (str prefix-str "node" n)
                           :value (str "10.0.0." n ":8080")})))
                 set)

            ;; All observations from successful scan-watch ops
            all-observations
            (->> history
                 (filter #(and (= :scan-watch (:f %)) (= :ok (:type %))))
                 (mapcat (fn [op] (-> op :value :observations)))
                 (filter #(= :put (:type %))))

            observed-set
            (set (map #(select-keys % [:key :value]) all-observations))

            ;; Invariant 1: no gap — every committed PUT was observed somewhere
            gaps
            (->> committed-puts
                 (remove #(contains? observed-set %))
                 vec)

            ;; Invariant 2: no phantom — every observed PUT corresponds to a committed write
            phantoms
            (->> all-observations
                 (remove #(contains? committed-puts (select-keys % [:key :value])))
                 (take 10)
                 vec)

            ;; Invariant 3: revision monotonicity within each watch session
            ;; (each scan-watch op is one session; check watch-sourced events are monotone)
            monotonicity-errors
            (->> history
                 (filter #(and (= :scan-watch (:f %)) (= :ok (:type %))))
                 (mapcat
                   (fn [op]
                     (let [obs   (-> op :value :observations)
                           watch (->> obs (filter #(= :watch (:source %))) (map :revision))]
                       (->> (partition 2 1 watch)
                            (filter (fn [[a b]] (>= a b)))
                            (map (fn [[a b]] {:prev a :next b :process (:process op)}))))))
                 (take 10)
                 vec)

            ;; Invariant 3b: first watch-event revision >= scan revision
            handoff-errors
            (->> history
                 (filter #(and (= :scan-watch (:f %)) (= :ok (:type %))))
                 (mapcat
                   (fn [op]
                     (let [scan-rev (-> op :value :scan-revision)
                           first-watch-rev (->> (-> op :value :observations)
                                                (filter #(= :watch (:source %)))
                                                (map :revision)
                                                first)]
                       (when (and first-watch-rev (< first-watch-rev scan-rev))
                         [{:scan-revision scan-rev
                           :first-watch-revision first-watch-rev
                           :process (:process op)}]))))
                 (take 10)
                 vec)

            valid? (and (empty? gaps)
                        (empty? phantoms)
                        (empty? monotonicity-errors)
                        (empty? handoff-errors))]

        (cond-> {:valid?            valid?
                 :committed-puts    (count committed-puts)
                 :total-observations (count all-observations)}
          (seq gaps)               (assoc :gaps               (vec (take 10 gaps)))
          (seq phantoms)           (assoc :phantoms           phantoms)
          (seq monotonicity-errors)(assoc :monotonicity-errors monotonicity-errors)
          (seq handoff-errors)     (assoc :handoff-errors     handoff-errors))))))

;; ── Workload ─────────────────────────────────────────────────────────────────

(defn workload [opts]
  {:client          (ScanWatchClient. (:endpoints opts) nil)
   :checker         (checker)
   :generator       (gen/mix [put-op delete-op scan-watch-op])
   :final-generator (gen/once scan-watch-op)})
