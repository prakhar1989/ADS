(ns jepson.cas
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer :all]
            [jepsen.core :as core]
            [jepson.rethinkdb :as rethinkdb]
            [jepsen.os.debian :as debian]
            [jepsen.tests :as tests]
            [jepsen.model :as model]
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
         (query/run
           (query/update
             (query/table (query/db "rethinkdb") "table_config")
             {:write_acks write-mode
              :shards [{:primary_replica (core/primary test)
                        :replicas (map name (:nodes test))}]})
           conn)))

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

(defrecord Client [tbl-created? db table write-mode read-mode]
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
    ; Reads are idempotent, we can treat their failure as info
    (with-errors op #{:read}
      (case (:f op)
            :read (let [result (-> (query/db db)
                                   (query/table table)
                                   (query/get "test")
                                   (query/get-field "value")
                                   (query/run (:conn this)))]
                        (assoc op
                          :value {:test result}
                          :type :ok))

            :write ((-> (query/db db)
                        (query/table table)
                        (query/insert {:id "test" :value (:value op)}
                                      {"conflict" "update"})
                        (query/run (:conn this)))
                     (assoc op :type :ok))

            :cas (let [row (-> (query/db db)
                                (query/table table)
                                (query/get "test"))]
                      (-> (query/update row 
                                      (query/fn [row]
                                            (if (query/eq (query/get-field row :value) (first (:value op)))
                                                {:value (last (:value op))})))
                          (query/run (:conn this)))
                   (assoc op :type :ok)))))

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
         (gen/delay 1)
         (gen/nemesis
           (gen/seq (cycle [(gen/sleep 60)
                            {:type :info :f :stop}
                            {:type :info :f :start}])))
         (gen/time-limit 6))))

; The skeleton is fixed used by aphyr at various places. Only the client is to be implemented for our use case.
(defn cas-test [version write-mode read-mode]
  "Test to perform"
  (merge tests/noop-test
         {:name       (str "rethnkdb" version)
          :os         debian/os
          :db         (rethinkdb/db version)
          :client     (create-client write-mode read-mode)
          :model      (model/cas-register)
          :checker    (checker/compose {:linear checker/linearizable
                                        :latency (checker/latency-graph)})
          :nemesis    (nemesis/partition-random-halves)
          :generator  (std-gen (gen/mix [r w cas]))}))
