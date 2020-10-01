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
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :refer [timeout meh]]]
            [next.jdbc :as j]
            [next.jdbc.sql :as sql]
            [knossos.model :as model]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.ubuntu :as ubuntu]
            [tarantool.client :as cl]
            [tarantool.db :as db]))

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
      (cl/with-conn-failure-retry conn
        (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name
                          " (id INT NOT NULL PRIMARY KEY,
                          value INT NOT NULL)")]))
      (assoc this :conn conn :node node)))

  (invoke! [this test op]
     (case (:f op)
       :read (let [value (:VALUE
               (first (sql/query conn
                 [(str "SELECT value FROM " table-name " WHERE id = 1")])))]
             (assoc op :type :ok :value value))

       :write (do (let [con (cl/open (jepsen/primary test) test)
                        table (clojure.string/upper-case table-name)]
                    (j/execute! con
                      [(str "SELECT _UPSERT(1, " (:value op) ", '" table "')")])
                  (assoc op :type :ok)))

       :cas (let [[old new] (:value op)
                  con (cl/open (jepsen/primary test) test)
                  table (clojure.string/upper-case table-name)]
                  (assoc op :type (if (->> (j/execute! conn
                                             [(str "SELECT _CAS(1, " old ", " new ", '" table "')")])
                                           (first)
                                           (vals)
                                           (first))
                                   :ok
                                   :fail)))))

  (teardown! [_ test]
    (cl/with-conn-failure-retry conn
      (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name)])))

  (close! [_ test]))

(defn workload
  [opts]
  {:client      (Client. nil)
   :generator   (->> (gen/mix [r w cas])
                     (gen/stagger 1/10)
                     (gen/nemesis nil)
                     (gen/time-limit (:time-limit opts)))
   :checker     (checker/compose
                   {:timeline     (timeline/html)
                    :linearizable (checker/linearizable {:model (model/cas-register 0)
                                                         :algorithm :linear})})})
