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
            [rethinkdb  [core :refer [connect close]]
                        [query :as query]
                        [query-builder :refer [term]]]))

(defn set-write-acks!
  "Updates the write-acks mode for a cluster. Spins until successful."
  [conn test db write-mode]
  (rethinkdb/retry 5
         (run!
           (query/update
             (query/table (query/db db) "table_config")
             {:write_acks write-mode
              :shards [{:primary_replica (core/primary test)
                        :replicas (map name (:nodes test))}]})
           conn)))

(defn wait-table
  "Wait for all replicas for a table to be ready"
  [conn db tbl]
  ;TODO:: Not sure what term is for. Documentation doesn't say anything
  (run! (term :WAIT [(query/table (query/db db) tbl)] {}) conn))

(defrecord Client [tbl-created? db table write-mode read-mode]
  client/Client
  (setup! [this test node]
    (let [conn (rethinkdb/connection node)]
      (info "Connected")
      ; Everyone's gotta block until we've made the table.
      (locking tbl-created?
        (when (compare-and-set! tbl-created? false true)
          (info node "Creating table.")
          (run! (query/db-create db) conn)
          (info node "Created db.")
          (run! (query/table-create (query/db db) table {:replicas 5}) conn)
          (info node "Created table.")
          (set-write-acks! conn test db write-mode)
          (info node "Write acks set.")
          (wait-table conn db table)
          (info node "Client setup complete")))
      (assoc this :conn conn :node node)))

  ;TODO::Figure out id at all places. Cant undestand what aphyr has done.
  (invoke! [this test op]
    (case (:f op)
      :read (let [result (-> (query/db db)
                             (query/table table)
                             (query/get ("test"))
                             (query/get-field "value")
                             (run! (:conn this)))
                  (assoc op
                    :value result
                    :type :ok)])

      :write ((-> (query/db db)
                  (query/table table)
                  (query/insert {:id "test" :value (:value op)}
                                {"conflict" "update"})
                  (run! (:conn this)))
               (assoc op :type :ok))

      :cas (let [row (-> (query/db db)
                          (query/table table)
                          (query/get ("test")))]
                (-> (query/update row (query/fn [row]
                                        (if (query/eq (query/get-field row :value) (head (:value op)))
                                            {:value (last (:value op))})))
                    (run! (:conn this)))

             (assoc op :type :ok))))


  (teardown! [this test]
    ;TODO::uncomment next line to drop db and table. Commented right now for debugging.
    ;(run! (query/db-drop db) (:conn this))
    (close (:conn this))))

(defn create-client
  "Client which executes the tests"
  [write-mode read-mode]
  (Client. (atom false) "ads" "cas" write-mode read-mode))

; Generators
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn r   [_ _] {:type :invoke, :f :read})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn generator
  "A generator that provides operations to the executer"
  []
  (info "Doing nothing yet. Trolling generator."))

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
          :generator  (generator)
          }))

