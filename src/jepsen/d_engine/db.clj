(ns jepsen.d_engine.db
  "Database lifecycle for d-engine Docker nodes.
  teardown! kills demo; setup! restarts it so the cluster reforms before each test.
  kill!/start!/pause!/resume! operate via SSH on the running demo process."
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]]
            [jepsen.control.util :as cu]
            [jepsen.d_engine.client :as grpc]))

(def binary  "demo")
(def logfile "/app/logs/d-engine-jepsen.log")

(defn node-id [node]
  (case node
    "node1" 1 "node2" 2 "node3" 3
    "node4" 4 "node5" 5
    (throw (ex-info "Unknown node" {:node node}))))

(defn kill!
  "Kills the demo process on the current node via SIGKILL."
  []
  (c/su (cu/grepkill! binary)))

(defn start!
  "Starts the demo binary. config-override optionally replaces the default
  /app/config/nN path (e.g. \"/app/config/n4-readonly\" for ReadOnly mode)."
  ([node] (start! node nil))
  ([node config-override]
   (let [id    (node-id node)
         conf  (or config-override (str "/app/config/n" id))
         log   (str "/app/logs/" id)
         mport (+ 8080 id)]
     (c/su
       (cu/start-daemon!
         {:logfile logfile
          :pidfile "/app/demo.pid"
          :chdir   "/app"
          :env     {"CONFIG_PATH"  conf
                    "LOG_DIR"      log
                    "METRICS_PORT" (str mport)
                    "RUST_LOG"     "demo=debug,d_engine=debug,hyper=warn,sled=warn"}}
         "/usr/local/bin/demo")))))

(defrecord DB []
  db/DB
  (setup! [_ test node]
    ; Demo is running from Docker startup — cluster lifecycle is managed by
    ; restart-stack (docker compose down/up), not by Jepsen setup/teardown.
    (info node "d-engine already running (Docker mode), skipping setup"))

  (teardown! [_ test node]
    ; Do NOT kill demo here. teardown! runs before setup! at test start,
    ; so killing here would leave the cluster dead for the entire test.
    ; restart-stack resets state between test runs.
    (info node "d-engine teardown skipped (Docker mode)"))

  db/LogFiles
  (log-files [_ test node]
    {logfile "d-engine-jepsen.log"})

  db/Primary
  (setup-primary! [_ test node])
  (primaries [_ test]
    (let [eps (:endpoints test)]
      (->> (:nodes test)
           (pmap (fn [node]
                   (let [ep (grpc/find-endpoint eps node)
                         ch (try (grpc/open-channel ep)
                                 (catch Exception _ nil))]
                     (when ch
                       (try
                         (when (= :ok (:type (grpc/lget [ch] 1)))
                           node)
                         (finally (grpc/close-channel ch)))))))
           (remove nil?))))

  db/Process
  (start! [_ test node] (start! node nil))
  (kill!  [_ test node] (kill!))

  db/Pause
  (pause!  [_ test node] (c/su (cu/grepkill! :stop  binary)))
  (resume! [_ test node] (c/su (cu/grepkill! :cont  binary))))

(defn db [] (DB.))
