(ns pekko-clj.http.client
  "HTTP client for Pekko HTTP.

   Provides simple HTTP request functions with async response handling."
  (:require [pekko-clj.http.response :as resp])
  (:import [org.apache.pekko.http.javadsl Http]
           [org.apache.pekko.http.javadsl.model HttpRequest HttpResponse HttpMethods
                                                  ContentTypes]
           [org.apache.pekko.http.javadsl.model.headers RawHeader]
           [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.stream Materializer]
           [java.util.concurrent CompletionStage CompletableFuture TimeUnit]
           [java.util.function Function BiConsumer]
           [scala.concurrent.duration Duration]
           [scala.jdk.javaapi FutureConverters]))

;; ---------------------------------------------------------------------------
;; Request Building
;; ---------------------------------------------------------------------------

(defn- build-request
  "Build an HttpRequest from method, url, and options.

   opts:
   - :headers - map of header names to values
   - :body - request body (string, bytes, or entity)
   - :content-type - content type keyword or ContentType"
  [method url opts]
  (let [req (-> (HttpRequest/create url)
                (.withMethod method))]
    (cond-> req
      ;; Add headers
      (:headers opts)
      ((fn [r]
         (reduce (fn [req [name value]]
                   (.addHeader req (RawHeader/create name value)))
                 r
                 (:headers opts))))

      ;; Add body with content type
      (and (:body opts) (:content-type opts))
      (.withEntity (resp/->content-type (:content-type opts))
                   (if (string? (:body opts))
                     ^String (:body opts)
                     ^bytes (:body opts)))

      ;; Add body without explicit content type
      (and (:body opts) (not (:content-type opts)))
      (.withEntity ^String (str (:body opts))))))

;; ---------------------------------------------------------------------------
;; Request Functions
;; ---------------------------------------------------------------------------

(defn request
  "Make an HTTP request.

   method: :get, :post, :put, :delete, :head, :options, :patch
   url: request URL string
   opts: optional map with :headers, :body, :content-type

   Returns CompletionStage<HttpResponse>."
  ([system method url]
   (request system method url {}))
  ([system method url opts]
   (let [http-method (case method
                       :get     HttpMethods/GET
                       :post    HttpMethods/POST
                       :put     HttpMethods/PUT
                       :delete  HttpMethods/DELETE
                       :head    HttpMethods/HEAD
                       :options HttpMethods/OPTIONS
                       :patch   HttpMethods/PATCH
                       :trace   HttpMethods/TRACE
                       :connect HttpMethods/CONNECT)
         req (build-request http-method url opts)
         http (Http/get system)]
     (.singleRequest http req))))

(defn GET
  "Make a GET request.

   (GET system \"http://example.com/api/users\")
   (GET system \"http://example.com/api/users\" {:headers {\"Accept\" \"application/json\"}})"
  ([system url]
   (GET system url {}))
  ([system url opts]
   (request system :get url opts)))

(defn POST
  "Make a POST request.

   (POST system \"http://example.com/api/users\"
         {:body \"{\\\"name\\\": \\\"John\\\"}\"
          :content-type :json})"
  ([system url]
   (POST system url {}))
  ([system url opts]
   (request system :post url opts)))

(defn PUT
  "Make a PUT request.

   (PUT system \"http://example.com/api/users/1\"
        {:body \"{\\\"name\\\": \\\"Jane\\\"}\"
         :content-type :json})"
  ([system url]
   (PUT system url {}))
  ([system url opts]
   (request system :put url opts)))

(defn DELETE
  "Make a DELETE request.

   (DELETE system \"http://example.com/api/users/1\")"
  ([system url]
   (DELETE system url {}))
  ([system url opts]
   (request system :delete url opts)))

(defn HEAD
  "Make a HEAD request."
  ([system url]
   (HEAD system url {}))
  ([system url opts]
   (request system :head url opts)))

(defn OPTIONS
  "Make an OPTIONS request."
  ([system url]
   (OPTIONS system url {}))
  ([system url opts]
   (request system :options url opts)))

(defn PATCH
  "Make a PATCH request."
  ([system url]
   (PATCH system url {}))
  ([system url opts]
   (request system :patch url opts)))

;; ---------------------------------------------------------------------------
;; Response Handling
;; ---------------------------------------------------------------------------

(defn response-status
  "Get the status code of a response as an integer."
  [^HttpResponse response]
  (.intValue (.status response)))

(defn response-status-keyword
  "Get the status code of a response as a keyword.
   Returns :ok, :not-found, :internal-server-error, etc."
  [^HttpResponse response]
  (let [code (response-status response)]
    (case code
      200 :ok
      201 :created
      202 :accepted
      204 :no-content
      301 :moved-permanently
      302 :found
      303 :see-other
      304 :not-modified
      307 :temporary-redirect
      308 :permanent-redirect
      400 :bad-request
      401 :unauthorized
      403 :forbidden
      404 :not-found
      405 :method-not-allowed
      409 :conflict
      410 :gone
      422 :unprocessable-entity
      429 :too-many-requests
      500 :internal-server-error
      501 :not-implemented
      502 :bad-gateway
      503 :service-unavailable
      504 :gateway-timeout
      (keyword (str "status-" code)))))

(defn response-header
  "Get a single header value by name (case-insensitive).
   Returns nil if header not present."
  [^HttpResponse response header-name]
  (let [optional (.getHeader response header-name)]
    (when (.isPresent optional)
      (.value (.get optional)))))

(defn response-headers
  "Get all headers as a map.
   Multi-valued headers return the first value."
  [^HttpResponse response]
  (into {}
        (for [header (iterator-seq (.iterator (.getHeaders response)))]
          [(.lowercaseName header) (.value header)])))

(defn response-body
  "Get the response body as a string.
   Returns a CompletionStage<String>.

   materializer-or-system: Materializer or ActorSystem"
  [^HttpResponse response materializer-or-system]
  (let [mat (if (instance? Materializer materializer-or-system)
              materializer-or-system
              (Materializer/createMaterializer materializer-or-system))]
    (-> (.entity response)
        (.toStrict (Duration/create 30 TimeUnit/SECONDS) mat)
        (FutureConverters/asJava)
        (.thenApply (reify Function
                      (apply [_ strict]
                        (.utf8String (.getData strict))))))))

(defn response-body-bytes
  "Get the response body as a byte array.
   Returns a CompletionStage<byte[]>.

   materializer-or-system: Materializer or ActorSystem"
  [^HttpResponse response materializer-or-system]
  (let [mat (if (instance? Materializer materializer-or-system)
              materializer-or-system
              (Materializer/createMaterializer materializer-or-system))]
    (-> (.entity response)
        (.toStrict (Duration/create 30 TimeUnit/SECONDS) mat)
        (FutureConverters/asJava)
        (.thenApply (reify Function
                      (apply [_ strict]
                        (.toArray (.getData strict))))))))

(defn discard-body
  "Discard the response body.
   Important for connection reuse - always call this if you don't need the body.
   Returns a CompletionStage<Done>."
  [^HttpResponse response materializer-or-system]
  (let [mat (if (instance? Materializer materializer-or-system)
              materializer-or-system
              (Materializer/createMaterializer materializer-or-system))]
    (.discardBytes (.entity response) mat)))

;; ---------------------------------------------------------------------------
;; Async Utilities
;; ---------------------------------------------------------------------------

(defn then
  "Chain a function after a CompletionStage completes.
   f receives the result and should return a CompletionStage."
  [^CompletionStage stage f]
  (.thenCompose stage
                (reify Function
                  (apply [_ result]
                    (f result)))))

(defn then-apply
  "Transform the result of a CompletionStage synchronously.
   f receives the result and returns a new value."
  [^CompletionStage stage f]
  (.thenApply stage
              (reify Function
                (apply [_ result]
                  (f result)))))

(defn on-complete
  "Add a callback for when a CompletionStage completes.
   f is called with (result exception) - one will be nil."
  [^CompletionStage stage f]
  (.whenComplete stage
                 (reify BiConsumer
                   (accept [_ result ex]
                     (f result ex)))))

(defn await-response
  "Block until a CompletionStage completes, returning its value.
   timeout-ms: maximum time to wait in milliseconds"
  [^CompletionStage stage timeout-ms]
  (try
    (.get (.toCompletableFuture stage) timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    (catch java.util.concurrent.TimeoutException _
      (throw (ex-info "Request timed out" {:timeout-ms timeout-ms})))))

;; ---------------------------------------------------------------------------
;; Convenience Functions
;; ---------------------------------------------------------------------------

(defn get-json
  "Make a GET request and parse the response as JSON (returns string).
   Returns CompletionStage<String>.

   For actual JSON parsing, use a library like cheshire with the result."
  [system url]
  (-> (GET system url {:headers {"Accept" "application/json"}})
      (then (fn [resp]
              (response-body resp system)))))

(defn post-json
  "Make a POST request with JSON body.
   Returns CompletionStage<String> of response body."
  [system url body-string]
  (-> (POST system url {:body body-string
                        :content-type :json
                        :headers {"Accept" "application/json"}})
      (then (fn [resp]
              (response-body resp system)))))

(defn successful?
  "Check if a response status indicates success (2xx)."
  [^HttpResponse response]
  (let [code (response-status response)]
    (and (>= code 200) (< code 300))))

(defn client-error?
  "Check if a response status indicates client error (4xx)."
  [^HttpResponse response]
  (let [code (response-status response)]
    (and (>= code 400) (< code 500))))

(defn server-error?
  "Check if a response status indicates server error (5xx)."
  [^HttpResponse response]
  (let [code (response-status response)]
    (>= code 500)))
