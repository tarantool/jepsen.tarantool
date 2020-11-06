(ns tarantool.client
  "Access to Tarantool with JDBC connector"
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [next.jdbc :as j]
            [next.jdbc.sql :as sql]
            [dom-top.core :as dt]
            [jepsen [util :as util :refer [default timeout]]]
            [next.jdbc.connection :as connection]))

(def max-timeout "Longest timeout, in ms" 10000000)

(def txn-timeout     5000)
(def connect-timeout 10000)
(def socket-timeout  10000)
(def open-timeout
  "How long will we wait for an open call by default"
  5000)

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

(defmacro with-error-handling
  "Common error handling for errors, including txn aborts."
  [op & body]
  `(try
    (with-txn-aborts ~op ~@body)

    (catch java.sql.BatchUpdateException e#
      (condp re-find (.getMessage e#)
        #"Query timed out" (assoc ~op :type :info, :error :query-timed-out)
        (throw e#)))

    (catch java.sql.SQLNonTransientConnectionException e#
      (condp re-find (.getMessage e#)
        #"Connection timed out" (assoc ~op :type :info, :error :conn-timed-out)
        (throw e#)))

    (catch clojure.lang.ExceptionInfo e#
      (cond (= "Connection is closed" (.cause (:rollback (ex-data e#))))
            (assoc ~op :type :info, :error :conn-closed-rollback-failed)

            (= "createStatement() is called on closed connection"
               (.cause (:rollback (ex-data e#))))
            (assoc ~op :type :fail, :error :conn-closed-rollback-failed)

            true (do (info e# :caught (pr-str (ex-data e#)))
                     (info :caught-rollback (:rollback (ex-data e#)))
                     (info :caught-cause    (.cause (:rollback (ex-data e#))))
                     (throw e#))))))

(defmacro with-txn-aborts
  "Aborts body on rollbacks."
  [op & body]
  `(let [res# (capture-txn-abort ~@body)]
     (if (= ::abort res#)
       (assoc ~op :type :fail, :error :conflict)
       res#)))

(defmacro with-txn-retries
  "Retries body on rollbacks."
  [& body]
  `(loop []
     (let [res# (capture-txn-abort ~@body)]
       (if (= ::abort res#)
         (recur)
         res#))))

(defmacro capture-txn-abort
  "Converts aborted transactions to an ::abort keyword"
  [& body]
  `(try ~@body
        (catch java.sql.SQLTransactionRollbackException e#
          (if (= (.getMessage e#) rollback-msg)
            ::abort
            (throw e#)))
        (catch java.sql.BatchUpdateException e#
          (if (= (.getMessage e#) rollback-msg)
            ::abort
            (throw e#)))
        (catch java.sql.SQLException e#
          (condp re-find (.getMessage e#)
            #"can not retry select for update statement" ::abort
            #"\[try again later\]" ::abort
            (throw e#)))))

(defmacro with-txn
  "Executes body in a transaction, with a timeout, automatically retrying
  conflicts and handling common errors."
  [op [c conn] & body]
  `(timeout (+ 1000 socket-timeout) (assoc ~op :type :info, :error :timed-out)
            (with-error-handling ~op
              (with-txn-retries
                (j/with-transaction [~c ~conn]
                  ~@body)))))


(defmacro with-conn-failure-retry
 "DBMS tends to be flaky for a few seconds after starting up, which can wind
  up breaking our setup code. This macro adds a little bit of backoff and retry
  for those conditions."
 [conn & body]
 (assert (symbol? conn))
 (let [tries    (gensym 'tries) ; try count
       e        (gensym 'e)     ; errors
       conn-sym (gensym 'conn)  ; local conn reference
       retry `(do (when (zero? ~tries)
                    (info "Out of retries!")
                    (throw ~e))
                  (info "Connection failure; retrying...")
                  (Thread/sleep (rand-int 2000))
                  (~'retry (reopen! ~conn-sym) (dec ~tries)))]
 `(dt/with-retry [~conn-sym ~conn
                  ~tries    32]
    (let [~conn ~conn-sym] ; Rebind the conn symbol to our current connection
      ~@body)
    (catch org.tarantool.CommunicationException ~e ~retry)
    (catch java.sql.BatchUpdateException ~e ~retry)
    (catch java.sql.SQLTimeoutException ~e ~retry)
    (catch java.sql.SQLNonTransientConnectionException ~e ~retry)
    (catch java.sql.SQLException ~e
      (condp re-find (.getMessage ~e)
        #"Resolve lock timeout"           ~retry ; high contention
        #"Information schema is changed"  ~retry ; ???
        #"called on closed connection"    ~retry ; definitely didn't happen
        #"Region is unavailable"          ~retry ; okay fine
        (do (info "with-conn-failure-retry isn't sure how to handle SQLException with message" (pr-str (class (.getMessage ~e))) (pr-str (.getMessage ~e)))
            (throw ~e)))))))

(defn reopen!
  "Closes a connection and returns a new one based on the given connection."
  [conn]
  ; Don't know how to close connection in next.jdbc
  ;(close! conn)
  (open (::node conn) (::test conn)))

(defmacro capture-txn-abort
  "Converts aborted transactions to an ::abort keyword"
  [& body]
  `(try ~@body
        (catch java.sql.SQLTransactionRollbackException e#
          (if (= (.getMessage e#) rollback-msg)
            ::abort
            (throw e#)))
        (catch java.sql.BatchUpdateException e#
          (if (= (.getMessage e#) rollback-msg)
            ::abort
            (throw e#)))
        (catch java.sql.SQLException e#
          (condp re-find (.getMessage e#)
            #"can not retry select for update statement" ::abort
            #"\[try again later\]" ::abort
            (throw e#)))))

(def rollback-msg
  "Some drivers have a few exception classes that use this message."
  "Deadlock found when trying to get lock; try restarting transaction")

(defn primary
  [node]
  (let [conn (open node test)]
    (with-conn-failure-retry conn
      (:COLUMN_1 (first (sql/query conn ["SELECT _LEADER()"]))))))
