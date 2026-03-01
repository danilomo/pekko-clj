(ns pekko-clj.http.routing-test
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.stream :as stream]
            [pekko-clj.http.core :as http]
            [pekko-clj.http.routing :as routing]
            [pekko-clj.http.response :as resp])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.http.javadsl Http]
           [org.apache.pekko.http.javadsl.model HttpRequest StatusCodes]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]))

(def ^:dynamic *system* nil)
(def ^:dynamic *http* nil)

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "routing-test")
        http (Http/get sys)]
    (try
      (binding [*system* sys
                *http* http]
        (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each actor-system-fixture)

;; ---------------------------------------------------------------------------
;; Route Composition Tests
;; ---------------------------------------------------------------------------

(deftest routes-combine-test
  (testing "Routes combine correctly"
    (let [route1 (routing/path "users" (routing/complete "users"))
          route2 (routing/path "posts" (routing/complete "posts"))
          combined (routing/routes route1 route2)]
      (is combined))))

;; ---------------------------------------------------------------------------
;; Complete Directive Tests
;; ---------------------------------------------------------------------------

(deftest complete-with-string-test
  (testing "Complete with string body"
    (let [route (routing/complete "Hello, World!")]
      (is route))))

(deftest complete-with-status-and-body-test
  (testing "Complete with status and body"
    (let [route (routing/complete :ok "Success")]
      (is route))))

(deftest not-found-test
  (testing "Not found response"
    (let [route (routing/not-found "Page not found")]
      (is route))))

;; ---------------------------------------------------------------------------
;; Path Directive Tests
;; ---------------------------------------------------------------------------

(deftest path-directive-test
  (testing "Path directive matches exact path"
    (let [route (routing/path "test" (routing/complete "matched"))]
      (is route))))

(deftest path-prefix-directive-test
  (testing "Path prefix directive matches prefix"
    (let [route (routing/path-prefix "api"
                  (routing/path "users"
                    (routing/complete "users list")))]
      (is route))))

;; ---------------------------------------------------------------------------
;; Method Directive Tests
;; ---------------------------------------------------------------------------

(deftest method-get-test
  (testing "GET method directive"
    (let [route (routing/method-get (routing/complete "GET response"))]
      (is route))))

(deftest method-post-test
  (testing "POST method directive"
    (let [route (routing/method-post (routing/complete "POST response"))]
      (is route))))

(deftest method-put-test
  (testing "PUT method directive"
    (let [route (routing/method-put (routing/complete "PUT response"))]
      (is route))))

(deftest method-delete-test
  (testing "DELETE method directive"
    (let [route (routing/method-delete (routing/complete "DELETE response"))]
      (is route))))

;; ---------------------------------------------------------------------------
;; Macro Tests
;; ---------------------------------------------------------------------------

(deftest get-macro-simple-path-test
  (testing "GET macro with simple path"
    (let [route (routing/GET "/users" []
                  (routing/complete "users list"))]
      (is route))))

(deftest post-macro-simple-path-test
  (testing "POST macro with simple path"
    (let [route (routing/POST "/users" []
                  (routing/complete :created "user created"))]
      (is route))))

;; ---------------------------------------------------------------------------
;; Combined Route Tests
;; ---------------------------------------------------------------------------

(deftest rest-api-routes-test
  (testing "REST API style routes"
    (let [routes (routing/routes
                   (routing/path-prefix "api"
                     (routing/routes
                       (routing/path "users"
                         (routing/routes
                           (routing/method-get (routing/complete "list users"))
                           (routing/method-post (routing/complete :created "create user"))))
                       (routing/path "posts"
                         (routing/method-get (routing/complete "list posts")))))
                   (routing/not-found "Page not found"))]
      (is routes))))

;; ---------------------------------------------------------------------------
;; Extract Directive Tests
;; ---------------------------------------------------------------------------

(deftest extract-request-test
  (testing "Extract request directive"
    (let [route (routing/extract-request
                  (fn [req]
                    (routing/complete (http/request-method req))))]
      (is route))))

(deftest handle-request-test
  (testing "Handle request function"
    (let [route (routing/handle-request
                  (fn [req]
                    (resp/ok "handled")))]
      (is route))))
