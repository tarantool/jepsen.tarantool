(ns tarantool.runner
  "Run Tarantool tests."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [next.jdbc :as j]
            [slingshot.slingshot :refer [try+]]
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
            [jepsen.checker.timeline :as timeline]
            ;[knossos.model :as model]
            [jepsen.os.ubuntu :as ubuntu]
            [tarantool [db :as db]
                       [register :as register]]))

(def workloads
  "A map of workload names to functions that can take opts and construct
  workloads."
  {;:set             set/workload
   ;:bank            bank/workload
   ;:bank-index      bank/index-workload
   ;:g2              g2/workload
   ;:internal        internal/workload
   ;:monotonic       monotonic/workload
   ;:multimonotonic  multimonotonic/workload
   ;:pages           pages/workload
   :register        register/workload})

(def workload-options
  "For each workload, a map of workload options to all the values that option
  supports."
  {;:set         {:serialized-indices  [true false]
   ;              :strong-read         [true false]}
   ;:bank        {:fixed-instances     [true false]
   ;              :at-query            [true false]}
   ;:bank-index  {:fixed-instances     [true false]
   ;              :serialized-indices  [true false]}
   ;:g2          {:serialized-indices  [true false]}
   ;:internal    {:serialized-indices  [true false]}
   ;:monotonic   {:at-query-jitter     [0 10000 100000]}
   ;:multimonotonic {}
   ;:pages       {:serialized-indices  [true false]}
   :register    {}})

(def cli-opts
  "Options for test runners."
   [["-v" "--version VERSION"
     "What Tarantool version should we test?"
     :default "2.6"]
    ["-w" "--workload NAME" "Test workload to run"
     :parse-fn keyword
     :missing (str "--workload " (cli/one-of workloads))
     :validate [workloads (cli/one-of workloads)]]
    [nil "--single-mode"
     "Use a single Tarantool instance"
     :default false]
    ["-e" "--engine NAME"
     "What Tarantool data engine should we use?"
     :default "memtx"]])

(def crash-pattern
  "An egrep pattern we use to find crashes in the Tarantool logs."
  "Segmentation fault|too long WAL write|F>")

(defn logged-crashes
  "Takes a test, and returns a map of nodes to strings from their Tarantool logs
  that look like crashes, or nil if no crashes occurred."
  ([test]
   (let [crashes (->> (c/on-many (:nodes test)
                                 (try+
                                   (c/exec :egrep :-i crash-pattern db/logfile)
                                   (catch [:type :jepsen.control/nonzero-exit] e
                                     nil)))
                      (keep (fn [[k v :as pair]]
                              (when v pair))))]
     (when (seq crashes)
       (into {} crashes)))))

(defn crash-checker
  "Reports on unexpected process crashes in the logfiles. This is... a terrible
  hack and will probably break in later versions of Jepsen; it relies on the
  fact that the DB is still running. It's also going to break retrospective
  analyses, but... better than nothing, and I'm short on time to cut a whole
  new Jepsen release for this."
  []
  (reify checker/Checker
    (check [this test history opts]
      (if-let [crashes (logged-crashes test)]
        {:valid?  false
         :crashes crashes}
        {:valid? true}))))

(defn tarantool-test
  [opts]
  (let [workload ((get workloads (:workload opts)) opts)
        nemesis  nemesis/noop
        gen      (->> (:generator workload)
                      (gen/nemesis (:generator nemesis))
                      (gen/time-limit (:time-limit opts)))]
    (merge tests/noop-test
           opts
           {:client    (:client workload)
            :nemesis   nemesis
            :name      (str "tarantool-" (:version opts))
            :os        ubuntu/os
            :db        (db/db (:version opts))
            :engine    (:engine opts)
            :single-mode (:single-mode opts)
            :pure-generators true
            :generator gen
            :checker   (checker/compose {:perf        (checker/perf)
                                         :clock-skew  (checker/clock-plot)
                                         :crash       (crash-checker)
                                         :timeline    (timeline/html)
                                         :stats       (checker/stats)
                                         :exceptions  (checker/unhandled-exceptions)
                                         :workload    (:checker workload)})})))

(defn -main
  "Handles command line arguments."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn tarantool-test
                                         :opt-spec cli-opts})
                   (cli/serve-cmd))
            args))
