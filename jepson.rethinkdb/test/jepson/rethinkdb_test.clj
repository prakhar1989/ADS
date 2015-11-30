(ns jepson.rethinkdb-test
  (:require [clojure.test :refer :all]
            [jepsen.core :refer [run!]]
            [jepson.rethinkdb :as rethinkdb])
            [jepson.rethinkTest :as rethinkTest])

(def version "2.2.0~0jessie")

(deftest basic-test
  (is (:valid? 
        (:results 
          (run! (rethinkdb/basic-test version))))))

(deftest test
  (is (:valid?
        (:results
          (run! (rethinkTest/test version "single" "single"))))))