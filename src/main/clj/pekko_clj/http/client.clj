(ns pekko-clj.http.client
  "HTTP client for Pekko HTTP.

   Provides simple HTTP request functions with async response handling."
  (:require [pekko-clj.http.response :as resp]
            [pekko-clj.http.core :as http]
            [pekko-clj.http.marshalling :as marshal])
  (:import [org.apache.pekko.http.javadsl Http HttpsConnectionContext]
           [org.apache.pekko.http.javadsl.model HttpRequest HttpResponse HttpMethods
            HttpHeader ResponseEntity ContentType$NonBinary]
           [org.apache.pekko.http.scaladsl.model HttpEntity$Strict]
           [org.apache.pekko.actor ActorSystem]
           [java.util Optional]
           [org.apache.pekko.http.javadsl.model.headers RawHeader]
           [java.util.concurrent CompletionStage]
           [java.util.function Function BiConsumer]))

;; ---------------------------------------------------------------------------
;; Request Building
;; ---------------------------------------------------------------------------

(def ^:private response-strict-timeout-ms
  "Default time response-body / response-body-bytes wait for the body to be
   collected. Override per call with the trailing timeout-ms argument."
  30000)

(defn- build-request
  "Build an HttpRequest from method, url, and options.

   opts:
   - :headers - map of header names to values
   - :body - request body (string, bytes, or entity)
   - :content-type - content type keyword or ContentType"
  [method url opts]
  ;; Built step by step rather than threaded through cond->: each step needs a
  ;; known HttpRequest type for the interop call to resolve without reflection,
  ;; and the String/byte[] entity overloads have to be picked in separate branches.
  (let [{:keys [headers body content-type]} opts
        ^HttpRequest req (.withMethod (HttpRequest/create ^String url) method)
        ^HttpRequest req (if headers
                           (reduce (fn [^HttpRequest r [header-name value]]
                                     (.addHeader r (RawHeader/create ^String header-name
                                                                     ^String value)))
                                   req
                                   headers)
                           req)]
    (cond
      (and body content-type)
      ;; withEntity's String overload is declared on ContentType$NonBinary.
      (let [ct (resp/->content-type content-type)]
        (if (string? body)
          (.withEntity req ^ContentType$NonBinary ct ^String body)
          (.withEntity req ct ^bytes body)))

      body (.withEntity req ^String (str body))
      :else req)))

;; ---------------------------------------------------------------------------
;; Request Functions
;; ---------------------------------------------------------------------------

(defn set-default-client-https-context!
  "Install `ctx` (an HttpsConnectionContext from `pekko-clj.http.tls/
   https-client-context`) as the default context for outgoing https requests on
   this system, so requests need not pass :https-context each time."
  [system ^HttpsConnectionContext ctx]
  (.setDefaultClientHttpsContext (Http/get ^ActorSystem system) ctx))

(defn request
  "Make an HTTP request.

   method: :get, :post, :put, :delete, :head, :options, :patch
   url: request URL string
   opts: optional map with :headers, :body, :content-type, :https-context

   :https-context is an HttpsConnectionContext used for this request only (see
   `pekko-clj.http.tls/https-client-context`); without it, https requests use the
   system default context.

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
         http (Http/get ^ActorSystem system)]
     (if-let [ctx (:https-context opts)]
       (.singleRequest http req ^HttpsConnectionContext ctx)
       (.singleRequest http req)))))

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
    ;; (int code): the case tests are primitive, so match a primitive expression —
    ;; avoids the "case has int tests, but tested expression is not primitive" note.
    (case (int code)
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
  (let [^Optional optional (.getHeader response ^String header-name)]
    (when (.isPresent optional)
      (.value ^HttpHeader (.get optional)))))

(defn response-headers
  "Get all headers as a map keyed by lowercase name.
   When a header name appears more than once, the map keeps the LAST value
   (`into {}` overwrites earlier entries); use `response-header` for a single
   lookup. See `pekko-clj.http.core/response-headers` for the server-side twin."
  [^HttpResponse response]
  (into {}
        (for [^HttpHeader header (iterator-seq (.iterator (.getHeaders response)))]
          [(.lowercaseName header) (.value header)])))

(defn response-body
  "Get the response body as a string.
   Returns a CompletionStage<String>.

   materializer-or-system: Materializer or ActorSystem
   timeout-ms: how long to wait for the body (default 30000)."
  ([^HttpResponse response materializer-or-system]
   (response-body response materializer-or-system response-strict-timeout-ms))
  ([^HttpResponse response materializer-or-system timeout-ms]
   (let [mat (http/->materializer materializer-or-system)]
     (-> (.toStrict ^ResponseEntity (.entity response) (long timeout-ms) mat)
         (.thenApply (reify Function
                       (apply [_ strict]
                         (.utf8String (.getData ^HttpEntity$Strict strict)))))))))

(defn response-body-bytes
  "Get the response body as a byte array.
   Returns a CompletionStage<byte[]>.

   materializer-or-system: Materializer or ActorSystem
   timeout-ms: how long to wait for the body (default 30000)."
  ([^HttpResponse response materializer-or-system]
   (response-body-bytes response materializer-or-system response-strict-timeout-ms))
  ([^HttpResponse response materializer-or-system timeout-ms]
   (let [mat (http/->materializer materializer-or-system)]
     (-> (.toStrict ^ResponseEntity (.entity response) (long timeout-ms) mat)
         (.thenApply (reify Function
                       (apply [_ strict]
                         (.toArray (.getData ^HttpEntity$Strict strict)))))))))

(defn discard-body
  "Discard the response body.
   Important for connection reuse - always call this if you don't need the body.
   Returns a CompletionStage<Done>."
  [^HttpResponse response materializer-or-system]
  (let [mat (http/->materializer materializer-or-system)]
    (.discardBytes ^ResponseEntity (.entity response) mat)))

;; ---------------------------------------------------------------------------
;; Async Utilities
;; ---------------------------------------------------------------------------

;; H18: these were byte-for-byte duplicates of pekko-clj.http.core's; re-export
;; them so there is one implementation. Kept here so `client/then` /
;; `client/then-apply` stay a stable part of the client API.
(def ^{:arglists '([stage f])
       :doc "Chain a function after a CompletionStage completes (thenCompose): `f`
   receives the result and should return a CompletionStage. See
   `pekko-clj.http.core/then`."}
  then http/then)

(def ^{:arglists '([stage f])
       :doc "Transform a CompletionStage's result synchronously (thenApply): `f`
   receives the result and returns a new value. See
   `pekko-clj.http.core/then-apply`."}
  then-apply http/then-apply)

(defn on-complete
  "Add a callback for when a CompletionStage completes.
   f is called with (result exception) - one will be nil."
  [^CompletionStage stage f]
  (.whenComplete stage
                 (reify BiConsumer
                   (accept [_ result ex]
                     (f result ex)))))

(defn await-response
  "Block for a CompletionStage's value (up to timeout-ms, default 30000). Rethrows
   the unwrapped failure if the request completed exceptionally; returns nil on the
   block timeout — the same nil-vs-throw convention as pekko-clj.core/<!."
  ([stage] (await-response stage 30000))
  ([^CompletionStage stage timeout-ms]
   (try
     (.get (.toCompletableFuture stage) (long timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
     (catch java.util.concurrent.ExecutionException e
       (throw (or (.getCause e) e)))
     (catch java.util.concurrent.TimeoutException _ nil))))

;; ---------------------------------------------------------------------------
;; Convenience Functions
;; ---------------------------------------------------------------------------

(defn get-json
  "GET `url` with an application/json Accept header and parse the response body as
   JSON into Clojure data (keywordized keys, via `pekko-clj.http.marshalling`).
   Returns a CompletionStage of the parsed data.

   (H18: previously returned the raw body string despite the name; it now parses,
   matching `post-json` and the marshalling layer.)"
  [system url]
  (-> (GET system url {:headers {"Accept" "application/json"}})
      (then (fn [resp] (response-body resp system)))
      (then-apply marshal/json->)))

(defn post-json
  "POST Clojure `data` as a JSON body to `url` and parse the JSON response into
   Clojure data (keywordized keys). Returns a CompletionStage of the parsed data.

   (H18: `data` is now Clojure data, encoded here — previously this took a
   pre-encoded body string and returned the raw response string.)"
  [system url data]
  (-> (POST system url {:body (marshal/->json data)
                        :content-type :json
                        :headers {"Accept" "application/json"}})
      (then (fn [resp] (response-body resp system)))
      (then-apply marshal/json->)))

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
