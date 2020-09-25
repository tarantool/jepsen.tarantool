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
            [knossos.model :as model]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.ubuntu :as ubuntu]
            [tarantool.client :as cl]
            [tarantool.db :as db]))

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
      ;(when (= node (jepsen/primary test))
      ;  (j/execute! conn ["CREATE TABLE IF NOT EXISTS jepsen (key INT, value INT, PRIMARY KEY (key))"])
      ;  (j/execute! conn ["CREATE INDEX IF NOT EXISTS idx ON jepsen (key)"]))
      (assoc this :conn conn :node node)))

  (invoke! [this test op]
     (case (:f op)
       :read (assoc op
                    :type  :ok
                    :value (cl/read-v-by-k conn 1))
       :write (do (let [con (cl/open (jepsen/primary test) test)]
                   (cl/write-v-by-k con 1 (:value op)))
                   (assoc op :type :ok))
       :cas (let [[old new] (:value op)
                  con (cl/open (jepsen/primary test) test)]
                  (assoc op :type (if (cl/compare-and-set con 1 old new)
                                   :ok
                                   :fail)))))

  (teardown! [this test])
      ;(j/execute! conn ["DROP TABLE jepsen"]))

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
