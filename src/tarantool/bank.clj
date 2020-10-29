(ns tarantool.bank
  "Simulates transfers between bank accounts."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [clojure.core.reducers :as r]
            [jepsen [cli :as cli]
                    [client :as client]
                    [checker :as checker]
                    [control :as c]
                    [generator :as gen]]
            [jepsen.tests.bank :as bank]
            [next.jdbc :as j]
            [next.jdbc.sql :as sql]
            [knossos.op :as op]
            [jepsen.checker.timeline :as timeline]
            [tarantool [client :as cl]
                       [db :as db]]))

(def table-name "accounts")

(defrecord BankClientWithLua [conn]
  client/Client

  (open! [this test node]
    (let [conn (cl/open node test)]
      (assoc this :conn conn :node node)))

  (setup! [this test node]
    (locking BankClientWithLua
      (let [conn (cl/open node test)]
       (Thread/sleep 10000) ; wait for leader election and joining to a cluster
       (when (= node (first (db/primaries test)))
         (cl/with-conn-failure-retry conn
           (info (str "Creating table " table-name))
           (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name
                             "(id INT NOT NULL PRIMARY KEY,
                             balance INT NOT NULL)")])
           (doseq [a (:accounts test)]
               (info "Populating account")
               (sql/insert! conn table-name {:id      a
                                             :balance (if (= a (first (:accounts test)))
                                                       (:total-amount test)
                                                       0)}))))
        (assoc this :conn conn :node node))))

  (invoke! [this test op]
    (try
      (case (:f op)
        :read (->> (sql/query conn [(str "SELECT * FROM " table-name)])
                   (map (juxt :ID :BALANCE))
                   (into (sorted-map))
                   (assoc op :type :ok, :value))

        :transfer
        (let [{:keys [from to amount]} (:value op)
              con (cl/open (first (db/primaries test)) test)
              table (clojure.string/upper-case table-name)
              r (-> con
                    (sql/query [(str "SELECT _WITHDRAW('" table "'," from "," to "," amount ")")])
                    first
                    :COLUMN_1)]
          (if (false? r)
                (assoc op :type :fail, :value {:from from :to to :amount amount})
                (assoc op :type :ok))))))

  (teardown! [_ test]
    (when-not (:leave-db-running? test)
      (info (str "Drop table" table-name))
      (cl/with-conn-failure-retry conn
        (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name)]))))

  (close! [_ test]))

; One bank account per table
(defrecord MultiBankClientWithLua [conn tbl-created?]
  client/Client
  (open! [this test node]
    (assoc this :conn (cl/open node test)))

  (setup! [this test node]
    (locking tbl-created?
      (let [conn (cl/open node test)]
        (Thread/sleep 10000) ; wait for leader election and joining to a cluster
        (when (= node (first (db/primaries test)))
          (when (compare-and-set! tbl-created? false true)
            (cl/with-conn-failure-retry conn
              (doseq [a (:accounts test)]
                (info "Creating table" table-name a)
                (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name a
                                       "(id     INT NOT NULL PRIMARY KEY,"
                                       "balance INT NOT NULL)")])
                  (info "Populating account" a)
                  (sql/insert! conn (str table-name a)
                             {:id 0
                              :balance (if (= a (first (:accounts test)))
                                         (:total-amount test)
                                         0)})))))
        (assoc this :conn conn :node node))))

  (invoke! [this test op]
    (try
      (case (:f op)
        :read
        (->> (:accounts test)
             (map (fn [x]
                    [x (->> (sql/query conn [(str "SELECT balance FROM " table-name
                                             x)]
                                     {:row-fn :BALANCE})
                            first)]))
             (into (sorted-map))
             (map (fn [[k {b :BALANCE}]] [k b]))
             (into {})
             (assoc op :type :ok, :value))

        :transfer
        (let [{:keys [from to amount]} (:value op)
              from (str table-name from)
              to   (str table-name to)
              con  (cl/open (first (db/primaries test)) test)
              from_uppercase (clojure.string/upper-case from)
              to_uppercase (clojure.string/upper-case to)
              r (-> con
                    (sql/query [(str "SELECT _WITHDRAW_MULTITABLE('" from_uppercase "','" to_uppercase "'," amount ")")])
                    first
                    :COLUMN_1)]
          (if (false? r)
                (assoc op :type :fail)
                (assoc op :type :ok))))))

  (teardown! [_ test]
    (when-not (:leave-db-running? test)
      (cl/with-conn-failure-retry conn
        (doseq [a (:accounts test)]
          (info "Drop table" table-name a)
          (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name a)])))))

  (close! [_ test]))

(defrecord BankClient [conn]
  client/Client

  (open! [this test node]
    (let [conn (cl/open node test)]
      (assoc this :conn conn :node node)))

  (setup! [this test node]
    (locking BankClient
      (let [conn (cl/open node test)]
       (Thread/sleep 10000) ; wait for leader election and joining to a cluster
       (when (= node (first (db/primaries test)))
         (cl/with-conn-failure-retry conn
           (info (str "Creating table " table-name))
           (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name
                             "(id INT NOT NULL PRIMARY KEY,
                             balance INT NOT NULL)")])
           (doseq [a (:accounts test)]
               (info "Populating account")
               (sql/insert! conn table-name {:id      a
                                             :balance (if (= a (first (:accounts test)))
                                                       (:total-amount test)
                                                       0)}))))
        (assoc this :conn conn :node node))))

  (invoke! [this test op]
    (try
      (case (:f op)
        :read (->> (sql/query conn [(str "SELECT * FROM " table-name)])
                   (map (juxt :ID :BALANCE))
                   (into (sorted-map))
                   (assoc op :type :ok, :value))

        :transfer
        ; TODO: with-transaction is not supported due to
        ; https://github.com/tarantool/tarantool/issues/2016
        (let [{:keys [from to amount]} (:value op)
              con (cl/open (first (db/primaries test)) test)
              b1 (-> con
                     (sql/query [(str "SELECT * FROM " table-name " WHERE id = ? ") from])
                     first
                     :BALANCE
                     (- amount))
              b2 (-> con
                     (sql/query [(str "SELECT * FROM " table-name " WHERE id = ? ") to])
                     first
                     :BALANCE
                     (+ amount))]
          (cond (or (neg? b1) (neg? b2))
                (assoc op :type :fail, :value {:from from :to to :amount amount})
                true
                (do (j/execute! con [(str "UPDATE " table-name " SET balance = balance - ? WHERE id = ?") amount from])
                    (j/execute! con [(str "UPDATE " table-name " SET balance = balance + ? WHERE id = ?") amount to])
                    (assoc op :type :ok)))))))

  (teardown! [_ test]
    (when-not (:leave-db-running? test)
      (info (str "Drop table" table-name))
      (cl/with-conn-failure-retry conn
        (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name)]))))

  (close! [_ test]))

; One bank account per table
(defrecord MultiBankClient [conn tbl-created?]
  client/Client
  (open! [this test node]
    (assoc this :conn (cl/open node test)))

  (setup! [this test node]
    (locking tbl-created?
      (let [conn (cl/open node test)]
        (Thread/sleep 10000) ; wait for leader election and joining to a cluster
        (when (= node (first (db/primaries test)))
          (when (compare-and-set! tbl-created? false true)
            (cl/with-conn-failure-retry conn
              (doseq [a (:accounts test)]
                (info "Creating table" table-name a)
                (j/execute! conn [(str "CREATE TABLE IF NOT EXISTS " table-name a
                                       "(id     INT NOT NULL PRIMARY KEY,"
                                       "balance INT NOT NULL)")])
                  (info "Populating account" a)
                  (sql/insert! conn (str table-name a)
                             {:id 0
                              :balance (if (= a (first (:accounts test)))
                                         (:total-amount test)
                                         0)})))))
        (assoc this :conn conn :node node))))

  (invoke! [this test op]
    (try
      (case (:f op)
        :read
        (->> (:accounts test)
             (map (fn [x]
                    [x (->> (sql/query conn [(str "SELECT balance FROM " table-name
                                             x)]
                                     {:row-fn :BALANCE})
                            first)]))
             (into (sorted-map))
             (map (fn [[k {b :BALANCE}]] [k b]))
             (into {})
             (assoc op :type :ok, :value))

        :transfer
        ; TODO: with-transaction is not supported due to
        ; https://github.com/tarantool/tarantool/issues/2016
        (let [{:keys [from to amount]} (:value op)
              from (str table-name from)
              to   (str table-name to)
              con  (cl/open (first (db/primaries test)) test)
              b1 (-> con
                     (sql/query [(str "SELECT balance FROM " from)])
                     first
                     :BALANCE
                     (- amount))
              b2 (-> con
                     (sql/query [(str "SELECT balance FROM " to)])
                     first
                     :BALANCE
                     (+ amount))]
          (cond (neg? b1)
                (assoc op :type :fail, :error [:negative from b1])
                (neg? b2)
                (assoc op :type :fail, :error [:negative to b2])
                true
                (do (j/execute! con [(str "UPDATE " from " SET balance = balance - ? WHERE id = 0") amount])
                    (j/execute! con [(str "UPDATE " to " SET balance = balance + ? WHERE id = 0") amount])
                    (assoc op :type :ok)))))))

  (teardown! [_ test]
    (when-not (:leave-db-running? test)
      (cl/with-conn-failure-retry conn
        (doseq [a (:accounts test)]
          (info "Drop table" table-name a)
          (j/execute! conn [(str "DROP TABLE IF EXISTS " table-name a)])))))

  (close! [_ test]))

(defn workload
  [opts]
  (assoc (bank/test opts)
         :client (BankClient. nil)))

(defn multitable-workload
  [opts]
  (assoc (workload opts)
         :client (MultiBankClient. nil (atom false))))

(defn workload-lua
  [opts]
  (assoc (workload opts)
         :client (BankClientWithLua. nil)))

(defn multitable-workload-lua
  [opts]
  (assoc (workload opts)
         :client (MultiBankClientWithLua. nil (atom false))))
