(ns pekko-clj.http.integration-test
  "Integration tests for HTTP server and client."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [pekko-clj.core :as core]
            [pekko-clj.stream :as stream]
            [pekko-clj.http.core :as http]
            [pekko-clj.http.routing :as routing]
            [pekko-clj.http.response :as resp]
            [pekko-clj.http.marshalling :as marshal]
            [pekko-clj.http.client :as client]
            [pekko-clj.http.tls :as tls]
            [pekko-clj.test-support :as ts])
  (:import [org.apache.pekko.http.javadsl Http]
           [org.apache.pekko.http.javadsl.model HttpResponse]
           [org.apache.pekko.http.javadsl.model.ws WebSocketRequest]
           [org.apache.pekko.stream.javadsl Flow]
           [com.typesafe.config ConfigFactory]
           [scala.concurrent Await]
           [scala.concurrent.duration Duration]
           [java.net ServerSocket]
           [java.util.function Supplier]
           [java.util.zip GZIPOutputStream GZIPInputStream]
           [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util.concurrent CompletableFuture]))

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
                       ;; N15: a bare string is now encoded as a JSON string value,
                       ;; so a pre-encoded body has to say so with raw-body.
                       (routing/complete :ok (resp/json (marshal/raw-body "{\"key\":\"value\"}"))))))]
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

;; ---------------------------------------------------------------------------
;; Compojure-style macros (B11)
;;
;; The static-path branch used to expand to (path "/users" …), but javadsl's
;; path(String) matches a *single segment* — a string with a leading slash can
;; never match, so every static macro route answered 404. The :param branch
;; matched the *full* request path, so it ignored any enclosing path-prefix.
;; Both are end-to-end failures the old "the route object is non-nil" unit
;; tests could not see.
;; ---------------------------------------------------------------------------

(deftest macro-static-path-test
  (testing "a static macro path matches, with or without the leading slash"
    (let [routes (routing/routes
                   (routing/GET "/users" []
                     (routing/complete "users list"))
                   (routing/GET "posts" []
                     (routing/complete "posts list")))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (url "/users")) (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (= "users list" (get-body response))))
          (let [response (-> (client/GET *system* (url "/posts")) (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (= "posts list" (get-body response)))))))))

(deftest macro-param-path-test
  (testing "a :param segment binds by name; multiple params bind in order"
    (let [routes (routing/routes
                   (routing/GET "/users/:id" [id]
                     (routing/complete (str "user " id)))
                   (routing/GET "/users/:id/posts/:post-id" [id post-id]
                     (routing/complete (str "user " id " post " post-id)))
                   (routing/POST "/users/:id" [id]
                     (routing/complete :created (str "created " id))))]
      (with-test-server routes
        (fn []
          (is (= "user 42" (-> (client/GET *system* (url "/users/42"))
                               (client/await-response 5000)
                               get-body)))
          (is (= "user 42 post 7" (-> (client/GET *system* (url "/users/42/posts/7"))
                                      (client/await-response 5000)
                                      get-body)))
          (let [response (-> (client/POST *system* (url "/users/9")) (client/await-response 5000))]
            (is (= 201 (client/response-status response)))
            (is (= "created 9" (get-body response)))))))))

(deftest macro-nests-under-path-prefix-test
  (testing "macro routes consume only their own segments, so they nest"
    (let [routes (routing/path-prefix "api"
                   (routing/path-prefix "v1"
                     (routing/routes
                       (routing/GET "/users" []
                         (routing/complete "v1 users"))
                       (routing/GET "/users/:id" [id]
                         (routing/complete (str "v1 user " id))))))]
      (with-test-server routes
        (fn []
          (is (= "v1 users" (-> (client/GET *system* (url "/api/v1/users"))
                                (client/await-response 5000)
                                get-body)))
          (is (= "v1 user 3" (-> (client/GET *system* (url "/api/v1/users/3"))
                                 (client/await-response 5000)
                                 get-body)))
          ;; The prefix is not optional, and the pattern is not a prefix match
          (is (= 404 (-> (client/GET *system* (url "/users"))
                         (client/await-response 5000)
                         client/response-status))))))))

(deftest path-var-directives-test
  (testing "path-var / path-prefix-var capture a segment for hand-built routes"
    (let [routes (routing/path-prefix "users"
                   (routing/routes
                     (routing/path-prefix-var
                       (fn [id]
                         (routing/path "posts"
                           (routing/method-get
                             (routing/complete (str "posts of " id))))))
                     (routing/path-var
                       (fn [id]
                         (routing/method-get
                           (routing/complete (str "user " id)))))))]
      (with-test-server routes
        (fn []
          (is (= "user 5" (-> (client/GET *system* (url "/users/5"))
                              (client/await-response 5000)
                              get-body)))
          (is (= "posts of 5" (-> (client/GET *system* (url "/users/5/posts"))
                                  (client/await-response 5000)
                                  get-body))))))))

(deftest multi-segment-path-directive-test
  (testing "path / path-prefix take several segments and tolerate a leading slash"
    (let [routes (routing/routes
                   (routing/path "/api/health"
                     (routing/method-get (routing/complete "ok")))
                   (routing/path-prefix "/api/v2"
                     (routing/path "users"
                       (routing/method-get (routing/complete "v2 users")))))]
      (with-test-server routes
        (fn []
          (is (= "ok" (-> (client/GET *system* (url "/api/health"))
                          (client/await-response 5000)
                          get-body)))
          (is (= "v2 users" (-> (client/GET *system* (url "/api/v2/users"))
                                (client/await-response 5000)
                                get-body))))))))

(deftest macro-path-must-end-test
  (testing "a longer path than the pattern is a miss, not a prefix match"
    (let [routes (routing/routes
                   (routing/GET "/users" []
                     (routing/complete "users list"))
                   (routing/GET "/users/:id" [id]
                     (routing/complete (str "user " id))))]
      (with-test-server routes
        (fn []
          (is (= 404 (-> (client/GET *system* (url "/users/42/extra"))
                         (client/await-response 5000)
                         client/response-status))
              "trailing segments beyond the pattern do not match")
          (is (= 404 (-> (client/GET *system* (url "/usersx"))
                         (client/await-response 5000)
                         client/response-status))
              "segments match whole, not by prefix")
          ;; A matching path with the wrong method is a MethodRejection (405),
          ;; which only works because the path directive is the outer one.
          (is (= 405 (-> (client/DELETE *system* (url "/users"))
                         (client/await-response 5000)
                         client/response-status))))))))

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

(deftest bind-server-with-function-handler-test
  ;; Regression: bind-server documents "a Route or function (request ->
  ;; CompletionStage<HttpResponse>)", but the function branch reified
  ;; java.util.function.Function while ServerBuilder.bind takes Pekko's
  ;; japi.function.Function — so it threw "No matching method bind found taking
  ;; 1 args". Reflection hid the mismatch and no test exercised this branch.
  (testing "a plain function handler binds and serves requests"
    (let [handler (fn [req]
                    (CompletableFuture/completedFuture
                     (resp/ok (str "fn-handler:" (http/request-path req)))))
          binding (-> (http/bind-server *system* "127.0.0.1" *port* handler)
                      (stream/await-completion 5000))]
      (try
        (let [response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/anything"))
                           (client/await-response 5000))]
          (is (client/successful? response))
          (is (= "fn-handler:/anything"
                 (-> (client/response-body response *system*)
                     (client/await-response 5000)))))
        (finally
          (stream/await-completion (http/unbind binding) 5000))))))

;; ---------------------------------------------------------------------------
;; N15: static content
;; ---------------------------------------------------------------------------

(defn- get-status+body
  "GET url and return [status body]. (`get-body` above takes a response, not a url.)"
  ([url] (get-status+body url {}))
  ([url opts]
   (let [response (-> (client/GET *system* url opts) (client/await-response 5000))]
     [(client/response-status response)
      (-> (client/response-body response *system*) (client/await-response 5000))])))

(deftest integer-status-codes-round-trip-test
  ;; B20: an integer status must behave exactly like its keyword twin. The old
  ;; ->status-code routed every integer through StatusCodes/custom(n,"","",false,
  ;; false), so (complete 201 body) rendered a 500 with no body.
  (testing "int status codes round-trip with their bodies (real server)"
    (let [routes (routing/routes
                   (routing/GET "/kw" [] (routing/complete :created "kw-body"))
                   (routing/GET "/int" [] (routing/complete 201 "int-body"))
                   (routing/GET "/unregistered" [] (routing/complete 289 "odd-body"))
                   (routing/not-found "nope"))]
      (with-test-server routes
        (fn []
          ;; the integer 201 behaves exactly like :created — status and body
          (is (= [201 "kw-body"] (get-status+body (url "/kw"))))
          (is (= [201 "int-body"] (get-status+body (url "/int"))))
          ;; a code outside the registry still round-trips, body and all
          (is (= [289 "odd-body"] (get-status+body (url "/unregistered")))))))))

(deftest client-json-helpers-parse-round-trip
  ;; H18: get-json / post-json now marshal — post Clojure data, get Clojure data
  ;; back (keywordized), instead of returning the raw body string.
  (let [routes (routing/routes
                 (routing/GET "/thing" [] (routing/complete-json {:id 1 :name "ada"}))
                 (routing/POST "/echo" []
                   (routing/with-json-body
                    (fn [data] (routing/complete-json {:got data}))))
                 (routing/not-found "nope"))]
    (with-test-server routes
      (fn []
        (is (= {:id 1 :name "ada"}
               (-> (client/get-json *system* (url "/thing")) (client/await-response 5000)))
            "get-json parses the response body into Clojure data")
        (is (= {:got {:k ["a" "b"]}}
               (-> (client/post-json *system* (url "/echo") {:k ["a" "b"]})
                   (client/await-response 5000)))
            "post-json encodes the request and parses the response")))))

(deftest from-resource-serves-a-classpath-file
  (let [routes (routing/routes
                 (routing/path "style.css" (routing/from-resource "public/css/app.css"))
                 (routing/not-found "nope"))]
    (with-test-server routes
      (fn []
        (let [[status body] (get-status+body (str "http://127.0.0.1:" *port* "/style.css"))]
          (is (= 200 status))
          (is (str/includes? body "rebeccapurple")))
        ;; content type comes from the extension, not from us
        (let [response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/style.css"))
                           (client/await-response 5000))]
          (is (str/includes? (str (.getContentType (.entity response))) "text/css")))))))

(deftest from-resource-directory-resolves-the-unmatched-path
  (let [routes (routing/routes
                 (routing/path-prefix "assets" (routing/from-resource-directory "public"))
                 (routing/not-found "nope"))]
    (with-test-server routes
      (fn []
        (let [[status body] (get-status+body (str "http://127.0.0.1:" *port* "/assets/css/app.css"))]
          (is (= 200 status) "nested path resolved inside the resource directory")
          (is (str/includes? body "rebeccapurple")))
        (let [[status _] (get-status+body (str "http://127.0.0.1:" *port* "/assets/missing.css"))]
          (is (= 404 status)))))))

(deftest from-directory-serves-filesystem-files
  (let [dir (java.io.File. "target/n15-static")
        _ (.mkdirs dir)
        _ (spit (java.io.File. dir "note.txt") "from the filesystem")
        routes (routing/routes
                 (routing/path-prefix "files" (routing/from-directory (.getPath dir)))
                 (routing/path "one" (routing/from-file (str (.getPath dir) "/note.txt")))
                 (routing/not-found "nope"))]
    (with-test-server routes
      (fn []
        (is (= [200 "from the filesystem"]
               (get-status+body (str "http://127.0.0.1:" *port* "/files/note.txt"))))
        (is (= [200 "from the filesystem"]
               (get-status+body (str "http://127.0.0.1:" *port* "/one"))))
        (is (= 404 (first (get-status+body (str "http://127.0.0.1:" *port* "/files/absent.txt")))))))))

;; ---------------------------------------------------------------------------
;; N15: authentication
;; ---------------------------------------------------------------------------

(def ^:private test-users {"ada" "lovelace" "alan" "turing"})

(defn- basic-header [user pass]
  {"Authorization"
   (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                                  (.getBytes (str user ":" pass) "UTF-8")))})

(deftest basic-auth-accepts-rejects-and-challenges
  (let [routes (routing/path "secret"
                 (routing/basic-auth
                  "test realm"
                  (fn [user verify]
                    (when-let [secret (get test-users user)]
                      (when (verify secret) {:user user})))
                  (fn [principal]
                    (routing/complete (str "welcome " (:user principal))))))]
    (with-test-server routes
      (fn []
        (is (= [200 "welcome ada"]
               (get-status+body (str "http://127.0.0.1:" *port* "/secret")
                                {:headers (basic-header "ada" "lovelace")})))
        (is (= 401 (first (get-status+body (str "http://127.0.0.1:" *port* "/secret")
                                           {:headers (basic-header "ada" "wrong")})))
            "wrong password")
        (is (= 401 (first (get-status+body (str "http://127.0.0.1:" *port* "/secret")
                                           {:headers (basic-header "nobody" "x")})))
            "unknown user")
        ;; no credentials at all -> 401 with the challenge naming the realm
        (let [response (-> (client/GET *system* (str "http://127.0.0.1:" *port* "/secret"))
                           (client/await-response 5000))]
          (is (= 401 (client/response-status response)))
          (is (str/includes? (str (client/response-headers response)) "test realm")
              "the realm reaches the WWW-Authenticate challenge"))))))

(deftest bearer-token-extracts-or-passes-nil
  (let [routes (routing/path "whoami"
                 (routing/bearer-token
                  (fn [token]
                    (routing/complete (str "token=" (pr-str token))))))]
    (with-test-server routes
      (fn []
        (is (= [200 "token=\"abc123\""]
               (get-status+body (str "http://127.0.0.1:" *port* "/whoami")
                                {:headers {"Authorization" "Bearer abc123"}})))
        (is (= [200 "token=\"abc123\""]
               (get-status+body (str "http://127.0.0.1:" *port* "/whoami")
                                {:headers {"Authorization" "bearer abc123"}}))
            "the scheme is matched case-insensitively")
        (is (= [200 "token=nil"]
               (get-status+body (str "http://127.0.0.1:" *port* "/whoami")))
            "absent header")
        (is (= [200 "token=nil"]
               (get-status+body (str "http://127.0.0.1:" *port* "/whoami")
                                {:headers (basic-header "ada" "lovelace")}))
            "another scheme is not a bearer token")))))

;; ---------------------------------------------------------------------------
;; N15: the JSON-string marshalling decision
;; ---------------------------------------------------------------------------

(deftest json-string-bodies-are-encoded-not-passed-through
  (let [routes (routing/routes
                 (routing/path "greeting" (routing/complete-json "hello"))
                 (routing/path "pre-encoded"
                   (routing/complete-json (marshal/raw-body "{\"a\":1}")))
                 (routing/not-found "nope"))]
    (with-test-server routes
      (fn []
        ;; The decision: a bare string is a JSON *value*, so it comes back quoted
        ;; and parses. It used to be emitted verbatim, which is invalid JSON.
        (let [[status body] (get-status+body (str "http://127.0.0.1:" *port* "/greeting"))]
          (is (= 200 status))
          (is (= "\"hello\"" body))
          (is (= "hello" (marshal/json-> body))))
        (let [[status body] (get-status+body (str "http://127.0.0.1:" *port* "/pre-encoded"))]
          (is (= 200 status))
          (is (= "{\"a\":1}" body) "raw-body is emitted verbatim"))))))

;; ---------------------------------------------------------------------------
;; N16: HTTPS
;; ---------------------------------------------------------------------------
;;
;; test/resources/certs holds a self-signed server keystore (server.p12, with an
;; ip:127.0.0.1 / dns:localhost SAN so hostname verification passes on loopback)
;; and a truststore (truststore.p12) holding just that cert for the client to trust.

(defn- server-https-context []
  (tls/https-server-context {:keystore (io/resource "certs/server.p12")
                             :keystore-password "changeit"}))

(defn- client-https-context []
  (tls/https-client-context {:truststore (io/resource "certs/truststore.p12")
                             :truststore-password "changeit"}))

(deftest https-round-trip-test
  (testing "a self-signed https server answers a client that trusts its cert"
    (let [routes (routing/path "secure"
                   (routing/method-get (routing/path-end (routing/complete "over TLS"))))
          binding (-> (http/bind-server *system* "127.0.0.1" *port* routes
                                        {:https (server-https-context)})
                      (stream/await-completion 5000))]
      (try
        (let [response (-> (client/GET *system* (str "https://127.0.0.1:" *port* "/secure")
                             {:https-context (client-https-context)})
                           (client/await-response 5000))]
          (is (= 200 (client/response-status response)))
          (is (= "over TLS" (-> (client/response-body response *system*)
                                (client/await-response 5000)))))
        (finally
          (stream/await-completion (http/unbind binding) 5000))))))

;; ---------------------------------------------------------------------------
;; N16: compression
;; ---------------------------------------------------------------------------

(defn- gzip ^bytes [^String s]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. baos)]
      (.write gz (.getBytes s "UTF-8")))
    (.toByteArray baos)))

(defn- gunzip [^bytes b]
  (with-open [gz (GZIPInputStream. (ByteArrayInputStream. b))]
    (String. (.readAllBytes gz) "UTF-8")))

(deftest gzip-encode-response-test
  (testing "encode-response gzips when the client asks for it, and the body decodes"
    (let [payload (apply str (repeat 50 "pekko-clj compresses this response. "))
          routes (routing/encode-response
                  (routing/path "data"
                    (routing/method-get (routing/path-end (routing/complete payload)))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (url "/data")
                               {:headers {"Accept-Encoding" "gzip"}})
                             (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (= "gzip" (client/response-header response "Content-Encoding"))
                "the response advertises gzip")
            ;; the pekko client does not auto-decode, so the raw body is gzip bytes
            (let [raw (-> (client/response-body-bytes response *system*)
                          (client/await-response 5000))]
              (is (= payload (gunzip raw)) "the gzipped body inflates to the original"))))))))

(deftest gzip-encode-response-skipped-without-accept-encoding-test
  (testing "with no Accept-Encoding, encode-response leaves the body identity-coded"
    (let [routes (routing/encode-response
                  (routing/path "data"
                    (routing/method-get (routing/path-end (routing/complete "plain")))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (url "/data")) (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (not= "gzip" (client/response-header response "Content-Encoding")))
            (is (= "plain" (get-body response)))))))))

(deftest gzip-decode-request-test
  (testing "decode-request inflates a gzipped request body before the route reads it"
    (let [routes (routing/path "ingest"
                   (routing/method-post
                     (routing/path-end
                       (routing/decode-request
                        (routing/with-request-body
                          (fn [body] (routing/complete (str "got:" body))))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/POST *system* (url "/ingest")
                               {:body (gzip "hello gzip")
                                :content-type :plain
                                :headers {"Content-Encoding" "gzip"}})
                             (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (= "got:hello gzip" (get-body response))
                "the server saw the decompressed text")))))))

;; ---------------------------------------------------------------------------
;; N16: request timeouts
;; ---------------------------------------------------------------------------

(deftest request-timeout-returns-503-test
  (testing "a route that overruns with-request-timeout completes 503; a fast one is fine"
    (let [slow-response (CompletableFuture/supplyAsync
                         (reify Supplier
                           (get [_] (Thread/sleep 3000) (resp/ok "late"))))
          routes (routing/with-request-timeout 500
                   (routing/routes
                     (routing/path "fast"
                       (routing/method-get (routing/path-end (routing/complete "quick"))))
                     (routing/path "slow"
                       (routing/method-get
                         (routing/path-end (routing/complete-future slow-response))))))]
      (with-test-server routes
        (fn []
          (let [fast (-> (client/GET *system* (url "/fast")) (client/await-response 5000))]
            (is (= 200 (client/response-status fast)))
            (is (= "quick" (get-body fast))))
          (let [slow (-> (client/GET *system* (url "/slow")) (client/await-response 5000))]
            (is (= 503 (client/response-status slow))
                "the request timed out with Service Unavailable")))))))

;; ---------------------------------------------------------------------------
;; N16: strict-entity timeout is configurable
;; ---------------------------------------------------------------------------

(deftest strict-entity-timeout-option-test
  (testing "entity->string and response-body accept an explicit strict timeout"
    ;; The materializer comes from the route context (extract-materializer): the
    ;; handler runs on a Pekko dispatcher thread where the test's *system* binding
    ;; is no longer in scope.
    (let [routes (routing/path "echo"
                   (routing/method-post
                     (routing/path-end
                       (routing/extract-request
                        (fn [req]
                          (routing/extract-materializer
                           (fn [mat]
                             (routing/complete-future
                              (-> (http/entity->string req mat 3000)
                                  (client/then-apply resp/ok))))))))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/POST *system* (url "/echo")
                               {:body "timed body" :content-type :plain})
                             (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (= "timed body"
                   (-> (client/response-body response *system* 3000)
                       (client/await-response 5000))))))))))

;; ---------------------------------------------------------------------------
;; N24: server-sent events, async routes, client IP
;; ---------------------------------------------------------------------------

(deftest sse-stream-test
  (testing "sse serves a text/event-stream a framing client can read back"
    (let [routes (routing/path "events"
                   (routing/method-get
                     (routing/sse (stream/source [{:data "one" :event "greeting"}
                                                  {:data "two"}
                                                  {:data "three" :id "42"}]))))]
      (with-test-server routes
        (fn []
          (let [response (-> (client/GET *system* (url "/events")) (client/await-response 5000))]
            (is (= 200 (client/response-status response)))
            (is (str/includes? (str (.getContentType (.entity ^HttpResponse response)))
                               "text/event-stream")
                "the response advertises the SSE content type")
            ;; Re-frame the chunked byte stream into lines (N14) — the point of a
            ;; streaming client: chunk boundaries do not line up with events.
            (let [lines (-> (.getDataBytes (.entity ^HttpResponse response))
                            (stream/via (stream/lines))
                            (stream/run-to-seq *mat*)
                            (stream/await-completion 5000)
                            vec)
                  text (str/join "\n" lines)]
              (is (some #(str/starts-with? % "data") lines) "events are `data:` framed")
              (is (str/includes? text "one"))
              (is (str/includes? text "two"))
              (is (str/includes? text "three"))
              (is (str/includes? text "greeting") "the :event type is rendered")
              (is (str/includes? text "42") "the :id is rendered"))))))))

(core/defactor n24-greeter
  (init [_] {})
  (handle [:greet who] (core/reply (str "hello " who))))

(deftest on-success-drives-an-actor-ask
  (testing "on-success builds the Route from an actor <?> reply without blocking"
    (let [actor (core/spawn *system* n24-greeter nil)
          routes (routing/GET "/greet/:who" [who]
                   (routing/on-success (core/<?> actor [:greet who] 3000)
                                       (fn [reply] (routing/complete reply))))]
      (with-test-server routes
        (fn []
          (is (= [200 "hello ada"] (get-status+body (url "/greet/ada")))))))))

(deftest on-complete-handles-success-and-failure
  (testing "on-complete turns a failed stage into a chosen response, not a bare 500"
    (let [actor (core/spawn *system* n24-greeter nil)
          routes (routing/routes
                   (routing/GET "/ok/:who" [who]
                     (routing/on-complete (core/<?> actor [:greet who] 3000)
                                          (fn [{:keys [success value]}]
                                            (if success
                                              (routing/complete (str "ok:" value))
                                              (routing/complete :internal-server-error "unreachable")))))
                   (routing/GET "/fail" []
                     (routing/on-complete (doto (CompletableFuture.)
                                            (.completeExceptionally (RuntimeException. "kaboom")))
                                          (fn [{:keys [success error]}]
                                            (if success
                                              (routing/complete "unexpected success")
                                              (routing/complete :internal-server-error
                                                                (str "failed: " (.getMessage ^Throwable error))))))))]
      (with-test-server routes
        (fn []
          (is (= [200 "ok:hello ada"] (get-status+body (url "/ok/ada"))))
          (is (= [500 "failed: kaboom"] (get-status+body (url "/fail")))))))))

(deftest extract-client-ip-test
  (testing "extract-client-ip yields the peer IP only when remote-address-attribute is on"
    (let [make-routes (fn [] (routing/path "whoami"
                               (routing/method-get
                                 (routing/extract-client-ip
                                  (fn [ip] (routing/complete (str "ip=" (pr-str ip))))))))
          run (fn [sys]
                (let [port (find-free-port)
                      binding (-> (http/bind-server sys "127.0.0.1" port (make-routes))
                                  (stream/await-completion 5000))]
                  (try
                    (-> (client/GET sys (str "http://127.0.0.1:" port "/whoami"))
                        (client/await-response 5000)
                        (client/response-body sys)
                        (client/await-response 5000))
                    (finally (stream/await-completion (http/unbind binding) 5000)))))]
      ;; With the attribute enabled, the loopback peer address is captured.
      (let [sys (core/actor-system "client-ip-on"
                                   (ConfigFactory/parseString
                                    "pekko.http.server.remote-address-attribute = on"))]
        (try
          (is (= "ip=\"127.0.0.1\"" (run sys)))
          (finally (core/shutdown-system sys))))
      ;; Without it (the default), the IP is unknown -> nil.
      (let [sys (core/actor-system "client-ip-off")]
        (try
          (is (= "ip=nil" (run sys)) "no address captured without the config")
          (finally (core/shutdown-system sys)))))))
