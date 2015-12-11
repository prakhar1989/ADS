(ns jepson.rethinkdb-test
  (:require [clojure.test :refer :all]
            [jepsen.core :as jepsen]
            [jepson.cas :as cas]))

(def version "2.2.0~0jessie")

;(deftest basic-test
;  (is (:valid?
;        (:results
;          (run! (rethinkdb/basic-test version))))))

(deftest better-test
    (is (:valid?
          (:results
            (jepsen/run! (cas/cas-test version "majority" "majority"))))))
