(ns jepsen.d_engine.nemesis
  "Nemesis implementations for d-engine testing"
  (:require [clojure.tools.logging :refer [info]]
            [jepsen.nemesis :as nemesis]
            [jepsen.nemesis.combined :as nc]
            [jepsen.generator :as gen]))

(defn nemesis-package
  "Constructs a nemesis package for d-engine, instantiating only the requested fault types.

  Uses nc/nemesis-packages selectively to avoid unconditional setup! of all nemeses
  (clock nemesis installs build-essential, bitflip downloads a binary — both fail in
  our Docker environment which has no apt lists and no internet access from nodes).

  Supported faults: :partition, :kill, :pause"
  [opts]
  ; kill and pause are both handled by nc/db-package in Jepsen 0.3.x
  (let [faults (set (:faults opts))
        pkgs   (cond-> []
                 (:partition faults)              (conj (nc/partition-package opts))
                 (some faults #{:kill :pause})    (conj (nc/db-package opts)))]
    (nc/compose-packages pkgs)))

