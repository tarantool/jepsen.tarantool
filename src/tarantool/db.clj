(ns tarantool.db
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [next.jdbc :as j]
            [next.jdbc.connection :as connection]
            [jepsen [core :as jepsen]
                    [control :as c]
                    [db :as db]]
            [slingshot.slingshot :refer [try+ throw+]]))

(def data-dir "/var/lib/tarantool/jepsen")
(def logfile "/var/log/tarantool/jepsen.log")
(def dir     "/opt/tarantool")

(def installer-name "installer.sh")
(def installer-url (str "https://tarantool.io/" installer-name))

(defn node-uri
  "An uri for connecting to a node on a particular port."
  [node port]
  (str "jepsen:jepsen@" (name node) ":" port))

(defn peer-uri
  "An uri for other peers to talk to a node."
  [node]
  (node-uri node 3301))

(defn replica-set
  "Build a Lua table with an URI's instances in a cluster."
  [test]
  (->> (:nodes test)
       (map (fn [node]
              (str "'" (peer-uri node) "'")))
       (str/join ",")))

(defn install!
  "Installation using installer.sh"
  [node version]
  (info "Install Tarantool version" version)
  (c/su
      (c/exec :curl :-O :-L "https://tarantool.io/installer.sh")
      (c/exec :chmod :+x "./installer.sh")
      (c/exec (str "VER=" version) "./installer.sh")
      (c/exec :usermod :-a :-G :tarantool :ubuntu)
      (c/su (c/exec :systemctl :stop "tarantool@example"))))

(defn start!
  "Starts tarantool service"
  [test node]
  (c/su (c/exec :systemctl :start "tarantool@jepsen")))

(defn restart!
  "Restarts tarantool service"
  [test node]
  (c/su (c/exec :systemctl :restart "tarantool@jepsen")))

(defn stop!
  "Stops tarantool service"
  [test node]
    (c/su (c/exec :systemctl :stop "tarantool@jepsen" "||" "true")))

(defn wipe!
  "Removes logs, data files and uninstall package"
  [test node]
  (c/su (c/exec :rm :-rf logfile (c/lit (str data-dir "/*"))))
  (c/su (c/exec :dpkg :--purge :--force-all :tarantool))
  (c/su (c/exec :dpkg :--configure :-a)))

(defn boolean-to-str
  [b]
  (if (true? b) "true" "false"))

(defn is-primary?
  [test node]
  (let [p (jepsen/primary test)]
    (if (= node p) true false)))

(defn configure!
  "Configure instance"
  [test node]
  (let [read-only (not (is-primary? test node))]
    (info "Joining" node "as" (if (true? read-only) "replica" "leader"))
    (c/exec :echo (-> "tarantool/jepsen.lua" io/resource slurp
                      (str/replace #"%TARANTOOL_REPLICATION%" (replica-set test))
                      (str/replace #"%TARANTOOL_IS_READ_ONLY%" (boolean-to-str read-only))
                      (str/replace #"%TARANTOOL_SINGLE_MODE%" (boolean-to-str (:single-mode test)))
                      (str/replace #"%TARANTOOL_DATA_ENGINE%" (:engine test)))
            :> "/etc/tarantool/instances.enabled/jepsen.lua")))

(defn is-read-only
  [conn]
  (j/execute! conn ["SELECT lua('return box.info().ro') IS NOT NULL"]))

(defn set-read-only-mode
  "Disable and enable read only mode"
  [conn mode]
  (j/execute! conn ["SELECT LUA('box.cfg{read_only=true}; return true')"]))

(defn db
  "Tarantool DB for a particular version."
  [version]
  (reify db/DB

    (setup! [_ test node]
      (info node "Starting Tarantool" version)
      (c/su
        (install! node version)
        (configure! test node)
        (start! test node)
        (Thread/sleep 10000)))

    (teardown! [_ test node]
      (info node "Stopping Tarantool")
      (stop! test node)
      (wipe! test node))

    db/LogFiles
    (log-files [_ test node]
      [logfile])))
