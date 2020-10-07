(ns tarantool.db
  (:require [clojure.tools.logging :refer :all]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [next.jdbc :as j]
            [next.jdbc.connection :as connection]
            [jepsen.os.debian :as debian]
            [jepsen.control.util :as cu]
            [jepsen [core :as jepsen]
                    [control :as c]
                    [db :as db]
                    [util :as util :refer [parse-long]]]
            [slingshot.slingshot :refer [try+ throw+]]))

(def data-dir "/var/lib/tarantool/jepsen")
(def logfile "/var/log/tarantool/jepsen.log")
(def dir     "/opt/tarantool")
(def tarantool-repo
  "Where can we clone tarantool from?"
  "https://github.com/tarantool/tarantool")

(def installer-name "installer.sh")
(def installer-url (str "https://tarantool.io/" installer-name))

(def build-dir
  "A remote directory to clone project and compile."
  "/tmp/jepsen/build")

(def build-file
  "A file we create to track the last built version; speeds up compilation."
  "jepsen-built-version")

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

(defn install-build-prerequisites!
  "Installs prerequisite packages for building Tarantool."
  []
  (info "Install prerequisites")
  (debian/install [:autoconf
		   :automake
		   :build-essential
		   :cmake
		   :coreutils
		   :libtool
		   :libreadline-dev
		   :libncurses5-dev
		   :libssl-dev
		   :libunwind-dev
		   :libicu-dev
		   :make
		   :sed
		   :zlib1g-dev]))

(defn checkout-repo!
  "Checks out a repo at the given version into a directory in build/ named
  `dir`. Returns the path to the build directory."
  [repo-url dir version]
  (let [full-dir (str build-dir "/" dir)]
    (when-not (cu/exists? full-dir)
      (c/cd build-dir
            (info "Cloning into" full-dir)
            (c/exec :mkdir :-p build-dir)
            (c/exec :git :clone repo-url dir)))

    (c/cd full-dir
          (try+ (c/exec :git :checkout version)
                (catch [:exit 1] e
                  (if (re-find #"pathspec .+ did not match any file" (:err e))
                    (do ; Ah, we're out of date
                        (c/exec :git :fetch)
                        (c/exec :git :checkout version))
                    (throw+ e)))))

    (c/cd full-dir
          (c/exec :git :submodule :update :--init :--recursive))

    full-dir))

(def build-locks
  "We use these locks to prevent concurrent builds."
  (util/named-locks))

(defmacro with-build-version
  "Takes a test, a repo name, a version, and a body. Builds the repo by
  evaluating body, only if it hasn't already been built. Takes out a lock on a
  per-repo basis to prevent concurrent builds. Remembers what version was last
  built by storing a file in the repo directory. Returns the result of body if
  evaluated, or the build directory."
  [node repo-name version & body]
  `(util/with-named-lock build-locks [~node ~repo-name]
     (let [build-file# (str build-dir "/" ~repo-name "/" build-file)]
       (if (try+ (= (str ~version) (c/exec :cat build-file#))
                (catch [:exit 1] e# ; Not found
                  false))
         ; Already built
         (str build-dir "/" ~repo-name)
         ; Build
         (let [res# (do ~@body)]
           ; Log version
           (c/exec :echo ~version :> build-file#)
           res#)))))

(defn build-tarantool!
  "Build Tarantool from scratch"
  [test node]
  (let [version (:version test)]
    (with-build-version node "tarantool" version
      (let [dir (checkout-repo! tarantool-repo "tarantool" version)]
        (install-build-prerequisites!)
        (info "Building Tarantool" (:version test))
        (c/cd dir
          (c/exec :cmake :-DWITH_SYSTEMD:BOOL=ON
			 :-DCMAKE_BUILD_TYPE=RelWithDebInfo
			 :-DENABLE_DIST:BOOL=ON
			 :-DENABLE_BACKTRACE:BOOL=ON
			 "-DCMAKE_INSTALL_LOCALSTATEDIR:PATH=/var"
			 "-DCMAKE_INSTALL_SYSCONFDIR:PATH=/etc"
			 :.)
          (c/exec :make :-j2)
          (c/exec :make :install))
        dir)))
  (c/su
      (c/exec :adduser
		:--system
		:--group
		:--quiet
		:--home "/var/spool/tarantool"
		:--no-create-home
		:--disabled-login
		:tarantool)
      (c/exec :install :-d :-otarantool :-gadm :-m2750 "/var/log/tarantool")
      (c/exec :install :-d :-otarantool :-gadm :-m2750 "/var/run/tarantool")
      (c/exec :install :-d :-otarantool :-gadm :-m2750 "/var/lib/tarantool")))

(defn install-package!
  "Installation using installer.sh"
  [node version]
  (info "Install Tarantool package version" version)
  (c/su
      (c/exec :curl :-O :-L "https://tarantool.io/installer.sh")
      (c/exec :chmod :+x "./installer.sh")
      (c/exec (str "VER=" version) "./installer.sh")
      (c/su (c/exec :systemctl :stop "tarantool@example"))))

(defn install!
  "Tarantool installation, accepts branch version (like 2.6) or commit hash"
  [node version]
  (if (re-matches #"\d+\.\d+" version)
	(install-package! node version)
	(build-tarantool! node version)))

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

(defn is-single-mode?
  [test]
  (let [n (count (:nodes test))]
    (cond
      (= n 1) true
      :else false)))

(defn configure!
  "Configure instance"
  [test node]
  (let [read-only (not (is-primary? test node))]
    (info "Joining" node "as" (if (true? read-only) "replica" "leader"))
    (c/exec :mkdir :-p "/etc/tarantool/instances.available")
    (c/exec :mkdir :-p "/etc/tarantool/instances.enabled")
    (c/exec :usermod :-a :-G :tarantool :ubuntu)
    (c/exec :echo (-> "tarantool/jepsen.lua" io/resource slurp
                      (str/replace #"%TARANTOOL_REPLICATION%" (replica-set test))
                      (str/replace #"%TARANTOOL_IS_READ_ONLY%" (boolean-to-str read-only))
                      (str/replace #"%TARANTOOL_MVCC%" (boolean-to-str (:mvcc test)))
                      (str/replace #"%TARANTOOL_SINGLE_MODE%" (boolean-to-str (is-single-mode? test)))
                      (str/replace #"%TARANTOOL_DATA_ENGINE%" (:engine test)))
            :> "/etc/tarantool/instances.enabled/jepsen.lua")
    (c/exec :cp "/etc/tarantool/instances.enabled/jepsen.lua" "/etc/tarantool/instances.available")))

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
