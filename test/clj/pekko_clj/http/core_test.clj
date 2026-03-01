(ns pekko-clj.http.core-test
  (:require [clojure.test :refer :all]
            [pekko-clj.http.core :as http])
  (:import [org.apache.pekko.http.javadsl.model HttpRequest HttpMethods]))

;; ---------------------------------------------------------------------------
;; Path Matching Tests
;; ---------------------------------------------------------------------------

(deftest match-path-pattern-exact-test
  (testing "Exact path matching"
    (is (= {} (http/match-path-pattern "/users" "/users")))
    (is (= {} (http/match-path-pattern "/api/v1/users" "/api/v1/users")))))

(deftest match-path-pattern-with-params-test
  (testing "Path matching with parameters"
    (is (= {:id "123"} (http/match-path-pattern "/users/123" "/users/:id")))
    (is (= {:id "456" :action "edit"}
           (http/match-path-pattern "/users/456/edit" "/users/:id/:action")))))

(deftest match-path-pattern-no-match-test
  (testing "Non-matching paths return nil"
    (is (nil? (http/match-path-pattern "/users" "/posts")))
    (is (nil? (http/match-path-pattern "/users/123" "/users")))
    (is (nil? (http/match-path-pattern "/users" "/users/:id")))))

(deftest match-path-pattern-complex-test
  (testing "Complex path patterns"
    (is (= {:version "v1" :resource "users" :id "42"}
           (http/match-path-pattern "/api/v1/users/42" "/api/:version/:resource/:id")))))

;; ---------------------------------------------------------------------------
;; Request Accessor Tests (using mock requests)
;; ---------------------------------------------------------------------------

(deftest request-method-test
  (testing "Request method extraction"
    (let [get-req (HttpRequest/GET "http://example.com/test")
          post-req (HttpRequest/POST "http://example.com/test")]
      (is (= :get (http/request-method get-req)))
      (is (= :post (http/request-method post-req))))))

(deftest request-path-test
  (testing "Request path extraction"
    (let [req (HttpRequest/GET "http://example.com/api/v1/users?page=1")]
      (is (= "/api/v1/users" (http/request-path req))))))

(deftest request-query-params-test
  (testing "Query parameter extraction"
    (let [req (HttpRequest/GET "http://example.com/users?page=1&limit=10")]
      (is (= {"page" "1" "limit" "10"} (http/request-query-params req))))))

(deftest path-segments-test
  (testing "Path segment splitting"
    (let [req (HttpRequest/GET "http://example.com/api/v1/users/123")]
      (is (= ["api" "v1" "users" "123"] (http/path-segments req))))))

;; ---------------------------------------------------------------------------
;; Async Utility Tests
;; ---------------------------------------------------------------------------

(deftest completed-test
  (testing "Completed future creation"
    (let [stage (http/completed "test-value")
          result (.get (.toCompletableFuture stage))]
      (is (= "test-value" result)))))

(deftest then-apply-test
  (testing "then-apply transformation"
    (let [stage (http/completed 10)
          result (-> stage
                     (http/then-apply #(* % 2))
                     (.toCompletableFuture)
                     (.get))]
      (is (= 20 result)))))

(deftest then-test
  (testing "then chaining"
    (let [stage (http/completed 5)
          result (-> stage
                     (http/then #(http/completed (* % 3)))
                     (.toCompletableFuture)
                     (.get))]
      (is (= 15 result)))))
