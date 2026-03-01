(ns pekko-clj.http.integration-test
  "Integration tests for HTTP server and client."
  (:require [clojure.test :refer :all]
            [pekko-clj.core :as core]
            [pekko-clj.stream :as stream]
            [pekko-clj.http.core :as http]
            [pekko-clj.http.routing :as routing]
            [pekko-clj.http.response :as resp]
            [pekko-clj.http.client :as client])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.stream Materializer]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [java.net ServerSocket]))

(def ^:dynamic *system* nil)
(def ^:dynamic *mat* nil)
(def ^:dynamic *port* nil)
(def ^:dynamic *binding* nil)

(defn find-free-port []
  (with-open [socket (ServerSocket. 0)]
    (.getLocalPort socket)))

(defn actor-system-fixture [f]
  (let [sys (core/actor-system "http-integration-test")
        mat (stream/materializer sys)
        port (find-free-port)]
    (try
      (binding [*system* sys
                *mat* mat
                *port* port]
        (f))
      (finally
        (.terminate sys)
        (Await/result (.whenTerminated sys) (Duration/create 10 "seconds"))))))

(use-fixtures :each actor-system-fixture)

;; ---------------------------------------------------------------------------
;; Helper to start and stop server for each test
;; ---------------------------------------------------------------------------

(defn with-test-server [routes test-fn]
  (let [binding-future (http/bind-server *system* "127.0.0.1" *port* routes)
        binding (stream/await-completion binding-future 5000)]
    (try
      (test-fn)
      (finally
        (stream/await-completion (http/unbind binding) 5000)))))

;; ---------------------------------------------------------------------------
;; Simple Server Tests
;; ---------------------------------------------------------------------------

(deftest simple-get-test
  (testing "Simple GET request and response"
    (let [routes (routing/path "hello"
                   (routing/method-get
                     (routing/path-end
                       (routing/complete "Hello, World!"))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/hello"))
                             (client/await-response 5000))]
            (is (client/successful? response))
            (is (= 200 (client/response-status response)))
            (let [body (-> (client/response-body response *system*)
                           (client/await-response 5000))]
              (is (= "Hello, World!" body)))))))))

(deftest not-found-test
  (testing "404 for non-existent path"
    (let [routes (routing/routes
                   (routing/path "exists"
                     (routing/method-get
                       (routing/path-end
                         (routing/complete "exists"))))
                   (routing/not-found "Page not found"))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/nonexistent"))
                             (client/await-response 5000))]
            (is (= 404 (client/response-status response)))))))))

(deftest post-with-body-test
  (testing "POST request with body"
    (let [received-body (atom nil)
          routes (routing/path "echo"
                   (routing/method-post
                     (routing/path-end
                       (routing/extract-request
                         (fn [req]
                           (routing/extract-materializer
                             (fn [mat]
                               (routing/complete-future
                                 (-> (http/entity->string req mat)
                                     (client/then-apply
                                       (fn [body]
                                         (reset! received-body body)
                                         (resp/ok body))))))))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/POST *system*
                                          (str "http://127.0.0.1:" *port* "/echo")
                                          {:body "Test body content"
                                           :content-type :plain})
                             (client/await-response 5000))]
            (is (client/successful? response))
            (let [body (-> (client/response-body response *system*)
                           (client/await-response 5000))]
              (is (= "Test body content" body))
              (is (= "Test body content" @received-body)))))))))

(deftest multiple-routes-test
  (testing "Multiple routes"
    (let [routes (routing/routes
                   (routing/path "users"
                     (routing/method-get
                       (routing/path-end
                         (routing/complete "[{\"id\":1},{\"id\":2}]"))))
                   (routing/path "posts"
                     (routing/method-get
                       (routing/path-end
                         (routing/complete "[{\"id\":1,\"title\":\"Hello\"}]"))))
                   (routing/not-found "Not found"))]
      (with-test-server routes
        (fn []
          ;; Test users endpoint
          (let [users-response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/users"))
                                   (client/await-response 5000))]
            (is (client/successful? users-response))
            (let [body (-> (client/response-body users-response *system*)
                           (client/await-response 5000))]
              (is (clojure.string/includes? body "id"))))

          ;; Test posts endpoint
          (let [posts-response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/posts"))
                                   (client/await-response 5000))]
            (is (client/successful? posts-response))))))))

(deftest json-content-type-test
  (testing "JSON response with proper content type"
    (let [routes (routing/path "data"
                   (routing/method-get
                     (routing/path-end
                       (routing/complete :ok (resp/json "{\"key\":\"value\"}")))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/data"))
                             (client/await-response 5000))]
            (is (client/successful? response))
            ;; Content-type is in the entity, not the headers
            (let [body (-> (client/response-body response *system*)
                           (client/await-response 5000))]
              (is (= "{\"key\":\"value\"}" body)))))))))

(deftest path-prefix-test
  (testing "Path prefix routing"
    (let [routes (routing/path-prefix "api"
                   (routing/routes
                     (routing/path-prefix "v1"
                       (routing/path "users"
                         (routing/method-get
                           (routing/path-end
                             (routing/complete "v1 users")))))
                     (routing/path-prefix "v2"
                       (routing/path "users"
                         (routing/method-get
                           (routing/path-end
                             (routing/complete "v2 users")))))))]
      (with-test-server routes
        (fn []
          (let [v1-response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/api/v1/users"))
                                (client/await-response 5000))]
            (is (client/successful? v1-response))
            (let [body (-> (client/response-body v1-response *system*)
                           (client/await-response 5000))]
              (is (= "v1 users" body))))

          (let [v2-response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/api/v2/users"))
                                (client/await-response 5000))]
            (is (client/successful? v2-response))
            (let [body (-> (client/response-body v2-response *system*)
                           (client/await-response 5000))]
              (is (= "v2 users" body)))))))))

(deftest different-http-methods-test
  (testing "Different HTTP methods on same path"
    (let [routes (routing/path "resource"
                   (routing/routes
                     (routing/method-get
                       (routing/path-end
                         (routing/complete "GET resource")))
                     (routing/method-post
                       (routing/path-end
                         (routing/complete :created "POST resource")))
                     (routing/method-put
                       (routing/path-end
                         (routing/complete "PUT resource")))
                     (routing/method-delete
                       (routing/path-end
                         (routing/complete "DELETE resource")))))]
      (with-test-server routes
        (fn []
          ;; GET
          (let [get-response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/resource"))
                                 (client/await-response 5000))]
            (is (= 200 (client/response-status get-response))))

          ;; POST
          (let [post-response (-> (client/POST *system* (str "http://127.0.0.1:" *port* "/resource"))
                                  (client/await-response 5000))]
            (is (= 201 (client/response-status post-response))))

          ;; PUT
          (let [put-response (-> (client/PUT *system* (str "http://127.0.0.1:" *port* "/resource"))
                                 (client/await-response 5000))]
            (is (= 200 (client/response-status put-response))))

          ;; DELETE
          (let [delete-response (-> (client/DELETE *system* (str "http://127.0.0.1:" *port* "/resource"))
                                    (client/await-response 5000))]
            (is (= 200 (client/response-status delete-response)))))))))

(deftest request-handler-test
  (testing "Custom request handler"
    (let [routes (routing/path "custom"
                   (routing/handle-request
                     (fn [req]
                       (let [method (http/request-method req)
                             path (http/request-path req)]
                         (resp/ok (str "Method: " (name method) ", Path: " path))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/custom"))
                             (client/await-response 5000))]
            (is (client/successful? response))
            (let [body (-> (client/response-body response *system*)
                           (client/await-response 5000))]
              (is (clojure.string/includes? body "Method: get"))
              (is (clojure.string/includes? body "Path: /custom")))))))))
