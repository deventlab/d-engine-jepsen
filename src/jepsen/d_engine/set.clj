(ns jepsen.d-engine.set
  "Set workload: every acknowledged add must survive faults and appear in reads.
   Encodes a set of integers 0-62 as a u64 bitmask; CAS ensures atomic updates."
  (:require [clojure.tools.logging :refer [info]]
            [clojure.string :as str]
            [jepsen [checker :as checker]
                    [client :as client]
                    [control :as c]
                    [generator :as gen]]
            [jepsen.d-engine.db :as d-db]
            [slingshot.slingshot :refer [try+]]))

(def set-key 42)

(def ctl-bin (str d-db/dir "/bin/" d-db/ctl-binary))

(defn parse-long-nil [s]
  (when s
    (try (Long/parseLong (str/trim s))
         (catch NumberFormatException _ nil))))

(defn decode-set
  "Decode a u64 bitmask into a set of present element indices."
  [packed]
  (->> (range 63)
       (filter #(not= 0 (bit-and packed (bit-shift-left 1 %))))
       set))

(defrecord SetClient [session node endpoints]
  client/Client

  (open! [this test node]
    (assoc this :session (c/session node) :node node))

  (setup! [this test]
    (c/with-session node session
      (c/exec ctl-bin :--endpoints endpoints :put (str set-key) "0")))

  (invoke! [this test op]
    (c/with-session node session
      (try+
        (case (:f op)
          :add
          (loop [attempts 0]
            (if (> attempts 50)
              (assoc op :type :fail :error :too-many-retries)
              (let [current  (or (parse-long-nil
                                   (c/exec ctl-bin :--endpoints endpoints
                                           :lget (str set-key)))
                                 0)
                    new-val  (bit-or current (bit-shift-left 1 (long (:value op))))
                    result   (str/trim
                               (c/exec ctl-bin :--endpoints endpoints
                                       :cas (str set-key)
                                       (str current) (str new-val)))]
                (if (= result "true")
                  (assoc op :type :ok)
                  (recur (inc attempts))))))

          :read
          (let [packed (or (parse-long-nil
                             (c/exec ctl-bin :--endpoints endpoints :lget (str set-key)))
                           0)]
            (assoc op :type :ok :value (decode-set packed))))

        (catch [:type :jepsen.control/nonzero-exit] e
          (let [err (str (:err e) (:out e))]
            (cond
              (re-find #"(?i)cluster unavailable|timeout|deadline exceeded|not leader" err)
              (assoc op :type :fail :error [:unavailable err])
              :else
              (assoc op :type :fail :error [:unknown err])))))))

  (teardown! [this test])

  (close! [this test]
    (when session (c/disconnect session))))

(defn add-op [_ _] {:type :invoke :f :add :value (rand-int 30)})
(defn read-op [_ _] {:type :invoke :f :read :value nil})

(defn workload [opts]
  {:client    (SetClient. nil nil (:endpoints opts))
   :checker   (checker/set-full)
   :generator (gen/mix [add-op read-op])})
