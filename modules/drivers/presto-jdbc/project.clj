(defproject metabase/presto-jdbc-driver "1.0.0-350-SNAPSHOT"
  :min-lein-version "2.5.0"

  :dependencies
  [[io.prestosql/presto-jdbc "350"]]

  :profiles
  {:provided
   {:dependencies
    [[org.clojure/clojure "1.10.1"]
     [metabase-core "1.0.0-SNAPSHOT"]]}

   :uberjar
   {:auto-clean    true
    :aot           :all
    :javac-options ["-target" "1.8", "-source" "1.8"]
    :target-path   "target/%s"
    :uberjar-name  "presto-jdbc.metabase-driver.jar"}})
