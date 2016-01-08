(ns buildviz.data.build-schema-test
  (:require [buildviz.data.build-schema :as schema]
            [clojure.test :refer :all]))

(deftest test-build-validation-errors
  (testing "should require start time"
    (is (empty? (schema/build-validation-errors {:start 0})))
    (is (= [:start]
           (:path (first (schema/build-validation-errors {})))))
    (is (= [:start]
           (:path (first (schema/build-validation-errors {:start -1})))))
    (comment
      "https://github.com/bigmlcom/closchema/pull/35"
      (is (= [:start]
             (:path (first (schema/build-validation-errors {:start nil})))))))

  (testing "should pass on 0 end time"
    (is (empty? (schema/build-validation-errors {:start 0 :end 0}))))

  (testing "should fail on negative end time"
    (is (= [:end]
           (:path (first (schema/build-validation-errors {:end -1}))))))

  (testing "should fail on end time before start time"
    (is (= [:end]
           (:path (first (schema/build-validation-errors {:start 42
                                                          :end 41}))))))

  (testing "should fail on missing revision for inputs"
    (is (= [:inputs 0 :revision]
           (:path (first (schema/build-validation-errors {:start 42
                                                          :inputs [{:source-id "43"}]}))))))

  ;; TODO we should validate, once "source_id" is gone
  ;; (testing "should fail on missing sourceId for inputs"
  ;;   (is (= [:inputs 0 :sourceId]
  ;;          (:path (first (schema/build-validation-errors {:start 42
  ;;                                                              :inputs [{:revision "abcd"}]}))))))

  (testing "should fail on missing jobName for triggeredBy"
    (is (= [:triggered-by :job-name]
           (:path (first (schema/build-validation-errors {:start 42
                                                          :triggered-by {:build-id "42"}}))))))

  (testing "should fail on missing buildId for triggeredBy"
    (is (= [:triggered-by :build-id]
           (:path (first (schema/build-validation-errors {:start 42
                                                          :triggered-by {:job-name "the_job"}}))))))
  )

(deftest test-was-triggered-by?
  (testing "should find triggering build"
    (is (schema/was-triggered-by? {:triggered-by {:job-name "Test" :build-id "42"}} {:job "Test" :build-id "42"})))

  (testing "should return false if not triggered by build"
    (is (not (schema/was-triggered-by? {:triggered-by {:job-name "Test" :build-id 42}} {:job "Deploy" :build-id 42})))
    (is (not (schema/was-triggered-by? {:triggered-by {:job-name "Test" :build-id 42}} {:job "Test" :build-id 43})))))