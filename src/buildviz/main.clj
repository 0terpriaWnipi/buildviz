(ns buildviz.main
  (:require [buildviz.build-results :as results]
            [buildviz.handler :as handler]
            [buildviz.http :as http]
            [buildviz.storage :as storage]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- path-for [file-name]
  (if-let [data-dir (System/getenv "BUILDVIZ_DATA_DIR")]
    (.getPath (io/file data-dir file-name))
    file-name))

(def data-dir (path-for "data"))


(defn- persist-build! [build-results job-name build-id]
  (storage/store-build! job-name
                        build-id
                        (results/build build-results job-name build-id)
                        data-dir))

(defn- persist-testresults! [build-results job-name build-id]
  (storage/store-testresults! job-name
                              build-id
                              (results/tests build-results job-name build-id)
                              data-dir))

(def app
  (let [builds (storage/load-builds data-dir)
        tests (storage/load-all-testresults data-dir)]
    (-> (handler/create-app (results/build-results builds tests)
                            persist-build!
                            persist-testresults!)
        http/wrap-log-request
        http/wrap-log-errors)))
