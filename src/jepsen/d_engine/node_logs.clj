(ns jepsen.d_engine.node-logs
  "Final scan of the node logs for fatal errors.

  The workload checkers only see what clients observed. A node that hit a fatal
  error (or panicked) while the rest of the cluster kept a quorum leaves every
  client operation looking normal, so the run would pass without anyone noticing.

  The scan runs as a nemesis operation at the end of a run and records what it
  found in the history; the checker reads it from there. The node logs are far too
  large to download (tens to hundreds of MB), so grep runs on the node and only the
  matching lines come back."
  (:require [clojure.string :as str]
            [jepsen [checker :as checker]
                    [control :as c]
                    [nemesis :as nemesis]]
            [jepsen.d_engine.db :as db]))

(def fatal-pattern
  "Messages d-engine logs when a node gives up: the raft loop shutting down on a
  fatal error, a failed hard-state or log flush, a poisoned log."
  "Fatal error from|Fatal error in [a-z_]+, shutting down|\\(fatal\\)|storage is poisoned")

(def shared-log
  "stdout/stderr of every demo process. /app/logs is one volume shared by all
  nodes, so this is the same file everywhere and is scanned once."
  "/app/logs/d-engine-jepsen.log")

(defn node-log
  "The tracing log of one node."
  [node]
  (str "/app/logs/" (db/node-id node) "/d.log"))

(def commit-file
  "Written into the node image at build time (see the Dockerfile's GIT_SHA)."
  "/etc/d-engine-commit")

(def ^:private max-lines 20)

(defn strip-ansi
  "Removes terminal colour codes from a log line."
  [s]
  (str/replace s #"\u001b\[[0-9;]*m" ""))

(defn log-lines
  "Non-blank lines of grep output, without colour codes."
  [out]
  (->> (str/split-lines (or out ""))
       (map strip-ansi)
       (remove str/blank?)
       vec))

(defn panic-blocks
  "Groups the output of `grep -A1 'panicked at'` into one string per panic: the
  location line and the message line after it. Group separators are dropped."
  [lines]
  (loop [[l & more] lines
         acc        []]
    (cond
      (nil? l)                      acc
      (str/includes? l "panicked at") (recur (rest more)
                                             (conj acc (str/join " | " (remove nil? [l (first more)]))))
      :else                         (recur more acc))))

(defn- grep
  "Runs a grep pipeline on the current node and returns its matching lines. A
  missing file or no match yields no lines instead of an error."
  [command]
  (log-lines (c/exec :sh :-c (str command " 2>/dev/null | head -n " max-lines "; true"))))

(defn node-commit
  "The commit the current node's binary was built from, or \"unknown\" for an image
  built without one. Run inside c/on."
  []
  (or (first (grep (str "cat " commit-file)))
      "unknown"))

(defn scan-node
  "What the logs of the current node say, and which commit it runs. Run inside
  c/on. The shared log is only scanned when scan-shared? is true."
  [node scan-shared?]
  {:fatal  (grep (str "grep -aE '" fatal-pattern "' " (node-log node)))
   :panics (if scan-shared?
             (panic-blocks (grep (str "grep -a -A1 'panicked at' " shared-log)))
             [])
   :commit (node-commit)})

(defn with-log-scan
  "Wraps a nemesis: an operation with :f :scan-logs scans every node and returns
  the findings as its :value; everything else goes to the wrapped nemesis."
  [inner]
  (reify nemesis/Nemesis
    (setup! [_ test]
      (with-log-scan (nemesis/setup! inner test)))
    (invoke! [_ test op]
      (if (= :scan-logs (:f op))
        (let [first-node (first (:nodes test))]
          (assoc op :value
                 (c/on-nodes test
                             (fn [_ node] (scan-node node (= node first-node))))))
        (nemesis/invoke! inner test op)))
    (teardown! [_ test]
      (nemesis/teardown! inner test))))

(def scan-op
  "The operation that triggers the scan."
  {:type :info, :f :scan-logs, :value nil})

(defn findings
  "Flat list of problems in one scan result (a map of node to scan-node output)."
  [scan]
  (vec (for [[node {:keys [fatal panics]}] (sort-by key scan)
             [kind lines] [[:fatal fatal] [:panic panics]]
             line lines]
         {:node node :kind kind :text line})))

(defn commits
  "The distinct commits the scanned nodes report, \"unknown\" included."
  [scans]
  (->> scans (mapcat vals) (keep :commit) distinct vec))

(defn checker
  "Fails when a node logged a fatal error or panicked, or when the nodes of one
  run do not run the same commit. Nodes that report \"unknown\" are ignored for the
  commit comparison: an image built without a commit says nothing. Unknown when
  the run recorded no scan, for instance because the harness failed before the
  end. The commits the nodes run are part of the result either way."
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [scans (->> history
                       (filter #(and (= :scan-logs (:f %))
                                     (= :info (:type %))
                                     (map? (:value %))))
                       (map :value))]
        (if (empty? scans)
          {:valid? :unknown
           :error  "no node log scan was recorded"}
          (let [problems (mapcat findings scans)
                cs       (commits scans)]
            (cond
              (seq problems)
              {:valid?   false
               :count    (count problems)
               :findings (vec (take max-lines problems))
               :commits  cs}

              (> (count (remove #{"unknown"} cs)) 1)
              {:valid?  false
               :error   "nodes run different commits"
               :commits cs}

              :else
              {:valid?  true
               :nodes   (count (first scans))
               :commits cs})))))))
