(ns pekko-clj.http.core
  "Core HTTP server functionality for Pekko HTTP.

   Provides server binding, request accessors, and entity handling."
  (:require [pekko-clj.stream :as stream])
  (:import [org.apache.pekko.http.javadsl Http ServerBinding]
           [org.apache.pekko.http.javadsl.model HttpRequest HttpResponse
                                                  HttpMethod HttpMethods Uri Query]
           [org.apache.pekko.http.javadsl.server Route]
           [org.apache.pekko.actor ActorSystem]
           [org.apache.pekko.stream Materializer]
           [org.apache.pekko.util ByteString]
           [java.util.concurrent CompletionStage CompletableFuture TimeUnit]
           [java.util.function Function]
           [scala.concurrent.duration Duration]
           [scala.jdk.javaapi FutureConverters]))

;; ---------------------------------------------------------------------------
;; Server Lifecycle
;; ---------------------------------------------------------------------------

(defn bind-server
  "Bind an HTTP server to a host and port with the given route handler.

   system: ActorSystem
   host: hostname or IP to bind to (e.g., \"localhost\", \"0.0.0.0\")
   port: port number
   route: a Route or function (request -> CompletionStage<HttpResponse>)

   Returns a CompletionStage<ServerBinding>."
  ([system host port route]
   (let [http (Http/get system)
         builder (.newServerAt http host (int port))]
     (if (instance? Route route)
       (.bind builder route)
       ;; route is a function: HttpRequest -> CompletionStage<HttpResponse>
       (.bind builder
              (reify Function
                (apply [_ request]
                  (route request)))))))
  ([system host port route materializer]
   (let [http (Http/get system)
         builder (-> (.newServerAt http host (int port))
                     (.withMaterializer materializer))]
     (if (instance? Route route)
       (.bind builder route)
       (.bind builder
              (reify Function
                (apply [_ request]
                  (route request))))))))

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
        raw (.rawQueryString uri)]
    (when (.isDefined raw)
      (.get raw))))

(defn request-query-params
  "Get query parameters as a map of strings.
   Multi-valued params return the first value."
  [^HttpRequest request]
  (let [uri (.getUri request)
        query (.query uri)]
    (into {}
          (for [param (iterator-seq (.iterator (.toList query)))]
            [(.first param) (.second param)]))))

(defn request-header
  "Get a single header value by name (case-insensitive).
   Returns nil if header not present."
  [^HttpRequest request header-name]
  (let [optional (.getHeader request header-name)]
    (when (.isPresent optional)
      (.value (.get optional)))))

(defn request-headers
  "Get all headers as a map.
   Multi-valued headers return the first value."
  [^HttpRequest request]
  (into {}
        (for [header (iterator-seq (.iterator (.getHeaders request)))]
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

(defn entity->string
  "Convert a request entity to a string.
   Returns a CompletionStage<String>.

   materializer-or-system: Materializer or ActorSystem"
  [^HttpRequest request materializer-or-system]
  (let [mat (if (instance? Materializer materializer-or-system)
              materializer-or-system
              (Materializer/createMaterializer materializer-or-system))]
    (-> (.entity request)
        (.toStrict (Duration/create 10 TimeUnit/SECONDS) mat)
        (FutureConverters/asJava)
        (.thenApply (reify Function
                      (apply [_ strict]
                        (.utf8String (.getData strict))))))))

(defn entity->bytes
  "Convert a request entity to a byte array.
   Returns a CompletionStage<byte[]>.

   materializer-or-system: Materializer or ActorSystem"
  [^HttpRequest request materializer-or-system]
  (let [mat (if (instance? Materializer materializer-or-system)
              materializer-or-system
              (Materializer/createMaterializer materializer-or-system))]
    (-> (.entity request)
        (.toStrict (Duration/create 10 TimeUnit/SECONDS) mat)
        (FutureConverters/asJava)
        (.thenApply (reify Function
                      (apply [_ strict]
                        (.toArray (.getData strict))))))))

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
    (vec (remove empty? (clojure.string/split path #"/")))))

(defn match-path-pattern
  "Match a path against a pattern with :param placeholders.
   Returns a map of extracted parameters or nil if no match.

   Example:
   (match-path-pattern \"/users/123\" \"/users/:id\")
   => {:id \"123\"}"
  [path pattern]
  (let [path-parts (remove empty? (clojure.string/split path #"/"))
        pattern-parts (remove empty? (clojure.string/split pattern #"/"))]
    (when (= (count path-parts) (count pattern-parts))
      (loop [remaining-path path-parts
             remaining-pattern pattern-parts
             params {}]
        (if (empty? remaining-path)
          params
          (let [path-part (first remaining-path)
                pattern-part (first remaining-pattern)]
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
