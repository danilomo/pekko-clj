(ns pekko-clj.http.response-test
  (:require [clojure.test :refer :all]
            [pekko-clj.http.response :as resp])
  (:import [org.apache.pekko.http.javadsl.model StatusCodes ContentTypes HttpResponse]))

;; ---------------------------------------------------------------------------
;; Status Code Tests
;; ---------------------------------------------------------------------------

(deftest status-codes-test
  (testing "Status codes are correctly mapped"
    (is (= StatusCodes/OK (resp/->status-code :ok)))
    (is (= StatusCodes/CREATED (resp/->status-code :created)))
    (is (= StatusCodes/NOT_FOUND (resp/->status-code :not-found)))
    (is (= StatusCodes/INTERNAL_SERVER_ERROR (resp/->status-code :internal-server-error)))))

(deftest status-code-passthrough-test
  (testing "StatusCode instances pass through"
    (let [sc StatusCodes/OK]
      (is (= sc (resp/->status-code sc))))))

(deftest custom-status-code-test
  (testing "Integer status codes create custom codes"
    (let [sc (resp/->status-code 418)]
      (is (= 418 (.intValue sc))))))

(deftest invalid-status-code-test
  (testing "Invalid status keyword throws exception"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown status code"
          (resp/->status-code :invalid-status)))))

;; ---------------------------------------------------------------------------
;; Content Type Tests
;; ---------------------------------------------------------------------------

(deftest content-types-test
  (testing "Content types are correctly mapped"
    (is (= ContentTypes/APPLICATION_JSON (resp/->content-type :json)))
    (is (= ContentTypes/TEXT_HTML_UTF8 (resp/->content-type :html)))
    (is (= ContentTypes/TEXT_PLAIN_UTF8 (resp/->content-type :plain)))))

(deftest content-type-passthrough-test
  (testing "ContentType instances pass through"
    (let [ct ContentTypes/APPLICATION_JSON]
      (is (= ct (resp/->content-type ct))))))

;; ---------------------------------------------------------------------------
;; Entity Tests
;; ---------------------------------------------------------------------------

(deftest json-entity-test
  (testing "JSON entity creation"
    (let [entity (resp/json "{\"name\": \"test\"}")]
      (is entity)
      (is (= ContentTypes/APPLICATION_JSON (.getContentType entity))))))

(deftest text-entity-test
  (testing "Text entity creation"
    (let [entity (resp/text "Hello, World!")]
      (is entity)
      (is (= ContentTypes/TEXT_PLAIN_UTF8 (.getContentType entity))))))

(deftest html-entity-test
  (testing "HTML entity creation"
    (let [entity (resp/html "<h1>Hello</h1>")]
      (is entity)
      (is (= ContentTypes/TEXT_HTML_UTF8 (.getContentType entity))))))

;; ---------------------------------------------------------------------------
;; Response Builder Tests
;; ---------------------------------------------------------------------------

(deftest response-with-status-and-body-test
  (testing "Response creation with status and body"
    (let [r (resp/response :ok "Hello")]
      (is (instance? HttpResponse r))
      (is (= StatusCodes/OK (.status r))))))

(deftest ok-response-test
  (testing "OK response helper"
    (let [r (resp/ok "Success")]
      (is (= StatusCodes/OK (.status r))))))

(deftest created-response-test
  (testing "Created response helper"
    (let [r (resp/created "Created")]
      (is (= StatusCodes/CREATED (.status r))))))

(deftest not-found-response-test
  (testing "Not found response helper"
    (let [r (resp/not-found "Not found")]
      (is (= StatusCodes/NOT_FOUND (.status r))))))

(deftest no-content-response-test
  (testing "No content response helper"
    (let [r (resp/no-content)]
      (is (= StatusCodes/NO_CONTENT (.status r))))))

(deftest bad-request-response-test
  (testing "Bad request response helper"
    (let [r (resp/bad-request "Invalid input")]
      (is (= StatusCodes/BAD_REQUEST (.status r))))))

(deftest internal-server-error-response-test
  (testing "Internal server error response helper"
    (let [r (resp/internal-server-error "Server error")]
      (is (= StatusCodes/INTERNAL_SERVER_ERROR (.status r))))))
