(ns tarantool.nemesis
  "Nemeses for Tarantool"
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen.nemesis [combined :as nc]]))

(defn nemesis-package
  "Constructs a nemesis and generators for Tarantool"
  [opts]
  (let [opts (update opts :faults set)]
    (nc/nemesis-package opts)))
