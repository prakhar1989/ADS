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

(defn start! [node]
  (info node "starting rethinkdb")
  (c/su (c/exec :service :rethinkdb :restart))
  (info node "rethinkdb ready"))

(defn download-config! [node]
  (info node "downloading config")
  (let [url "https://raw.githubusercontent.com/prakhar1989/ADS/master/rethinkdb.conf"
        dest "/etc/rethinkdb/instances.d/instance1.conf"]
    (c/su (c/exec :wget url :-O dest))))
  
(defn db [version]
  (reify db/DB
    (setup! [_ test node] 
      (info node "set up")
        (do 
          (debian/add-repo! "rethinkdb" apt-line "pgp.mit.edu" packagekey)
          (debian/update!)
          (debian/install {:rethinkdb version})
          (download-config! node)
          (start! node)))

    (teardown! [_ test node]
      ;(info node "tearing down"))))
      (debian/uninstall! "rethinkdb"))))

(defn basic-test [version]
  (merge tests/noop-test
         {:os debian/os
          :db (db version)}))
