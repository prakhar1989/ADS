(ns jepson.rethinkdb
  (:require [clojure.tools.logging :refer :all]
            [clojure.core.reducers :as r]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [knossos.op :as op]
            [jepsen [client :as client]
                                 [core :as jepsen]
                                 [db :as db]
                                 [tests :as tests]
                                 [control :as c :refer [|]]
                                 [checker :as checker]
                                 [nemesis :as nemesis]
                                 [generator :as gen]
                                 [util :refer [timeout meh]]]
            [jepsen.control.util :as cu]
            [jepsen.control.net :as cn]
            [jepsen.os.debian :as debian]))

;; constants
(def packagekey "1614552E5765227AEC39EFCFA7E00EF33A8F2399")
(def apt-line "deb http://download.rethinkdb.com/apt jessie main")

;; codeeeez
(defn db [version]
  (reify db/DB
    ;; installation instructions copied from https://github.com/prakhar1989/ADS/blob/master/Dockerfile
    (setup! [_ test node] 
      (info node "set up")
      (if (= node :n1)
        (do 
          (debian/add-repo! "rethinkdb" apt-line "pgp.mit.edu" packagekey)
          (debian/update!)
          (debian/install {:rethinkdb version}))))

    (teardown! [_ test node]
      (info node "tore down"))))

(defn basic-test [version]
  (merge tests/noop-test
         {:os debian/os
          :db (db version)}))
