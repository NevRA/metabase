(ns metabase.test.data.presto-jdbc
  "Presto JDBC driver test extensions."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.core :as hsql]
            [honeysql.helpers :as h]
            [metabase.config :as config]
            [metabase.driver :as driver]
            [metabase.driver.presto :as presto]
            [metabase.driver.presto-test :as presto-test]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql.util :as sql.u]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.test.data.interface :as tx]
            [metabase.test.data.presto :as test.data.presto]
            [metabase.test.data.sql :as sql.tx]
            [metabase.test.data.sql-jdbc :as sql-jdbc.tx]))

(sql-jdbc.tx/add-test-extensions! :presto-jdbc)

;; during unit tests don't treat presto as having FK support
(defmethod driver/supports? [:presto-jdbc :foreign-keys] [_ _] (not config/is-test?))

;;; driver test extensions implementation

;; in the past, we had to manually update our Docker image and add a new catalog for every new dataset definition we
;; added. That's insane. Just use the `test-data` catalog and put everything in that, and use
;; `db-qualified-table-name` like everyone else.
(def ^:private ^:dynamic test-catalog-name "test_data")

(defmethod tx/dbdef->connection-details :presto-jdbc
  [_ context {:keys [database-name]}]
  {:host               (tx/db-test-env-var-or-throw :presto-jdbc :host "localhost")
   :port               (tx/db-test-env-var-or-throw :presto-jdbc :port "8443")
   :user               (tx/db-test-env-var-or-throw :presto-jdbc :user "metabase")
   :additional-options "SSLTrustStorePath=/tmp/cacerts-with-presto-ssl.jks&SSLTrustStorePassword=changeit"
   :ssl                true
   :catalog            test-catalog-name})

(defmethod sql.tx/qualified-name-components :presto-jdbc
  ;; use the default schema from the in-memory connector
  ([_ db-name]                       [test-catalog-name "default"])
  ([_ db-name table-name]            [test-catalog-name "default" (tx/db-qualified-table-name db-name table-name)])
  ([_ db-name table-name field-name] [test-catalog-name "default" (tx/db-qualified-table-name db-name table-name) field-name]))

(defn- execute-over-http! [db-details sql]
  (partial #'presto/execute-query-for-sync (assoc db-details :port 8080 :ssl false)))

(defn- field-base-type->dummy-value [field-type]
  ;; we need a dummy value for every base-type to make a properly typed SELECT statement
  (if (keyword? field-type)
    (case field-type
      :type/Boolean        "TRUE"
      :type/Integer        "1"
      :type/BigInteger     "cast(1 AS bigint)"
      :type/Float          "1.0"
      :type/Decimal        "DECIMAL '1.0'"
      :type/Text           "cast('' AS varchar(255))"
      :type/Date           "current_timestamp" ; this should probably be a date type, but the test data begs to differ
      :type/DateTime       "current_timestamp"
      :type/DateTimeWithTZ "current_timestamp"
      :type/Time           "cast(current_time as TIME)"
      "from_hex('00')") ; this might not be the best default ever
    ;; we were given a native type, map it back to a base-type and try again
    (field-base-type->dummy-value (#'presto/presto-type->base-type field-type))))

(defn- qualify-and-quote [database-name table-name]
  (sql.tx/qualify-and-quote :presto-jdbc database-name table-name))

(defmethod sql.tx/create-table-sql :presto-jdbc
  [driver {:keys [database-name]} {:keys [table-name], :as tabledef}]
  (let [field-definitions (cons {:field-name "id", :base-type  :type/Integer} (:field-definitions tabledef))
        dummy-values      (map (comp field-base-type->dummy-value :base-type) field-definitions)
        columns           (map :field-name field-definitions)
        qual-name         (qualify-and-quote database-name table-name)]
    ;; Presto won't let us use the `CREATE TABLE (...)` form, but we can still do it creatively if we select the right
    ;; types out of thin air
    (format "CREATE TABLE %s AS SELECT * FROM (VALUES (%s)) AS t (%s) WHERE 1 = 0"
            qual-name
            (str/join \, dummy-values)
            (str/join \, (for [column columns]
                           (sql.u/quote-name driver :field (tx/format-name driver column)))))))

(defmethod sql.tx/drop-table-if-exists-sql :presto-jdbc
  [driver {:keys [database-name]} {:keys [table-name]}]
  (let [qual-nm (qualify-and-quote database-name table-name)]
    (str "DROP TABLE IF EXISTS " qual-nm)))

(defn- insert-sql [driver {:keys [database-name]} {:keys [table-name], :as tabledef} rows]
  (let [field-definitions (cons {:field-name "id"} (:field-definitions tabledef))
        columns           (map (comp keyword :field-name) field-definitions)
        [query & params]  (-> (apply h/columns columns)
                              (h/insert-into (apply hsql/qualify
                                                    (sql.tx/qualified-name-components driver database-name table-name)))
                              (h/values rows)
                              (hsql/format :allow-dashed-names? true, :quoting :ansi))]
    (log/tracef "Inserting Presto rows")
    (doseq [row rows]
      (log/trace (str/join ", " (map #(format "^%s %s" (.getName (class %)) (pr-str %)) row))))
    #_[query params]
    (if (nil? params)
      query
      (unprepare/unprepare :presto-jdbc (cons query params)))))

(defn- raw-execute! [jdbc-spec sql]
  (with-open [conn (jdbc/get-connection jdbc-spec)
              stmt (sql-jdbc.execute/statement :presto-jdbc conn)]
    (.execute stmt sql)))

(defmethod tx/create-db! :presto-jdbc
  [driver {:keys [table-definitions database-name] :as dbdef} & {:keys [skip-drop-db?] :as more}]
  #_(let [method (get-method tx/create-db! :sql-jdbc/test-extensions)]
      (method :presto-jdbc (update dbdef :database-name dash->underscore) more))
  #_(swap! all-dbdefs #(conj % dbdef))
  (let [details   (tx/dbdef->connection-details driver :db dbdef)
        jdbc-spec (sql-jdbc.conn/connection-details->spec driver details)
        execute!  (partial jdbc/execute! jdbc-spec)]
    (doseq [tabledef table-definitions
            :let     [rows       (:rows tabledef)
                      ;; generate an ID for each row because we don't have auto increments
                      keyed-rows (map-indexed (fn [i row] (cons (inc i) row)) rows)
                      ;; make 50 rows batches since we have to inline everything
                      ;; changed down from 100 with adoption of JDBC driver, because some unknown error occurred
                      ;; when inserting rows into test_data_venues that went away with this smaller batch size
                      batches    (partition 50 50 nil keyed-rows)
                      create-sql (sql.tx/create-table-sql driver dbdef tabledef)
                      drop-sql   (sql.tx/drop-table-if-exists-sql driver dbdef tabledef)]]
      (when-not skip-drop-db?
        (execute! drop-sql))
      (execute! create-sql)
      (doseq [batch batches]
        (execute! (insert-sql driver dbdef tabledef batch))))))

(defmethod tx/destroy-db! :presto-jdbc
  [driver {:keys [database-name table-definitions], :as dbdef}]
  (let [details   (tx/dbdef->connection-details driver :db dbdef)
        jdbc-spec (sql-jdbc.conn/connection-details->spec driver details)
        execute!  (partial jdbc/execute! jdbc-spec)]
    (doseq [{:keys [table-name], :as tabledef} table-definitions]
      (println (format "[Presto] destroying %s.%s" (pr-str database-name) (pr-str table-name)))
      (let [drop-sql (sql.tx/drop-table-if-exists-sql driver dbdef tabledef)]
        (execute! drop-sql)
        (println "[Presto] [ok]")))))

(defmethod tx/format-name :presto-jdbc
  [_ s]
  (-> (str/replace s #"-" "_")
      str/lower-case))

(defmethod tx/aggregate-column-info :presto-jdbc
  ([driver ag-type]
   ((get-method tx/aggregate-column-info ::tx/test-extensions) driver ag-type))

  ([driver ag-type field]
   (merge
    ((get-method tx/aggregate-column-info ::tx/test-extensions) driver ag-type field)
    (when (= ag-type :sum)
      {:base_type :type/BigInteger}))))

;; FIXME Presto actually has very good timezone support
(defmethod tx/has-questionable-timezone-support? :presto-jdbc [_] true)
