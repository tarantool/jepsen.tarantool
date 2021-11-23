(ns tarantool.runner
  "Run Tarantool tests."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.pprint :refer [pprint]]
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
                    [tests :as tests]
                    [util :refer [timeout meh]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.os.ubuntu :as ubuntu]
            [tarantool [db :as db]
                       [bank :as bank]
                       [errcode :as err]
                       [nemesis :as nemesis]
                       [register :as register]
                       [sets :as sets]
                       [counter :as counter]])
   (:gen-class))

(def minimal-concurrency
  10)

(def workloads
  "A map of workload names to functions that can take opts and construct
  workloads.
  Each workload is a map like

      {:generator         a generator of client ops
       :final-generator   a generator to run after the cluster recovers
       :client            a client to execute those ops
       :checker           a checker
       :model             for the checker}

  Or, for some special cases where nemeses and workloads are coupled, we return
  a keyword here instead."
  {:bank            bank/workload
   :bank-multitable bank/multitable-workload
   :bank-lua        bank/workload-lua
   :bank-multitable-lua bank/multitable-workload-lua
   :set             sets/workload
   :counter-inc     counter/workload-inc
   :register        register/workload})

(def standard-workloads
  "The workload names we run for test-all by default."
  (remove #{} (keys workloads)))

(def workloads-expected-to-pass
  "A collection of workload names which we expect should actually pass."
  (remove #{:bank               ; https://github.com/tarantool/jepsen.tarantool/issues/83
            :bank-multitable    ; https://github.com/tarantool/jepsen.tarantool/issues/83
            } standard-workloads))

(def nemeses
  "Types of faults a nemesis can create."
   #{:pause :kill :partition :clock})

(def standard-nemeses
  "Combinations of nemeses for tests."
  [[]
   [:pause]
   [:kill]
   [:partition]
   [:pause :kill :partition :clock]])

(def special-nemeses
  "A map of special nemesis names to collections of faults."
  {:none      []
   :standard  [:partition :clock]
   :all       [:pause :kill :partition :clock]})

(defn parse-nemesis-spec
  "Takes a comma-separated nemesis string and
  returns a collection of keyword faults."
  [spec]
  (->> (str/split spec #",")
       (map keyword)
       (mapcat #(get special-nemeses % [%]))))

(def cli-opts
  "Options for test runners."
   [["-v" "--version VERSION"
     "What Tarantool version should we test?"
     :default "2.6"]
    [nil "--mvcc"
     "Enable MVCC engine"
     :default false]
    [nil "--nemesis FAULTS" "A comma-separated list of nemesis faults to enable"
     :parse-fn parse-nemesis-spec
     :validate [(partial every? (into nemeses (keys special-nemeses)))
                (str "Faults must be one of " nemeses " or "
                     (cli/one-of special-nemeses))]]
    [nil "--nemesis-interval SECONDS" "How long to wait between nemesis faults"
     :default  3
     :parse-fn read-string
     :validate [#(and (number? %) (pos? %)) "must be a positive number"]]
    ["-e" "--engine NAME"
     "What Tarantool data engine should we use?"
     :default "memtx"]])

(def test-all-opts
  "Command line options for testing everything."
  [[nil "--only-workloads-expected-to-pass" "Don't run tests which we know fail."
     :default true]
   ["-w" "--workload NAME"
    "Test workload to run. If omitted, runs all workloads."
    :parse-fn keyword
    :default nil
    :validate [workloads (cli/one-of workloads)]]])

(def single-test-opts
  "Command line options for single tests."
  [["-w" "--workload NAME" "Test workload to run"
    :parse-fn keyword
    :missing (str "--workload " (cli/one-of workloads))
    :validate [workloads (cli/one-of workloads)]]])

(def crash-pattern
  "An egrep pattern we use to find crashes in the Tarantool logs."
  (str/join "|"
            (vector "Segmentation fault|F>"
            (str/join "|" (map name err/codes)))))

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
        nemesis  (nemesis/nemesis-package
                   {:db        (db/db (:version opts))
                    :nodes     (:nodes opts)
                    :faults    (:nemesis opts)
                    :partition {:targets [:one :majority :majorities-ring :primaries]}
                    :pause     {:targets [:one :majority :all :primaries]}
                    :kill      {:targets [:one :majority :all :primaries]}
                    :interval  (:nemesis-interval opts)})
        _ (info (pr-str nemesis))
        gen      (->> (:generator workload)
                      (gen/nemesis (:generator nemesis))
                      (gen/time-limit (:time-limit opts)))
        gen      (if (:final-generator workload)
                   (gen/phases gen
                               (gen/log "Healing cluster")
                               (gen/nemesis (:final-generator nemesis))
                               (gen/log "Waiting for recovery...")
                               (gen/clients (:final-generator workload)))
                   gen)]
    (merge tests/noop-test
           opts
           {:client    (:client workload)
            :nemesis   (:nemesis nemesis)
            :name      (str "tarantool-" (:version opts))
            :os        ubuntu/os
            :db        (db/db (:version opts))
            :engine    (:engine opts)
            :mvcc      (:mvcc opts)
            :pure-generators true
            :concurrency (if (and (< (:concurrency opts) minimal-concurrency)
                                  (= (:workload opts) :register))
                             minimal-concurrency
                             (:concurrency opts))
            :accounts  (vec (range 10)) ; bank-specific option
            :max-transfer 5 ; bank-specific option
            :total-amount 100 ; bank-specific option
            :generator gen
            :checker   (checker/compose {:perf        (checker/perf {:nemeses (:perf nemesis)})
                                         :clock-skew  (checker/clock-plot)
                                         :crash       (crash-checker)
                                         :timeline    (timeline/html)
                                         :stats       (checker/stats)
                                         :exceptions  (checker/unhandled-exceptions)
                                         :workload    (:checker workload)})})))

(defn all-test-options
  "Takes base cli options, a collection of nemeses, workloads, and a test count,
  and constructs a sequence of test options."
  [cli nemeses workloads]
  (for [n nemeses, w workloads, i (range (:test-count cli))]
    (assoc cli
           :nemesis   n
           :workload  w)))

(defn all-tests
  "Takes parsed CLI options and constructs a sequence of test options, by
  combining all workloads and nemeses."
  [test-fn cli]
  (let [nemeses   (if-let [n (:nemesis cli)] [n] standard-nemeses)
        workloads (if-let [w (:workload cli)] [w]
                    (if (:only-workloads-expected-to-pass cli)
                      workloads-expected-to-pass
                      standard-workloads))]
    (println "\nUsed workloads:")
    (pprint workloads)
    (println "\nUsed nemeses:")
    (pprint nemeses)
    (->> (all-test-options cli nemeses workloads)
         (map test-fn))))

(defn -main
  "Handles command line arguments."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn tarantool-test
                                         :opt-spec (concat cli-opts single-test-opts)})
                   (cli/test-all-cmd {:tests-fn  (partial all-tests tarantool-test)
                                      :opt-spec (concat cli-opts test-all-opts)})
                   (cli/serve-cmd))
            args))
