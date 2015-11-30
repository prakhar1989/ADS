(ns jepson.rethinkTest
  (:require [clojure.string :as str]
            [jepson.rethinkdb :as rethinkdb]
            [jepsen.os.debian :as debian]
            [jepsen.tests :as tests]
            [jepsen.model :as model]
            [jepsen.checker :as checker]
            [jepsen.nemesis :as nemesis]))


(defn create-client
  "Client which executes the tests"
  [write-mode read-mode])

; Generators
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn r   [_ _] {:type :invoke, :f :read})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn generator
  "A generator that provides operations to the executer"
  [])

(defn test [version write-mode read-mode]
  "Actual Tests to perform"
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

