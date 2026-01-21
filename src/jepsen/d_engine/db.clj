(ns jepsen.d-engine.db
  "Database setup and automation for d-engine"
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [jepsen [control :as c]
             [core :as jepsen]
             [db :as db]]
            [jepsen.control.util :as cu]))

(def dir "/opt/d-engine")
(def binary (or (System/getenv "D_ENGINE_BINARY")
                "three-nodes-embedded-linux-amd64"))
(def ctl-binary (or (System/getenv "D_ENGINE_CTL_BINARY")
                    "dengine_ctl-linux-amd64"))
(def s3-base-url (System/getenv "S3_BASE_URL"))
(def logfile (str dir "/d-engine.log"))
(def pidfile (str dir "/d-engine.pid"))

(defn node-id
  "Maps node names to numeric IDs (node1->1, node2->2, node3->3)"
  [node]
  (case node
    "node1" 1
    "node2" 2
    "node3" 3
    (throw (ex-info "Unknown node" {:node node}))))

(defn client-port
  "Client port for a given node"
  [node]
  (+ 8080 (node-id node)))

(defn raft-port
  "Raft port for a given node"
  [node]
  (+ 9080 (node-id node)))

(defn health-port
  "Health check port for a given node"
  [node]
  (+ 10000 (node-id node)))

(defn data-dir
  "Data directory for this node"
  [node]
  (str dir "/db/" (node-id node)))

(defn config-path
  "Config file path for this node"
  [node]
  (str dir "/config/n" (node-id node) ".toml"))

(defn wipe!
  "Wipes data files on the current node"
  [test node]
  (c/su
   (c/exec :rm :-rf (data-dir node))
   (c/exec :rm :-rf logfile)))

(defn start!
  "Starts d-engine on the given node"
  [test node]
  (c/su
   (cu/start-daemon!
    {:logfile logfile
     :pidfile pidfile
     :chdir   dir}
    (str dir "/bin/" binary)
    :--port (client-port node)
    :--health-port (health-port node)
    :--config-path (config-path node))))

(defn kill!
  "Kills d-engine process"
  []
  (c/su (cu/stop-daemon! binary pidfile)))

(defrecord DB []
  db/DB
  (setup! [db test node]
    (info node "setting up d-engine")
    (c/su
      ; Create directory structure
     (c/exec :mkdir :-p dir)
     (c/exec :mkdir :-p (str dir "/bin"))
     (c/exec :mkdir :-p (str dir "/config"))
     (c/exec :mkdir :-p (data-dir node))

      ; Check if binaries exist (mounted from host), otherwise download from S3
     (when s3-base-url
       (when-not (cu/exists? (str dir "/bin/" binary))
         (info node "downloading binary from S3")
         (c/exec :curl :-L :-o (str dir "/bin/" binary)
                 (str s3-base-url "/" binary))
         (c/exec :chmod :+x (str dir "/bin/" binary)))

       (when-not (cu/exists? (str dir "/bin/" ctl-binary))
         (info node "downloading dengine_ctl from S3")
         (c/exec :curl :-L :-o (str dir "/bin/" ctl-binary)
                 (str s3-base-url "/" ctl-binary))
         (c/exec :chmod :+x (str dir "/bin/" ctl-binary))))

      ; Create config file dynamically
     (c/exec :echo
             (str "[cluster]\n"
                  "node_id = " (node-id node) "\n"
                  "listen_address = \"0.0.0.0:" (raft-port node) "\"\n"
                  "initial_cluster = [\n"
                  "    { id = 1, address = \"node1:" (raft-port "node1") "\", role = 1, status = 2 },\n"
                  "    { id = 2, address = \"node2:" (raft-port "node2") "\", role = 1, status = 2 },\n"
                  "    { id = 3, address = \"node3:" (raft-port "node3") "\", role = 1, status = 2 },\n"
                  "]\n"
                  "db_root_dir = \"" (data-dir node) "\"\n\n"
                  "[raft.snapshot]\n"
                  "enable = false\n")
             :> (config-path node)))

    (db/start! db test node)

    ; Wait for node to be ready
    (Thread/sleep 5000)
    (info node "d-engine started"))

  (teardown! [db test node]
    (info node "tearing down d-engine")
    (kill!)
    (c/su
      ; Only delete data and config, preserve /bin (which is mounted from host)
      (c/exec :rm :-rf (data-dir node))
      (c/exec :rm :-rf (str dir "/config"))
      (c/exec :rm :-f logfile pidfile)))

  db/LogFiles
  (log-files [_ test node]
    {logfile "d-engine.log"})

  db/Process
  (start! [_ test node]
    (start! test node))

  (kill! [_ test node]
    (kill!))

  db/Pause
  (pause!  [_ test node] (c/su (cu/grepkill! :stop binary)))
  (resume! [_ test node] (c/su (cu/grepkill! :cont binary))))

(defn db
  "Creates a new d-engine DB"
  []
  (DB.))
