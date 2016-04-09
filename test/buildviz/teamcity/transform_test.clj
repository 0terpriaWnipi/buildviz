(ns buildviz.teamcity.transform-test
  (:require [buildviz.teamcity.transform :as sut]
            [clojure.test :refer :all]))

(defn- a-teamcity-build [build]
  {:job-name "a_job"
   :project-name "a_project"
   :build (merge {:number "42"
                  :status "SUCCESS"
                  :startDate "20160401T003701+0000"
                  :finishDate "20160401T003707+0000"}
                 build)})

(defn- a-teamcity-build-with-test [test]
  (-> (a-teamcity-build {})
      (assoc :tests [(merge {:name "a test suite: the.class.the test"
                             :status "SUCCESS"}
                            test)])))

(deftest test-teamcity-build->buildviz-build
  (testing "should return job name"
    (is (= "some_project some_job"
           (:job-name (sut/teamcity-build->buildviz-build (-> (a-teamcity-build {})
                                                              (assoc :job-name "some_job"
                                                                     :project-name "some_project")))))))
  (testing "should return build id"
    (is (= "21"
           (:build-id (sut/teamcity-build->buildviz-build (a-teamcity-build {:number "21"}))))))

  (testing "should return successful status"
    (is (= "pass"
           (:outcome (:build (sut/teamcity-build->buildviz-build (a-teamcity-build {:status "SUCCESS"})))))))
  (testing "should return failed status"
    (is (= "fail"
           (:outcome (:build (sut/teamcity-build->buildviz-build (a-teamcity-build {:status "FAILURE"})))))))
  (testing "should return start timestamp"
    (is (= 1459585432000
           (:start (:build (sut/teamcity-build->buildviz-build (a-teamcity-build {:startDate "20160402T082352+0000"})))))))
  (testing "should return end timestamp"
    (is (= 1459585450000
           (:end (:build (sut/teamcity-build->buildviz-build (a-teamcity-build {:finishDate "20160402T082410+0000"})))))))

  (testing "should return tests"
    (is (= [{:name "suite"
             :children [{:name "the test"
                         :classname "class"
                         :status "pass"
                         :runtime 42}]}]
           (:test-results (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {:name "suite: class.the test"
                                                                                           :status "SUCCESS"
                                                                                           :duration 42}))))))
  (testing "should return failing test"
    (is (= "fail"
           (-> (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {:status "FAILURE"}))
               :test-results
               first
               :children
               first
               :status))))
  (testing "should return skipped test"
    (is (= "skipped"
           (-> (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {:status "UNKNOWN"
                                                                                :ignored true}))
               :test-results
               first
               :children
               first
               :status))))
  (testing "should return runtime"
    (is (= 21
           (-> (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {:duration 21}))
               :test-results
               first
               :children
               first
               :runtime))))
  (testing "should handle missing runtime"
    (is (not
         (contains? (-> (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {}))
                        :test-results
                        first
                        :children
                        first)
                    :runtime))))
  (testing "should extract classname for JUnit origin"
    (is (= "the.class"
           (-> (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {:name "suite: the.class.the test"}))
               :test-results
               first
               :children
               first
               :classname))))
  (testing "should for now not care for nested suites for JUnit origin"
    (is (= "suite: nested suite"
           (-> (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {:name "suite: nested suite: the.class.the test"}))
               :test-results
               first
               :name))))
  (testing "should extract classname for non-JUnit orgin"
    (is (= {:name "<empty>"
            :children [{:classname "The Class: Sub section"
                        :name "Test description"
                        :status "pass"}]}
           (-> (sut/teamcity-build->buildviz-build (a-teamcity-build-with-test {:name "The Class: Sub section: Test description"}))
               :test-results
               first))))
  (testing "should fallback to RSpec style test name pattern if at least one of the tests does not match JUnit pattern"
    ;; This crude logic should save us from implementing either
    ;; - a configuration item for the user to specify which format is correct
    ;; - parsing the job's configuration and guessing what underlying reporter was used to generate the test report
    (is (= {:classname "The Subject"
            :name "test.description.with.dots"
            :status "pass"}
           (-> (sut/teamcity-build->buildviz-build (-> (a-teamcity-build {})
                                                       (assoc :tests [{:name "The Subject: test.description.with.dots" :status "SUCCESS"}
                                                                      {:name "The Class: Sub section: Test description" :status "SUCCESS"}])))
               :test-results
               first
               :children
               first)))))
