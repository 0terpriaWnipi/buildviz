(ns buildviz.handler
  (:use
   ring.middleware.json
   ring.middleware.resource
   ring.middleware.content-type
   ring.middleware.not-modified
   ring.middleware.accept
   ring.util.response
   [compojure.core :only (GET PUT)]
   [clojure.string :only (join escape)]
   [clojure.walk :only (postwalk)])
  (:require [buildviz.build-results :as results]
            [buildviz.http :as http]
            [buildviz.junit-xml :as junit-xml]
            [buildviz.csv :as csv]
            [buildviz.jobinfo :as jobinfo]
            [buildviz.pipelineinfo :as pipelineinfo]
            [buildviz.testsuites :as testsuites]))


(defn- store-build! [build-results job-name build-id build-data persist!]
  (if-some [errors (seq (results/build-data-validation-errors build-data))]
    {:status 400
     :body errors}
    (do (results/set-build! build-results job-name build-id build-data)
        (persist! @(:builds build-results) job-name build-id)
        (http/respond-with-json build-data))))

(defn- get-build [build-results job-name build-id]
  (if-some [build-data (results/build build-results job-name build-id)]
    (http/respond-with-json build-data)
    {:status 404}))

(defn- force-evaluate-junit-xml [content]
  (postwalk identity (junit-xml/parse-testsuites content)))

(defn- store-test-results! [build-results job-name build-id body persist!]
  (let [content (slurp body)]
    (try
      (force-evaluate-junit-xml content)
      (results/set-tests! build-results job-name build-id content)
      (persist! @(:tests build-results) job-name build-id)
      {:status 204}
      (catch Exception e
        {:status 400
         :body (.getMessage e)}))))

(defn- get-test-results [build-results job-name build-id accept]
  (if-some [content (results/tests build-results job-name build-id)]
    (if (= (:mime accept) :json)
      (http/respond-with-json (junit-xml/parse-testsuites content))
      (http/respond-with-xml content))
    {:status 404}))

(defn- serialize-nested-testsuites [testsuite-id]
  (join ": " testsuite-id))

;; status

(defn- with-latest-build-start [all-builds response]
  (if-let [build-starts (seq (remove nil? (map :start all-builds)))]
    (assoc response :latestBuildStart (apply max build-starts))
    response))

(defn- get-status [build-results]
  (let [all-builds (seq (mapcat #(results/builds build-results %)
                                (results/job-names build-results)))
        total-build-count (count all-builds)]
    (http/respond-with-json (with-latest-build-start all-builds
                              {:totalBuildCount total-build-count}))))

;; jobs

(defn- average-runtime-for [build-data-entries]
  (if-let [avg-runtime (jobinfo/average-runtime build-data-entries)]
    {:averageRuntime avg-runtime}))

(defn- total-count-for [build-data-entries]
  {:totalCount (count build-data-entries)})

(defn- failed-count-for [build-data-entries]
  (if-some [builds (seq (jobinfo/builds-with-outcome build-data-entries))]
    {:failedCount (jobinfo/fail-count builds)}))

(defn- flaky-count-for [build-data-entries]
  (if-some [builds (seq (jobinfo/builds-with-outcome build-data-entries))]
    {:flakyCount (jobinfo/flaky-build-count builds)}))

(defn- summary-for [build-results job-name]
  (let [build-data-entries (results/builds build-results job-name)]
    (merge (average-runtime-for build-data-entries)
           (total-count-for build-data-entries)
           (failed-count-for build-data-entries)
           (flaky-count-for build-data-entries))))

(defn- get-jobs [build-results accept]
  (let [job-names (results/job-names build-results)
        build-summaries (map #(summary-for build-results %) job-names)
        build-summary (zipmap job-names build-summaries)]
    (if (= (:mime accept) :json)
      (http/respond-with-json build-summary)
      (http/respond-with-csv
       (csv/export-table ["job" "averageRuntime" "totalCount" "failedCount" "flakyCount"]
                         (map (fn [[job-name job]] [job-name
                                                    (csv/format-duration (:averageRuntime job))
                                                    (:totalCount job)
                                                    (:failedCount job)
                                                    (:flakyCount job)])
                              build-summary))))))

;; pipelineruntime

(defn- runtimes-by-day [build-results]
  (let [job-names (results/job-names build-results)]
    (->> (map #(jobinfo/average-runtime-by-day (results/builds build-results %))
              job-names)
         (zipmap job-names)
         (filter #(not-empty (second %))))))

(defn- remap-date-first [[job runtimes-by-day]]
  (map (fn [[day avg-runtime]]
         [day {job avg-runtime}])
       runtimes-by-day))

(defn- merge-runtimes [all-runtimes-by-day]
  (->> (mapcat remap-date-first all-runtimes-by-day)
       (group-by first)
       (map (fn [[date entries]]
              [date (apply merge (map second entries))]))))

(defn- runtime-table-entry [date runtimes job-names]
  (->> (map #(get runtimes %) job-names)
       (map csv/format-duration)
       (cons date)))

(defn- runtimes-as-table [job-names runtimes]
  (map (fn [[date runtimes-by-day]]
         (runtime-table-entry date runtimes-by-day job-names))
       runtimes))

(defn- get-pipeline-runtime [build-results]
  (let [runtimes-by-day (runtimes-by-day build-results)
        job-names (keys runtimes-by-day)]

    (http/respond-with-csv (csv/export-table (cons "date" job-names)
                                             (->> (merge-runtimes runtimes-by-day)
                                                  (runtimes-as-table job-names)
                                                  (sort-by first))))))

;; fail phases

(defn- all-builds-in-order [build-results]
  (sort-by :end
           (mapcat (fn [[job builds]]
                     (map #(assoc % :job job) (vals builds)))
                   @(:builds build-results))))

(defn- get-fail-phases [build-results accept]
  (let [fail-phases (pipelineinfo/pipeline-fail-phases (all-builds-in-order build-results))]
    (if (= (:mime accept) :json)
      (http/respond-with-json fail-phases)
      (http/respond-with-csv
       (csv/export-table ["start" "end" "culprits"]
                         (map (fn [{start :start end :end culprits :culprits}]
                                [(csv/format-timestamp start)
                                 (csv/format-timestamp end)
                                 (join "|" culprits)])
                              fail-phases))))))

;; failures

(defn- failures-for [build-results job-name]
  (when-some [test-results (results/chronological-tests build-results job-name)]
    (when-some [failed-tests (seq (testsuites/accumulate-testsuite-failures
                                   (map junit-xml/parse-testsuites test-results)))]
      (let [build-data-entries (results/builds build-results job-name)]
        {job-name (merge {:children failed-tests}
                         (failed-count-for build-data-entries))}))))

(defn- failures-as-list [build-results job-name]
  (when-some [test-results (results/chronological-tests build-results job-name)]
    (->> (map junit-xml/parse-testsuites test-results)
         (testsuites/accumulate-testsuite-failures-as-list)
         (map (fn [{testsuite :testsuite classname :classname name :name failed-count :failedCount}]
                [failed-count
                 job-name
                 (serialize-nested-testsuites testsuite)
                 classname
                 name])))))

(defn- get-failures [build-results accept]
  (let [job-names (results/job-names build-results)]
    (if (= (:mime accept) :json)
      (let [failures (map #(failures-for build-results %) job-names)]
        (http/respond-with-json (into {} (apply merge failures))))
      (http/respond-with-csv (csv/export-table ["failedCount" "job" "testsuite" "classname" "name"]
                                               (mapcat #(failures-as-list build-results %) job-names))))))

;; testsuites

(defn- test-runs [build-results job-name]
  (let [test-results (results/chronological-tests build-results job-name)]
    (map junit-xml/parse-testsuites test-results)))

(defn- names-of-jobs-with-tests [build-results]
  (filter #(results/has-tests? build-results %) (results/job-names build-results)))

(defn- testcase-runtimes [build-results job-name]
  {:children (testsuites/average-testcase-runtime (test-runs build-results job-name))})

(defn- flat-testcase-runtimes [build-results job-name]
  (->> (testsuites/average-testcase-runtime-as-list (test-runs build-results job-name))
       (map (fn [{testsuite :testsuite classname :classname name :name average-runtime :averageRuntime}]
              [(csv/format-duration average-runtime)
               job-name
               (serialize-nested-testsuites testsuite)
               classname
               name]))))

(defn- get-testcases [build-results accept]
  (let [job-names (names-of-jobs-with-tests build-results)]
    (if (= (:mime accept) :json)
      (http/respond-with-json (zipmap job-names (map #(testcase-runtimes build-results %) job-names)))
      (http/respond-with-csv (csv/export-table
                              ["averageRuntime" "job" "testsuite" "classname" "name"]
                              (mapcat #(flat-testcase-runtimes build-results %) job-names))))))

;; testclasses

(defn- testclass-runtimes [build-results job-name]
  {:children (testsuites/average-testclass-runtime (test-runs build-results job-name))})

(defn- flat-testclass-runtimes [build-results job-name]
  (->> (testsuites/average-testclass-runtime-as-list (test-runs build-results job-name))
       (map (fn [{testsuite :testsuite classname :classname average-runtime :averageRuntime}]
              [(csv/format-duration average-runtime)
               job-name
               (serialize-nested-testsuites testsuite)
               classname]))))

(defn- get-testclasses [build-results accept]
  (let [job-names (names-of-jobs-with-tests build-results)]
    (if (= (:mime accept) :json)
      (http/respond-with-json (zipmap job-names
                                      (map #(testclass-runtimes build-results %) job-names)))
      (http/respond-with-csv (csv/export-table
                              ["averageRuntime" "job" "testsuite" "classname"]
                              (mapcat #(flat-testclass-runtimes build-results %) job-names))))))

;; flaky testcases

(defn- test-results-for-build [build-results job-name build-id]
  (if-let [test-results (results/tests build-results job-name build-id)]
    (junit-xml/parse-testsuites test-results)))

(defn- flat-flaky-testcases [build-results job-name]
  (let [builds (get @(:builds build-results) job-name)
        test-lookup (partial test-results-for-build build-results job-name)]
    (->> (testsuites/flaky-testcases-as-list builds test-lookup)
         (map (fn [{testsuite :testsuite
                    classname :classname
                    name :name
                    build-id :build-id
                    latest-failure :latest-failure
                    flaky-count :flaky-count}]
                [(csv/format-timestamp latest-failure)
                 flaky-count
                 job-name
                 build-id
                 (serialize-nested-testsuites testsuite)
                 classname
                 name])))))

(defn get-flaky-testclasses [build-results]
  (http/respond-with-csv (csv/export-table
                          ["latestFailure" "flakyCount" "job" "latestBuildId" "testsuite" "classname" "name"]
                          (mapcat #(flat-flaky-testcases build-results %)
                                  (results/job-names build-results)))))

;; app

(defn- app-routes [build-results persist-jobs! persist-tests!]
  (compojure.core/routes
   (GET "/" [] (redirect "/index.html"))

   (PUT "/builds/:job/:build" [job build :as {body :body}] (store-build! build-results job build body persist-jobs!))
   (GET "/builds/:job/:build" [job build] (get-build build-results job build))
   (PUT "/builds/:job/:build/testresults" [job build :as {body :body}] (store-test-results! build-results job build body persist-tests!))
   (GET "/builds/:job/:build/testresults" [job build :as {accept :accept}] (get-test-results build-results job build accept))

   (GET "/status" {} (get-status build-results))
   (GET "/jobs" {accept :accept} (get-jobs build-results accept))
   (GET "/jobs.csv" {} (get-jobs build-results {:mime :csv}))
   (GET "/pipelineruntime" {} (get-pipeline-runtime build-results))
   (GET "/pipelineruntime.csv" {} (get-pipeline-runtime build-results))
   (GET "/failphases" {accept :accept} (get-fail-phases build-results accept))
   (GET "/failphases.csv" {} (get-fail-phases build-results {:mime :csv}))
   (GET "/failures" {accept :accept} (get-failures build-results accept))
   (GET "/failures.csv" {} (get-failures build-results {:mime :csv}))
   (GET "/testcases" {accept :accept} (get-testcases build-results accept))
   (GET "/testcases.csv" {} (get-testcases build-results {:mime :csv}))
   (GET "/testclasses" {accept :accept} (get-testclasses build-results accept))
   (GET "/testclasses.csv" {} (get-testclasses build-results {:mime :csv}))
   (GET "/flakytestcases" {} (get-flaky-testclasses build-results))
   (GET "/flakytestcases.csv" {} (get-flaky-testclasses build-results))))

(defn create-app [build-results persist-jobs! persist-tests!]
  (-> (app-routes build-results persist-jobs! persist-tests!)
      wrap-json-response
      (wrap-json-body {:keywords? true})
      (wrap-accept {:mime ["application/json" :as :json,
                           "application/xml" "text/xml" :as :xml
                           "text/plain" :as :plain]})
      (wrap-resource "public")
      wrap-content-type
      wrap-not-modified))
