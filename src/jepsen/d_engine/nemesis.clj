(ns jepsen.d-engine.nemesis
  "Nemesis implementations for d-engine testing"
  (:require [clojure.tools.logging :refer [info]]
            [jepsen.nemesis :as nemesis]
            [jepsen.nemesis.combined :as nc]
            [jepsen.generator :as gen]))

(defn nemesis-package
  "Constructs a nemesis and generators for d-engine using Jepsen's combined nemesis framework.

  Options:
    :faults    - Set of fault types to inject (e.g., #{:partition :kill :pause})
    :interval  - Time between nemesis operations (seconds)
    :partition - Partition configuration (e.g., {:targets [:majority]})
    :pause     - Process pause configuration (e.g., {:targets [:all]})
    :kill      - Process kill configuration (e.g., {:targets [:all]})"
  [opts]
  (let [opts (update opts :faults set)]
    (-> (nc/nemesis-packages opts)
        nc/compose-packages)))

(defn full-generator
  "Generator for mixed fault injection.
  Cycles through network partitions, process kills, and process pauses."
  [{:keys [interval] :or {interval 5}}]
  (gen/phases
   (cycle [(gen/sleep interval)
           {:type :info, :f :start-partition}
           (gen/sleep interval)
           {:type :info, :f :stop-partition}

           (gen/sleep interval)
           {:type :info, :f :kill}
           (gen/sleep (/ interval 2))
           {:type :info, :f :start}

           (gen/sleep interval)
           {:type :info, :f :pause}
           (gen/sleep (/ interval 2))
           {:type :info, :f :resume}])))
