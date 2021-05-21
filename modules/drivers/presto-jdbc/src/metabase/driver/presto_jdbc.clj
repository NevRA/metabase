(ns metabase.driver.presto-jdbc
  "Presto JDBC driver. See https://prestodb.io/docs/current/ for complete dox."
  (:require [buddy.core.codecs :as codecs]
            [clj-http.client :as http]
            [clojure.core.async :as a]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.core :as hsql]
            [honeysql.helpers :as h]
            [java-time :as t]
            [medley.core :as m]
            [metabase.db.spec :as db.spec]
            [metabase.driver :as driver]
            [metabase.driver.common :as driver.common]
            [metabase.driver.presto :as presto]
            [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql-jdbc.sync.describe-database :as sql-jdbc.describe-database]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql.util :as sql.u]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.query-processor.context :as context]
            [metabase.query-processor.store :as qp.store]
            [metabase.query-processor.timezone :as qp.timezone]
            [metabase.query-processor.util :as qputil]
            [metabase.util :as u]
            [metabase.util.date-2 :as u.date]
            [metabase.util.honeysql-extensions :as hx]
            [metabase.util.i18n :refer [trs tru]]
            [metabase.util.schema :as su]
            [metabase.util.ssh :as ssh]
            [schema.core :as s])
  (:import [java.sql Connection ResultSet Time]
           [java.time OffsetDateTime ZonedDateTime]))

(driver/register! :presto-jdbc, :parent #{:presto :sql-jdbc})

;;; Presto API helpers

(defmethod sql-jdbc.sync/database-type->base-type :presto-jdbc [_ field-type]
  ;; seems like we can just delegate to the Presto implementation for this?
  (#'presto/presto-type->base-type (if (keyword? field-type) (name field-type) field-type)))

(defn- jdbc-spec
  [{:keys [host port catalog]
    :or   {host "localhost", port 5432, catalog ""}
    :as   opts}]
  (-> (merge
       {:classname                     "io.prestosql.jdbc.PrestoDriver"
        :subprotocol                   "presto"
        :subname                       (db.spec/make-subname host port catalog)}
       (dissoc opts :host :port :db :catalog))
      sql-jdbc.common/handle-additional-options))

(defmethod sql-jdbc.conn/connection-details->spec :presto-jdbc
  [_ {ssl? :ssl, :as details-map}]
  (let [props (-> details-map
                  (update :port (fn [port]
                                    (if (string? port)
                                      (Integer/parseInt port)
                                      port)))
                  (assoc :SSL ssl?)
                  (dissoc :ssl))]
       (jdbc-spec props)))

(defn- have-select-privilege?
  "Checks whether the connected user has permission to select from the given `table-name`, in the given `schema`.
  Adapted from the legacy Presto driver implementation."
  [driver conn schema table-name]
  (try
   (let [sql (sql-jdbc.describe-database/simple-select-probe-query driver schema table-name)]
        ;; if the query completes without throwing an Exception, we can SELECT from this table
        (jdbc/reducible-query {:connection conn} sql)
        true)
   (catch Throwable _
     false)))

(defn- describe-schema
  "Gets a set of maps for all tables in the given `catalog` and `schema`. Adapted from the legacy Presto driver
  implementation."
  [driver conn catalog schema]
  (let [sql (str "SHOW TABLES FROM " (sql.u/quote-name driver :schema catalog schema))]
    (into #{} (comp (filter (fn [{table-name :table}]
                                (and (not (contains? presto/excluded-schemas schema))
                                     (have-select-privilege? driver conn schema table-name))))
                    (map (fn [{table-name :table}]
                             {:name        table-name
                              :schema      schema})))
              (jdbc/reducible-query {:connection conn} sql))))

(defn- all-schemas
  "Gets a set of maps for all tables in all schemas in the given `catalog`. Adapted from the legacy Presto driver
  implementation."
  [driver conn catalog]
  (let [sql (str "SHOW SCHEMAS FROM " (sql.u/quote-name driver :database catalog))]
    (into #{}
          (map (fn [{:keys [schema] :as full}]
                   {:tables (describe-schema driver conn catalog schema)}))
          (jdbc/reducible-query {:connection conn} sql))))

(defmethod driver/describe-database :presto-jdbc
  [driver {{:keys [catalog schema] :as details} :details :as database}]
  (with-open [conn (-> (sql-jdbc.conn/db->pooled-connection-spec database)
                       jdbc/get-connection)]
    (let [schemas (remove presto/excluded-schemas (all-schemas driver conn catalog))]
        (reduce set/union schemas))))

;; Result set holdability must be HOLD_CURSORS_OVER_COMMIT
;; defining this method to omit the holdability param
(defmethod sql-jdbc.execute/prepared-statement :presto-jdbc
  [driver ^Connection conn ^String sql params]
  (let [stmt (.prepareStatement conn
                                sql
                                ResultSet/TYPE_FORWARD_ONLY
                                ResultSet/CONCUR_READ_ONLY)]
       (try
         (try
           (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
           (catch Throwable e
             (log/debug e (trs "Error setting prepared statement fetch direction to FETCH_FORWARD"))))
         (sql-jdbc.execute/set-parameters! driver stmt params)
         stmt
         (catch Throwable e
           (.close stmt)
           (throw e)))))

;; and similarly for statement
(defmethod sql-jdbc.execute/statement :presto-jdbc
  [_ ^Connection conn]
  (let [stmt (.createStatement conn
                               ResultSet/TYPE_FORWARD_ONLY
                               ResultSet/CONCUR_READ_ONLY)]
       (try
         (try
           (.setFetchDirection stmt ResultSet/FETCH_FORWARD)
           (catch Throwable e
             (log/debug e (trs "Error setting statement fetch direction to FETCH_FORWARD"))))
         stmt
         (catch Throwable e
           (.close stmt)
           (throw e)))))

;; TODO: figure out if this is still needed here, since :presto already does it
(doseq [[feature supported?] {:set-timezone                    true
                              :basic-aggregations              true
                              :standard-deviation-aggregations true
                              :expressions                     true
                              :native-parameters               true
                              :expression-aggregations         true
                              :binning                         true
                              :foreign-keys                    true}]
  (defmethod driver/supports? [:presto-jdbc feature] [_ _] supported?))

(prefer-method driver/execute-reducible-query :sql-jdbc :presto)
(prefer-method driver/can-connect? :sql-jdbc :presto)
(prefer-method driver/describe-table :sql-jdbc :presto)
