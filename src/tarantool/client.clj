(ns tarantool.client
  "Access to Tarantool with JDBC connector"
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [next.jdbc :as j]
            [next.jdbc.connection :as connection]))

(def max-timeout "Longest timeout, in ms" 30000)

(defn conn-spec
   "JDBC connection spec for a node."
   [node]
   {:classname "org.tarantool.jdbc.SQLDriver"
    :dbtype "tarantool"
    :dbname "jepsen"
    :host (name node)
    :port 3301
    :user "jepsen"
    :password "jepsen"
    :loginTimeout  (/ max-timeout 1000)
    :connectTimeout (/ max-timeout 1000)
    :socketTimeout (/ max-timeout 1000)})

(defn open
  "Opens a connection to the given node."
  [node test]
  (j/get-datasource (conn-spec node)))

(defn read-v-by-k
  "Reads the current value of a key."
  [conn k]
  (first (vals (first (j/execute! conn ["SELECT _READ(?, 'JEPSEN')" k])))))

(defn write-v-by-k
  "Writes the current value of a key."
  [conn k v]
  (j/execute! conn ["SELECT _WRITE(?, ?, 'JEPSEN')"
                    k v]))

(defn compare-and-set
  [conn id old new]
  (first (vals (first (j/execute! conn ["SELECT _CAS(?, ?, ?, 'JEPSEN')"
                                        id old new])))))
