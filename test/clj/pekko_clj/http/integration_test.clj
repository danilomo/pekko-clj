(ns pekko-clj.http.integration-test
  "Integration tests for HTTP server and client."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [pekko-clj.core :as core]
            [pekko-clj.stream :as stream]
            [pekko-clj.http.core :as http]
            [pekko-clj.http.routing :as routing]
            [pekko-clj.http.response :as resp]
            [pekko-clj.http.marshalling :as marshal]
            [pekko-clj.http.client :as client]
            [pekko-clj.test-support :as ts])
  (:import [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.http.javadsl Http]
           [org.apache.pekko.http.javadsl.model.ws WebSocketRequest]
           [org.apache.pekko.stream Materializer]
           [org.apache.pekko.stream.javadsl Flow]
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

;; ---------------------------------------------------------------------------
;; Body marshalling (N7)
;; ---------------------------------------------------------------------------

(defn- url [path] (str "http://127.0.0.1:" *port* path))

(defn- get-body [response]
  (-> (client/response-body response *system*)
      (client/await-response 5000)))

(deftest json-body-round-trip-test
  (testing "with-json-body parses the request, complete-json encodes the response"
    (let [received (atom nil)
          routes (routing/path "users"
                   (routing/method-post
                     (routing/path-end
                       (routing/with-json-body
                         (fn [data]
                           (reset! received data)
                           (routing/complete-json :created
                             {:id 7 :name (:name data) :tags (:tags data)}))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/POST *system* (url "/users")
                                          {:body (marshal/->json {:name "ada" :tags ["x" "y"]})
                                           :content-type :json})
                             (client/await-response 5000))]
            (is (= 201 (client/response-status response)))
            ;; Handler saw real Clojure data with keywordized keys
            (is (= {:name "ada" :tags ["x" "y"]} @received))
            (let [body (get-body response)]
              (is (= {:id 7 :name "ada" :tags ["x" "y"]} (marshal/json-> body)))
              (is (str/includes? body "\"name\":\"ada\"") "the response is JSON, not EDN"))))))))

(deftest malformed-json-body-test
  (testing "a malformed JSON body completes 400 instead of throwing"
    (let [routes (routing/path "users"
                   (routing/method-post
                     (routing/path-end
                       (routing/with-json-body (fn [_] (routing/complete "should not happen"))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/POST *system* (url "/users")
                                          {:body "{not json" :content-type :json})
                             (client/await-response 5000))]
            (is (= 400 (client/response-status response)))
            (is (str/includes? (get-body response) "Malformed JSON"))))))))

(deftest edn-body-round-trip-test
  (testing "with-edn-body / complete-edn"
    (let [routes (routing/path "edn"
                   (routing/method-post
                     (routing/path-end
                       (routing/with-edn-body
                         (fn [data] (routing/complete-edn {:echo data :n (count (:items data))}))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/POST *system* (url "/edn")
                                          {:body (pr-str {:items #{:a :b}})
                                           :content-type :edn})
                             (client/await-response 5000))
                body (marshal/edn-> (get-body response))]
            (is (= 200 (client/response-status response)))
            (is (= {:items #{:a :b}} (:echo body)) "sets survive the EDN round trip")
            (is (= 2 (:n body)))))))))

(deftest with-request-body-string-test
  (testing "with-request-body hands the handler a plain string (previously stubbed)"
    (let [routes (routing/path "raw"
                   (routing/method-post
                     (routing/path-end
                       (routing/with-request-body
                         (fn [body] (routing/complete (str "got:" body)))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/POST *system* (url "/raw")
                                          {:body "hello" :content-type :plain})
                             (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (= "got:hello" (get-body response)))))))))

;; ---------------------------------------------------------------------------
;; Params & form fields (N7)
;; ---------------------------------------------------------------------------

(deftest with-body-dispatches-on-content-type-test
  (testing "with-body parses JSON, EDN, and leaves anything else a string"
    (let [routes (routing/path "any"
                   (routing/method-post
                     (routing/path-end
                       (routing/with-body
                         (fn [data] (routing/complete (pr-str data)))))))]
      (with-test-server routes
        (fn []
          (let [json-resp (-> (client/POST *system* (url "/any")
                                           {:body "{\"a\":1}" :content-type :json})
                              (client/await-response 5000))]
            (is (= "{:a 1}" (get-body json-resp))))
          (let [edn-resp (-> (client/POST *system* (url "/any")
                                          {:body "{:a 1}" :content-type :edn})
                             (client/await-response 5000))]
            (is (= "{:a 1}" (get-body edn-resp))))
          (let [text-resp (-> (client/POST *system* (url "/any")
                                           {:body "just text" :content-type :plain})
                              (client/await-response 5000))]
            (is (= "\"just text\"" (get-body text-resp)))))))))

(deftest single-form-field-test
  (testing "form-field / form-field-opt"
    (let [routes (routing/path "login"
                   (routing/method-post
                     (routing/path-end
                       (routing/form-field "username"
                         (fn [username]
                           (routing/form-field-opt "realm" "default"
                             (fn [realm]
                               (routing/complete (str username "@" realm)))))))))]
      (with-test-server routes
        (fn []
          ;; The optional field falls back to its default when absent
          (let [response (-> (client/POST *system* (url "/login")
                                          {:body "username=ada" :content-type :form})
                             (client/await-response 5000))]
            (is (= "ada@default" (get-body response))))
          (let [response (-> (client/POST *system* (url "/login")
                                          {:body "username=ada&realm=lovelace" :content-type :form})
                             (client/await-response 5000))]
            (is (= "ada@lovelace" (get-body response))))
          ;; A missing required field is a rejection (400), not a 500
          (let [response (-> (client/POST *system* (url "/login")
                                          {:body "realm=x" :content-type :form})
                             (client/await-response 5000))]
            (is (= 400 (client/response-status response)))))))))

(deftest query-params-test
  (let [routes (routing/path "search"
                 (routing/method-get
                   (routing/path-end
                     (routing/params
                       (fn [{:keys [q page]}]
                         (routing/complete-json {:q q :page page}))))))]
    (with-test-server routes
      (fn []
        (let [response (-> (client/GET *system* (url "/search?q=pekko&page=2"))
                           (client/await-response 5000))]
          (is (= {:q "pekko" :page "2"} (marshal/json-> (get-body response)))))))))

(deftest form-fields-test
  (let [routes (routing/path "login"
                 (routing/method-post
                   (routing/path-end
                     (routing/routes
                       (routing/form-fields
                         (fn [{:keys [username]}]
                           (routing/complete (str "hello " username))))))))]
    (with-test-server routes
      (fn []
        (let [response (-> (client/POST *system* (url "/login")
                                        {:body "username=ada&password=secret"
                                         :content-type :form})
                           (client/await-response 5000))]
          (is (= "hello ada" (get-body response))))))))

;; ---------------------------------------------------------------------------
;; Rejection & exception handling (N7)
;; ---------------------------------------------------------------------------

(deftest exception-handler-test
  (testing "a throwing handler is turned into a response instead of a bare 500"
    (let [routes (routing/handle-exceptions
                   (routing/exception-handler
                     {IllegalArgumentException (fn [e] (routing/complete :bad-request
                                                                         (str "bad: " (.getMessage e))))
                      Throwable (fn [_] (routing/complete :internal-server-error "unexpected"))})
                   (routing/routes
                     (routing/path "boom"
                       (routing/method-get
                         (routing/path-end
                           (routing/handle-request
                             (fn [_] (throw (IllegalArgumentException. "nope")))))))
                     (routing/path "kaboom"
                       (routing/method-get
                         (routing/path-end
                           (routing/handle-request
                             (fn [_] (throw (RuntimeException. "other")))))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (url "/boom")) (client/await-response 5000))]
            (is (= 400 (client/response-status response)))
            (is (= "bad: nope" (get-body response))))
          (let [response (-> (client/GET *system* (url "/kaboom")) (client/await-response 5000))]
            (is (= 500 (client/response-status response)))
            (is (= "unexpected" (get-body response)))))))))

(deftest rejection-handler-test
  (testing "custom not-found rejection handling"
    (let [routes (routing/handle-rejections
                   (routing/rejection-handler
                     {:not-found (routing/complete :not-found "custom 404")})
                   (routing/path "exists"
                     (routing/method-get
                       (routing/path-end (routing/complete "here")))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (url "/missing")) (client/await-response 5000))]
            (is (= 404 (client/response-status response)))
            (is (= "custom 404" (get-body response))))
          ;; A wrong method on an existing path is a MethodRejection, not not-found
          (let [response (-> (client/POST *system* (url "/exists")) (client/await-response 5000))]
            (is (= 405 (client/response-status response)))))))))

;; ---------------------------------------------------------------------------
;; WebSockets (N7)
;; ---------------------------------------------------------------------------

(deftest websocket-echo-test
  (testing "handleWebSocketMessages over a stream Flow"
    (let [routes (routing/path "ws"
                   (routing/websocket (routing/text-flow str/upper-case)))]
      (with-test-server routes
        (fn []
          (let [received (atom [])
                client-flow (Flow/fromSinkAndSource
                             (stream/sink-foreach
                              (fn [msg] (swap! received conj (routing/message->text msg))))
                             (stream/source [(routing/text-message "ping")]))
                pair (.singleWebSocketRequest
                      (Http/get *system*)
                      (WebSocketRequest/create (str "ws://127.0.0.1:" *port* "/ws"))
                      client-flow
                      *mat*)
                upgrade (deref (.first pair) 5000 nil)]
            (is (some? upgrade))
            (is (= 101 (.intValue (.status (.response upgrade)))) "protocol switch")
            (is (ts/poll-until #(seq @received) 5000))
            (is (= ["PING"] @received))))))))

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
