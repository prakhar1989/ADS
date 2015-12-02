(ns jepson.rethinkdb
  (:require [clojure.tools.logging :refer :all]
            [clojure.java.io :as io]
            [rethinkdb.core :refer [connect close]]
	          [clojure.pprint :refer [pprint]]
            [jepsen [db :as db]
                    [tests :as tests]
                    [control :as c :refer [|]]
                    [util :refer [timeout meh]]]
            [jepsen.os.debian :as debian]))


(defn connection
  "Make a connection to rethinkdb node"
  [node]
  (connect :host (name node) :port 28015))

(defn retry
  "Evals body repeatedly until it doesn't throw, sleeping dt seconds."
  [dt node]
  (loop []
     (let [done? (atom false)]
       (try (close (connection node))
            (compare-and-set! done? false true)
            (catch Throwable _
              (info "Node not up yet. Will try again")))
       (if (= done? false)
         (do (Thread/sleep (* ~dt 1000))
             (recur))))))

(defn join-servers 
  "returns a list of config lines for cluster setup"
  [test]
  (->> (:nodes test)
       (map #(str "join=" (name %) ":29015"))
       (clojure.string/join "\n")))

(defn configure! 
  "configure instance with the config file"
  [node test]
    (c/su (c/exec :echo (-> (slurp (io/resource "rethinkdb.conf"))
                            (str "\n\n" (join-servers test))
                            (str "\n\n" "server-name=" (name node)))
                    :> "/etc/rethinkdb/instances.d/instance1.conf")))

(defn install! 
  "installs rethinkdb on each node"
  [version]
  (let [packagekey "1614552E5765227AEC39EFCFA7E00EF33A8F2399"
        apt-line "deb http://download.rethinkdb.com/apt jessie main"]
    (debian/add-repo! "rethinkdb" apt-line "pgp.mit.edu" packagekey)
    (debian/update!)
    (debian/install {:rethinkdb version})))

(defn start! 
  "starts the db on each node"
  [node]
  (info node "starting rethinkdb")
  (c/su (c/exec :service :rethinkdb :restart))
  (info node "rethinkdb ready"))

(defn show-dbinfo
  "logs db information for each node"
  [node]
  (retry 5 node)
  (info node "ready"))

;; the jepsen core function
(defn db [version]
  (reify db/DB
    (setup! [_ test node] 
      (info node "set up")
        (do
          (install! version)
          (configure! node test)
          (start! node)
          (show-dbinfo node)))

    (teardown! [_ test node]
      ;; TODO: See how to kill
      (info node "tearing down"))))


(defn basic-test [version]
  (merge tests/noop-test
         {:os debian/os
          :db (db version)}))
