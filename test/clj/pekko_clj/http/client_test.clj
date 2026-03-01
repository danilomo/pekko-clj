(ns pekko-clj.http.client-test
  (:require [clojure.test :refer :all]
            [pekko-clj.http.client :as client])
  (:import [org.apache.pekko.http.javadsl.model HttpResponse StatusCodes]))

;; ---------------------------------------------------------------------------
;; Response Status Tests
;; ---------------------------------------------------------------------------

(deftest response-status-test
  (testing "Response status extraction"
    (let [response (HttpResponse/create)]
      (is (= 200 (client/response-status response))))))

(deftest response-status-keyword-test
  (testing "Response status keyword mapping"
    (let [ok-response (HttpResponse/create)
          not-found-response (-> (HttpResponse/create)
                                  (.withStatus StatusCodes/NOT_FOUND))]
      (is (= :ok (client/response-status-keyword ok-response)))
      (is (= :not-found (client/response-status-keyword not-found-response))))))

;; ---------------------------------------------------------------------------
;; Response Status Check Tests
;; ---------------------------------------------------------------------------

(deftest successful-test
  (testing "Successful response check"
    (let [ok-response (HttpResponse/create)
          created-response (-> (HttpResponse/create)
                                (.withStatus StatusCodes/CREATED))
          error-response (-> (HttpResponse/create)
                              (.withStatus StatusCodes/NOT_FOUND))]
      (is (client/successful? ok-response))
      (is (client/successful? created-response))
      (is (not (client/successful? error-response))))))

(deftest client-error-test
  (testing "Client error response check"
    (let [ok-response (HttpResponse/create)
          bad-request (-> (HttpResponse/create)
                           (.withStatus StatusCodes/BAD_REQUEST))
          not-found (-> (HttpResponse/create)
                         (.withStatus StatusCodes/NOT_FOUND))]
      (is (not (client/client-error? ok-response)))
      (is (client/client-error? bad-request))
      (is (client/client-error? not-found)))))

(deftest server-error-test
  (testing "Server error response check"
    (let [ok-response (HttpResponse/create)
          server-error (-> (HttpResponse/create)
                            (.withStatus StatusCodes/INTERNAL_SERVER_ERROR))
          bad-gateway (-> (HttpResponse/create)
                           (.withStatus StatusCodes/BAD_GATEWAY))]
      (is (not (client/server-error? ok-response)))
      (is (client/server-error? server-error))
      (is (client/server-error? bad-gateway)))))

;; ---------------------------------------------------------------------------
;; Async Utility Tests
;; ---------------------------------------------------------------------------

(deftest then-apply-test
  (testing "then-apply transformation"
    (let [stage (java.util.concurrent.CompletableFuture/completedFuture 10)
          result (-> stage
                     (client/then-apply #(* % 2))
                     (.toCompletableFuture)
                     (.get))]
      (is (= 20 result)))))

(deftest then-test
  (testing "then chaining"
    (let [stage (java.util.concurrent.CompletableFuture/completedFuture 5)
          result (-> stage
                     (client/then #(java.util.concurrent.CompletableFuture/completedFuture (* % 3)))
                     (.toCompletableFuture)
                     (.get))]
      (is (= 15 result)))))

(deftest await-response-test
  (testing "await-response blocking"
    (let [stage (java.util.concurrent.CompletableFuture/completedFuture "test-value")
          result (client/await-response stage 1000)]
      (is (= "test-value" result)))))

(deftest await-response-timeout-test
  (testing "await-response timeout"
    (let [stage (java.util.concurrent.CompletableFuture.)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"timed out"
            (client/await-response stage 100))))))

;; ---------------------------------------------------------------------------
;; Note: Full client tests with actual HTTP requests require a running server
;; See integration_test.clj for end-to-end tests
;; ---------------------------------------------------------------------------
