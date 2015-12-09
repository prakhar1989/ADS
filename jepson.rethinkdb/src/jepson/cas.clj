(ns jepson.cas
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [jepsen.core :as core]
            [jepson.rethinkdb :as rethinkdb]
            [jepsen.os.debian :as debian]
            [jepsen.tests :as tests]
            [jepsen.model :as model]
            [jepsen.independent :as independent]
            [jepsen.checker :as checker]
            [jepsen.nemesis :as nemesis]
            [jepsen.client :as client]
            [jepsen.generator :as gen]
            [rethinkdb  [core :refer [connect close]]
                        [query :as query]
                        [query-builder :refer [term]]]))

(defn set-write-acks!
  "Updates the write-acks mode for a cluster. Spins until successful."
  [conn test write-mode]
  (rethinkdb/retry 5
        (let [primary (first (:nodes test))]
          (prn (str "primary replica:" primary))
          (query/run
             (query/update
               (query/table (query/db "rethinkdb") "table_config")
               {:write_acks write-mode
                :shards [{:primary_replica primary
                          :replicas (map name (:nodes test))}]})
             conn))))

; wraps a operation's error in either a :fail or :info state
; depending on whether the operation 
(defmacro with-errors
  [op idempotent-ops & body]
  `(let [error-type# (if (~idempotent-ops (:f ~op)) :fail :info)]
     (try
       ~@body
       (catch clojure.lang.ExceptionInfo e#
         (case (:e (ex-data e#))
           4100000 (assoc ~op :type :fail,       :error (:cause (ex-data e#)))
                   (assoc ~op :type error-type#, :error (str e#)))))))

(defn wait-table
  "Wait for all replicas for a table to be ready"
  [conn db tbl]
  ;TODO:: Not sure what term is for. Documentation doesn't say anything
  (query/run (term :WAIT [(query/table (query/db db) tbl)] {}) conn))

(defrecord Client [tbl-created? db table write-mode read_mode]
  client/Client
  (setup! [this test node]
    (let [conn (rethinkdb/connection node)]
      (info "Connected")
      ; Everyone's gotta block until we've made the table.
      (locking tbl-created?
        (when (compare-and-set! tbl-created? false true)
          (query/run (query/db-create db) conn)
          (info node "Created db.")
          (query/run (query/table-create (query/db db) table {:replicas 5}) conn)
          (info node "Created table.")
          (set-write-acks! conn test write-mode)
          (info node "Write acks set.")
          (wait-table conn db table)
          (info node "Client setup complete")))
      (assoc this :conn conn :node node)))

  ;TODO::Figure out id at all places. Cant undestand what aphyr has done.
  (invoke! [this test op]
      (with-errors op #{:read}
        (let [id    (key (:value op))
              value (val (:value op))
              row (query/get (term :TABLE [(query/db db) table]
                             {:read_mode read_mode}) id)]
          (case (:f op)
            :read (assoc op
                         :type  :ok
                         :value (independent/tuple id
                                  (query/run (term :DEFAULT
                                               [(query/get-field row "val") nil])
                                         (:conn this))))
            :write (do (query/run (query/insert (query/table (query/db db) table)
                                        {:id id, :val value}
                                        {"conflict" "update"})
                              (:conn this))
                       (assoc op :type :ok))
            :cas (let [[value value'] value
                       res (query/run
                             (query/update
                               row
                               (query/fn [row]
                                 (query/branch
                                   (query/eq (query/get-field row "val") value)
                                   {:val value'}
                                   (query/error "abort"))))
                             (:conn this))]
                   (assoc op :type (if (and (= (:errors res) 0)
                                            (= (:replaced res) 1))
                                     :ok
                                     :fail)))))))

  (teardown! [this test]
    (query/run (query/db-drop db) (:conn this))
    (close (:conn this))
    (info "Tearing down client")))

(defn create-client
  "Client which executes the tests"
  [write-mode read-mode]
  (Client. (atom false) "ads" "cas" write-mode read-mode))

; Generators
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn r   [_ _] {:type :invoke, :f :read})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn std-gen
  "Takes a client generator and wraps it in a typical schedule and nemesis causing failover."
  [gen]
  (gen/phases
    (->> gen
         ;(gen/delay 1)
         (gen/nemesis
           (gen/seq (cycle [{:type :info :f :start}
                            (gen/sleep 30)
                            {:type :info :f :stop}
                            (gen/sleep 0)])))
         (gen/time-limit 100))))

; The skeleton is fixed used by aphyr at various places. Only the client is to be implemented for our use case.
(defn cas-test 
  "Test to perform"
  [version write-mode read-mode]
  (merge tests/noop-test
         {:name       (str "rethnkdb" version)
          :version    version
          :os         debian/os
          :db         (rethinkdb/db version)
          :client     (create-client write-mode read-mode)
          :model      (model/cas-register)
          :checker    (checker/compose {:linear checker/linearizable
                                        :latency (checker/latency-graph)})
          :nemesis    (nemesis/partition-random-halves)
          ;:generator  (std-gen (gen/mix [r w cas]))}))
          ;:concurrency 10
          :generator (std-gen (independent/sequential-generator (range)
                                (fn [k] (->> (gen/mix [r r w cas cas])
                                             (gen/stagger 0.05)
                                             (gen/limit 200))))) }))
