(ns tarantool.sets
  "Set (test inserts a series of unique numbers as separate instances, one per
   transaction, and attempts to read them back through an index), serializability."

  (:require [jepsen [client :as client]
                    [checker :as checker]
                    [generator :as gen]
                    [independent :as independent]]
            [clojure.tools.logging :refer [info warn]]
            [next.jdbc :as j]
            [next.jdbc.sql :as sql]
            [tarantool [client :as cl]
                       [db :as db]]
            [jepsen.core :as jepsen]
            [knossos.op :as op]))

(def table-name "sets")

(defrecord SetClient [conn]
  client/Client

  (open! [this test node]
    (let [conn (cl/open node test)]
      (assert conn)
      (assoc this :conn conn :node node)))

  (setup! [this test node]
    (let [conn (cl/open node test)]
      (assert conn)
      (Thread/sleep 10000) ; wait for leader election and joining to a cluster
      (if (= node (first (db/primaries test)))
        (cl/with-conn-failure-retry conn
          (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name
                            " (id INT NOT NULL PRIMARY KEY AUTOINCREMENT,
                            value INT NOT NULL)")])
          (j/execute! conn [(str "SELECT LUA('return box.space."
                                 (clojure.string/upper-case table-name)
                                 ":alter{ is_sync = true } or 1')")])))
    (assoc this :conn conn :node node)))

  (invoke! [this test op]
    (let [[k v] (:value op)]
    (cl/with-error-handling op
      (cl/with-txn-aborts op
        (case (:f op)
          :add (let [con (cl/open (first (db/primaries test)) test)]
                 (do (sql/insert! con table-name {:value v})
                    (assoc op :type :ok)))

          :read (->> (sql/query conn [(str "SELECT * FROM " table-name)])
                     (mapv :VALUE)
                     (assoc op :type :ok, :value)))))))

  (teardown! [_ test]
    (when-not (:leave-db-running? test)
      (cl/with-conn-failure-retry conn
        (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name)]))))

  (close! [_ test]))

(defn workload
  [opts]
  (let [max-key (atom 0) c (:concurrency opts)]
    {:client  (SetClient. nil)
     :checker (independent/checker (checker/set-full {:linearizable? true}))
     :generator (independent/concurrent-generator
                  c
                  (range)
                  (fn [k]
                    (swap! max-key max k)
                    (->> (range 10000)
                         (map (fn [x] {:type :invoke, :f :add, :value x}))
                         gen/seq
                         (gen/stagger 1/10))))
     :final-generator (gen/derefer
                        (delay
                          (locking keys
                            (independent/concurrent-generator
                              c
                              (range (inc @max-key))
                              (fn [k]
                                (gen/stagger 10
                                   (gen/each
                                     (gen/once {:type :invoke
                                                :f    :read}))))))))}))
