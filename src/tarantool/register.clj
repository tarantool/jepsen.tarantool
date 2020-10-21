(ns tarantool.register
  "Run Tarantool tests."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [jepsen [cli :as cli]
                    [client :as client]
                    [checker :as checker]
                    [core :as jepsen]
                    [control :as c]
                    [independent :as independent]
                    [generator :as gen]
                    [util :refer [timeout meh]]]
            [next.jdbc :as j]
            [next.jdbc.sql :as sql]
            [knossos.model :as model]
            [jepsen.checker.timeline :as timeline]
            [tarantool [client :as cl]
                       [db :as db]]))

(def table-name "register")

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defrecord Client [conn]
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
                            " (id INT NOT NULL PRIMARY KEY,
                            value INT NOT NULL)")])
          (let [table (clojure.string/upper-case table-name)]
            (j/execute! conn [(str "SELECT LUA('return box.space." table ":alter{ is_sync = true } or 1')")]))))
      (assoc this :conn conn :node node)))

  (invoke! [this test op]
   (let [[k value] (:value op)]
     (case (:f op)

       :read (let [r (first (sql/query conn [(str "SELECT value FROM " table-name " WHERE id = " k)]))
                   v (:value r)]
               (assoc op :type :ok, :value (independent/tuple k v)))

       :write (do (let [con (cl/open (first (db/primaries test)) test)
                        table (clojure.string/upper-case table-name)]
                    (j/execute! con
                      [(str "SELECT _UPSERT(" k ", " value ", '" table "')")])
                    (assoc op
                           :type :ok
                           :value (independent/tuple k value))))

       :cas (do (let [[old new] value
                  con (cl/open (first (db/primaries test)) test)
                  table (clojure.string/upper-case table-name)
                  r (->> (j/execute! con
                           [(str "SELECT _CAS(" k ", " old ", " new ", '" table "')")])
                         (first)
                         (vals)
                         (first))]
                  (if r
                    (assoc op
                           :type  :ok
                           :value (independent/tuple k value))
                    (assoc op :type :fail)))))))

  (teardown! [_ test]
    (when-not (:leave-db-running? test)
      (cl/with-conn-failure-retry conn
        (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name)]))))

  (close! [_ test]))

(defn workload
  [opts]
  {:client      (Client. nil)
   :generator   (independent/concurrent-generator
                  10
                  (range)
                  (fn [k]
                    (->> (gen/mix [w cas])
                         (gen/reserve 5 r)
                         (gen/limit 100))))
   :checker     (independent/checker
                  (checker/compose
                    {:timeline     (timeline/html)
                     :linearizable (checker/linearizable {:model (model/cas-register 0)
                                                          :algorithm :linear})}))})
