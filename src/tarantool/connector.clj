(ns tarantool.connector
  "Access to Tarantool using connector on Clojure"
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [tarantool-clj.client :as tc]
            [tarantool-clj.space :as ts]
            [tarantool-clj.tuple-space :as tuple-space]
            [com.stuartsierra.component :as component]))

(defn conn-config
  [node]
  {:host (name node)
   :port 3301
   :username "jepsen"
   :password "jepsen"
   :request-timeout 10
   :auto-reconnect? true
   :async? false})

(def space-config
  {:name "jepsen"
   :fields [:id :a]
   :tail :_tail})

(defn open!
  [node]
  (-> (conn-config node)
      (tc/new-client)
      (component/start)))

(defn close!
  [conn]
  (component/stop conn))

(defn setup!
  [conn space-config]
  (info "Setup database on master node")
  (-> space-config
      (tuple-space/new-tuple-space (conn))
      (component/start)))

(defn tset
  [conn v]
  (let [space (-> conn
                  (ts/new-space space-config)
                  (component/start))]
    (ts/insert space {:a v})))

(defn tget
  [conn]
  (let [space (-> conn
                  (ts/new-space space-config)
                  (component/start))]
    (ts/select-first space {:a v})))
