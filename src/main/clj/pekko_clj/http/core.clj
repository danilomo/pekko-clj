(ns pekko-clj.http.core
  "Core HTTP server functionality for Pekko HTTP.

   Provides server binding, request accessors, and entity handling."
  (:require [clojure.string :as str])
  (:import [org.apache.pekko.http.javadsl Http ServerBinding ServerBuilder HttpsConnectionContext]
           [org.apache.pekko.http.javadsl.model HttpRequest HttpMethods HttpHeader RequestEntity]
           [org.apache.pekko.http.scaladsl.model HttpEntity$Strict]
           [org.apache.pekko.japi Pair]
           [java.util Optional]
           [org.apache.pekko.http.javadsl.server Route]
           [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.stream Materializer SystemMaterializer]
           [java.util.concurrent CompletionStage CompletableFuture]
           [java.util.function Function]))

;; ---------------------------------------------------------------------------
;; Server Lifecycle
;; ---------------------------------------------------------------------------

(defn- bind-route
  "Bind a Route or a plain request->CompletionStage<HttpResponse> function on an
   already-configured ServerBuilder."
  [^ServerBuilder builder route]
  (if (instance? Route route)
    (.bind builder ^Route route)
    ;; route is a function: HttpRequest -> CompletionStage<HttpResponse>.
    ;; ServerBuilder.bind takes Pekko's japi Function, NOT java.util.function
    ;; .Function — reifying the latter throws "No matching method bind".
    (.bind builder
           (reify org.apache.pekko.japi.function.Function
             (apply [_ request]
               (route request))))))

(defn bind-server
  "Bind an HTTP server to a host and port with the given route handler.

   system: ActorSystem
   host: hostname or IP to bind to (e.g., \"localhost\", \"0.0.0.0\")
   port: port number
   route: a Route or function (request -> CompletionStage<HttpResponse>)

   The optional last argument is either a Materializer (kept for back-compat) or
   an options map:
   - :materializer - a stream Materializer
   - :https        - an HttpsConnectionContext (see `pekko-clj.http.tls/
                     https-server-context`); when present the server speaks TLS

   Returns a CompletionStage<ServerBinding>."
  ([system host port route]
   (bind-server system host port route {}))
  ([system host port route mat-or-opts]
   (let [opts (if (instance? Materializer mat-or-opts)
                {:materializer mat-or-opts}
                mat-or-opts)
         {:keys [materializer https]} opts
         http (Http/get ^ActorSystem system)
         ^ServerBuilder builder (.newServerAt http ^String host (int port))
         ^ServerBuilder builder (if materializer (.withMaterializer builder materializer) builder)
         ^ServerBuilder builder (if https
                                  (.enableHttps builder ^HttpsConnectionContext https)
                                  builder)]
     (bind-route builder route))))

(defn unbind
  "Unbind a server, stopping it from accepting new connections.
   Returns a CompletionStage<Done>."
  [^ServerBinding binding]
  (.unbind binding))

(defn local-address
  "Get the local address the server is bound to.
   Returns an InetSocketAddress."
  [^ServerBinding binding]
  (.localAddress binding))

(defn terminate-hard
  "Terminate the server immediately, dropping all connections.
   deadline: java.time.Duration
   Returns a CompletionStage<HttpTerminated>."
  [^ServerBinding binding deadline]
  (.terminate binding deadline))

;; ---------------------------------------------------------------------------
;; Request Accessors
;; ---------------------------------------------------------------------------

(defn request-method
  "Get the HTTP method of a request as a keyword.
   Returns :get, :post, :put, :delete, :head, :options, :patch, :trace, :connect"
  [^HttpRequest request]
  (let [method (.method request)]
    (condp = method
      HttpMethods/GET     :get
      HttpMethods/POST    :post
      HttpMethods/PUT     :put
      HttpMethods/DELETE  :delete
      HttpMethods/HEAD    :head
      HttpMethods/OPTIONS :options
      HttpMethods/PATCH   :patch
      HttpMethods/TRACE   :trace
      HttpMethods/CONNECT :connect
      (keyword (.toLowerCase (.name method))))))

(defn request-uri
  "Get the URI of a request."
  [^HttpRequest request]
  (.getUri request))

(defn request-path
  "Get the path portion of the request URI.
   Returns a string like \"/users/123\"."
  [^HttpRequest request]
  (let [uri (.getUri request)]
    (.path uri)))

(defn request-query-string
  "Get the raw query string of the request.
   Returns nil if no query string."
  [^HttpRequest request]
  (let [uri (.getUri request)
        ^Optional raw (.rawQueryString uri)]
    (when (.isPresent raw)
      (.get raw))))

(defn request-query-params
  "Get query parameters as a map of strings.
   Multi-valued params return the last value (later entries overwrite
   earlier ones with the same name)."
  [^HttpRequest request]
  (let [uri (.getUri request)
        query (.query uri)]
    (into {}
          (for [^Pair param (iterator-seq (.iterator (.toList query)))]
            [(.first param) (.second param)]))))

(defn request-header
  "Get a single header value by name (case-insensitive).
   Returns nil if header not present."
  [^HttpRequest request header-name]
  (let [^Optional optional (.getHeader request ^String header-name)]
    (when (.isPresent optional)
      (.value ^HttpHeader (.get optional)))))

(defn request-headers
  "Get all headers as a map.
   Multi-valued headers return the last value (later entries overwrite
   earlier ones with the same name)."
  [^HttpRequest request]
  (into {}
        (for [^HttpHeader header (iterator-seq (.iterator (.getHeaders request)))]
          [(.lowercaseName header) (.value header)])))

(defn request-content-type
  "Get the Content-Type header value, or nil if not present."
  [^HttpRequest request]
  (let [ct (.entity request)]
    (when-let [content-type (.getContentType ct)]
      (.toString content-type))))

;; ---------------------------------------------------------------------------
;; Entity Handling
;; ---------------------------------------------------------------------------

(def ^:private entity-strict-timeout-ms
  "Default time entity->string / entity->bytes wait for the body to be fully
   collected. Override per call with the trailing timeout-ms argument."
  10000)

(defn ->materializer
  "Resolve a Materializer from a Materializer or an ActorSystem.

   When given a system, returns the shared per-system materializer via
   SystemMaterializer instead of creating a fresh one each call — the latter
   leaks an unclosed materializer (and its actor) on every invocation."
  ^Materializer [materializer-or-system]
  (if (instance? Materializer materializer-or-system)
    materializer-or-system
    (.materializer (SystemMaterializer/get ^ActorSystem materializer-or-system))))

(defn entity->string
  "Convert a request entity to a string.
   Returns a CompletionStage<String>.

   materializer-or-system: Materializer or ActorSystem
   timeout-ms: how long to wait for the body (default 10000)."
  ([^HttpRequest request materializer-or-system]
   (entity->string request materializer-or-system entity-strict-timeout-ms))
  ([^HttpRequest request materializer-or-system timeout-ms]
   (let [mat (->materializer materializer-or-system)]
     (-> (.toStrict ^RequestEntity (.entity request) (long timeout-ms) mat)
         (.thenApply (reify Function
                       (apply [_ strict]
                         (.utf8String (.getData ^HttpEntity$Strict strict)))))))))

(defn entity->bytes
  "Convert a request entity to a byte array.
   Returns a CompletionStage<byte[]>.

   materializer-or-system: Materializer or ActorSystem
   timeout-ms: how long to wait for the body (default 10000)."
  ([^HttpRequest request materializer-or-system]
   (entity->bytes request materializer-or-system entity-strict-timeout-ms))
  ([^HttpRequest request materializer-or-system timeout-ms]
   (let [mat (->materializer materializer-or-system)]
     (-> (.toStrict ^RequestEntity (.entity request) (long timeout-ms) mat)
         (.thenApply (reify Function
                       (apply [_ strict]
                         (.toArray (.getData ^HttpEntity$Strict strict)))))))))

(defn entity->data-bytes
  "Get the entity data as a Source of ByteString.
   Useful for streaming large bodies."
  [^HttpRequest request]
  (.getDataBytes (.entity request)))

;; ---------------------------------------------------------------------------
;; Path Helpers
;; ---------------------------------------------------------------------------

(defn path-segments
  "Split the request path into segments.
   \"/api/v1/users/123\" -> [\"api\" \"v1\" \"users\" \"123\"]"
  [^HttpRequest request]
  (let [path (request-path request)]
    (vec (remove empty? (str/split path #"/")))))

(defn match-path-pattern
  "Match a path against a pattern with :param placeholders.
   Returns a map of extracted parameters or nil if no match.

   Example:
   (match-path-pattern \"/users/123\" \"/users/:id\")
   => {:id \"123\"}"
  [path pattern]
  (let [path-parts (remove empty? (str/split path #"/"))
        pattern-parts (remove empty? (str/split pattern #"/"))]
    (when (= (count path-parts) (count pattern-parts))
      (loop [remaining-path path-parts
             remaining-pattern pattern-parts
             params {}]
        (if (empty? remaining-path)
          params
          (let [path-part (first remaining-path)
                ^String pattern-part (first remaining-pattern)]
            (cond
              ;; Parameter placeholder
              (.startsWith pattern-part ":")
              (recur (rest remaining-path)
                     (rest remaining-pattern)
                     (assoc params (keyword (subs pattern-part 1)) path-part))
              ;; Exact match
              (= path-part pattern-part)
              (recur (rest remaining-path)
                     (rest remaining-pattern)
                     params)
              ;; No match
              :else nil)))))))

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

(defn completed
  "Create an already-completed CompletionStage with the given value."
  [value]
  (CompletableFuture/completedFuture value))
