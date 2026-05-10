(ns jepsen.d_engine.db
  "Database lifecycle for d-engine Docker nodes.
  setup!/teardown! are no-ops — Docker handles installation and config.
  kill!/start!/pause!/resume! operate via SSH on the running demo process."
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [control :as c]
                    [db :as db]]
            [jepsen.control.util :as cu]))

(def binary  "demo")
(def logfile "/app/logs/d-engine-jepsen.log")

(defn node-id [node]
  (case node
    "node1" 1 "node2" 2 "node3" 3
    (throw (ex-info "Unknown node" {:node node}))))

(defn kill!
  "Kills the demo process on the current node via SIGKILL."
  []
  (c/su (cu/grepkill! binary)))

(defn start!
  "Restarts the demo binary. Env vars are reconstructed from node identity
  since SSH sessions don't inherit docker-compose environment."
  [node]
  (let [id    (node-id node)
        conf  (str "config/n" id)
        log   (str "./logs/" id)
        mport (+ 8080 id)]
    (c/su
      (c/exec :bash :-c
        (str "nohup env"
             " CONFIG_PATH=" conf
             " LOG_DIR=" log
             " METRICS_PORT=" mport
             " RUST_LOG=demo=debug,d_engine=debug,hyper=warn,sled=warn"
             " /usr/local/bin/demo >> " logfile " 2>&1 &")))))

(defrecord DB []
  db/DB
  (setup! [_ test node]
    ; Docker already started demo with correct config — nothing to install.
    (info node "d-engine running in Docker, skipping setup"))

  (teardown! [_ test node]
    ; Leave data dirs intact (mounted from host). Just stop the process.
    (info node "tearing down d-engine")
    (kill!))

  db/LogFiles
  (log-files [_ test node]
    {logfile "d-engine-jepsen.log"})

  db/Process
  (start! [_ test node] (start! node))
  (kill!  [_ test node] (kill!))

  db/Pause
  (pause!  [_ test node] (c/su (cu/grepkill! :stop  binary)))
  (resume! [_ test node] (c/su (cu/grepkill! :cont  binary))))

(defn db [] (DB.))
