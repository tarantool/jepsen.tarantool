(ns tarantool.counter
  "Incrementing a counter."
  (:require [jepsen [client :as client]
                    [checker :as checker]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [clojure.tools.logging :refer [debug info warn]]
            [next.jdbc :as j]
            [next.jdbc.sql :as sql]
            [tarantool [client :as cl]
                       [db :as db]]
            [knossos.op :as op]))

(def table-name "counter")

(defrecord CounterClient [conn]
  client/Client

  (open! [this test node]
    (let [conn (cl/open node test)]
      (assert conn)
      (assoc this :conn conn :node node)))

  (setup! [this test node]
    (let [conn (cl/open node test)]
      (assert conn)
      (Thread/sleep 10000) ; wait for leader election and joining to a cluster
      (when (= node (first (db/primaries test)))
        (cl/with-conn-failure-retry conn
          (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name
                            " (id INT NOT NULL PRIMARY KEY,
                            count INT NOT NULL)")]))
          (sql/insert! conn table-name {:id 0 :count 0})
          (let [table (clojure.string/upper-case table-name)]
            (j/execute! conn [(str "SELECT LUA('return box.space." table ":alter{ is_sync = true } or 1')")])))
      (assoc this :conn conn :node node)))

  (invoke! [this test op]
    (cl/with-error-handling op
      (cl/with-txn-aborts op
        (case (:f op)
          :add (let [con (cl/open (first (db/primaries test)) test)]
                 (do (j/execute! con
                      [(str "UPDATE " table-name " SET count = count + ? WHERE id = 0") (:value op)]))
               (assoc op :type :ok))

          :read (let [value (:COUNT
                  (first (sql/query conn
                    [(str "SELECT count FROM " table-name " WHERE id = 0")])))]
                (assoc op :type :ok :value value))))))

  (teardown! [_ test]
    (when-not (:leave-db-running? test)
      (cl/with-conn-failure-retry conn
        (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name)]))))

  (close! [_ test]))

(def add {:type :invoke :f :add :value 1})
(def sub {:type :invoke :f :add :value -1})
(def r   {:type :invoke :f :read})

(defn with-op-index
  "Append :op-index integer to every operation emitted by the given generator.
  Value starts at 1 and increments by 1 for every subsequent emitted operation."
  [gen]
  (let [ctr (atom 0)]
    (gen/map (fn add-op-index [op]
               (assoc op :op-index (swap! ctr inc)))
             gen)))

(defn workload-inc
  [opts]
  {:client    (CounterClient. nil)
   :generator (->> (repeat 100 add)
                   (cons r)
                   gen/mix
                   (gen/delay 1/10)
                   (with-op-index))
   :checker   (checker/compose
                {:timeline (timeline/html)
                 :counter  (checker/counter)})})
