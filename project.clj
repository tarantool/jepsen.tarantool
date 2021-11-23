(defproject jepsen.tarantool "0.1.0"
  :description "A Jepsen tests for Tarantool"
  :url "https://www.tarantool.io/en/"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :main tarantool.runner
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [jepsen "0.1.18"]
                 [expound "0.8.5"]
                 [seancorfield/next.jdbc "1.1.582"]
                 [org.tarantool/connector "1.9.4"]
                 [com.stuartsierra/component "1.0.0"]]
  :plugins [[lein-cljfmt "0.6.8"]]
  :repl-options {:init-ns tarantool.runner}
  :jvm-opts ["-Djava.awt.headless=true -Xmx12G"]
  :aot [tarantool.runner])
