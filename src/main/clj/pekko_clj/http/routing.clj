(ns pekko-clj.http.routing
  "Routing DSL for Pekko HTTP.

   Provides a Compojure-style routing API with Pekko HTTP backend."
  (:require [pekko-clj.http.core :as http]
            [pekko-clj.http.response :as resp])
  (:import [org.apache.pekko.http.javadsl.server Route AllDirectives Directives]
           [org.apache.pekko.http.javadsl.model HttpRequest HttpResponse
                                                  HttpMethods StatusCodes]
           [org.apache.pekko.http.javadsl.model.headers Location]
           [java.util.function Supplier Function]
           [java.util.concurrent CompletionStage CompletableFuture]
           [scala.jdk.javaapi FutureConverters]))

;; ---------------------------------------------------------------------------
;; Internal: Directives Instance
;; ---------------------------------------------------------------------------

(def ^:private ^AllDirectives directives
  "Singleton instance of AllDirectives for building routes."
  (proxy [AllDirectives] []))

;; ---------------------------------------------------------------------------
;; Route Composition
;; ---------------------------------------------------------------------------

(defn routes
  "Combine multiple routes, trying each in order until one matches.
   Returns a Route."
  [& route-list]
  (if (empty? route-list)
    (.reject directives)
    (let [first-route (first route-list)
          rest-routes (rest route-list)]
      (if (empty? rest-routes)
        first-route
        (.concat directives
                 first-route
                 (into-array Route (vec rest-routes)))))))

;; ---------------------------------------------------------------------------
;; Completion Directives
;; ---------------------------------------------------------------------------

(defn complete
  "Complete the route with a response.

   Arities:
   (complete body) - OK response with text body
   (complete status body) - response with status and body
   (complete status content-type body) - response with status, content-type, and body

   body can be:
   - string: sent as-is
   - HttpEntity: used directly
   - HttpResponse: used directly"
  ([body]
   (cond
     (instance? HttpResponse body)
     (.complete directives body)

     (instance? org.apache.pekko.http.javadsl.model.ResponseEntity body)
     (.complete directives StatusCodes/OK body)

     :else
     (.complete directives (str body))))
  ([status body]
   (let [sc (resp/->status-code status)]
     (cond
       (instance? org.apache.pekko.http.javadsl.model.ResponseEntity body)
       (.complete directives sc body)

       :else
       (.complete directives sc (str body)))))
  ([status content-type body]
   (let [sc (resp/->status-code status)
         ct (resp/->content-type content-type)]
     (.complete directives sc ct (str body)))))

(defn complete-future
  "Complete with a future response.
   future: CompletionStage<HttpResponse>"
  [^CompletionStage future]
  (.completeWithFuture directives future))

(defn not-found
  "Complete with a 404 Not Found response."
  [body]
  (complete :not-found body))

(defn redirect
  "Redirect to a URL.
   status defaults to :found (302)."
  ([url]
   (redirect url :found))
  ([url status]
   (.redirect directives
              (org.apache.pekko.http.javadsl.model.Uri/create url)
              (resp/->status-code status))))

(defn reject
  "Reject the current route, allowing the next route to try."
  []
  (.reject directives))

;; ---------------------------------------------------------------------------
;; Path Directives
;; ---------------------------------------------------------------------------

(defn path
  "Match an exact path.

   (path \"/users\" inner-route)"
  [path-str inner-route]
  (.path directives
         path-str
         (reify Supplier
           (get [_] inner-route))))

(defn path-prefix
  "Match a path prefix.

   (path-prefix \"/api\"
     (routes
       user-routes
       post-routes))"
  [prefix inner-route]
  (.pathPrefix directives
               prefix
               (reify Supplier
                 (get [_] inner-route))))

(defn path-end
  "Match only if at the end of the path."
  [inner-route]
  (.pathEnd directives
            (reify Supplier
              (get [_] inner-route))))

;; ---------------------------------------------------------------------------
;; Method Directives
;; ---------------------------------------------------------------------------

(defn method-get
  "Match only GET requests."
  [inner-route]
  (.get directives
        (reify Supplier
          (get [_] inner-route))))

(defn method-post
  "Match only POST requests."
  [inner-route]
  (.post directives
         (reify Supplier
           (get [_] inner-route))))

(defn method-put
  "Match only PUT requests."
  [inner-route]
  (.put directives
        (reify Supplier
          (get [_] inner-route))))

(defn method-delete
  "Match only DELETE requests."
  [inner-route]
  (.delete directives
           (reify Supplier
             (get [_] inner-route))))

(defn method-head
  "Match only HEAD requests."
  [inner-route]
  (.head directives
         (reify Supplier
           (get [_] inner-route))))

(defn method-options
  "Match only OPTIONS requests."
  [inner-route]
  (.options directives
            (reify Supplier
              (get [_] inner-route))))

(defn method-patch
  "Match only PATCH requests."
  [inner-route]
  (.patch directives
          (reify Supplier
            (get [_] inner-route))))

;; ---------------------------------------------------------------------------
;; Parameter Directives
;; ---------------------------------------------------------------------------

(defn param
  "Extract a required query parameter.

   (param \"id\" (fn [id] (complete (str \"ID: \" id))))"
  [param-name inner-fn]
  (.parameter directives
              param-name
              (reify Function
                (apply [_ value]
                  (inner-fn value)))))

(defn param-opt
  "Extract an optional query parameter with a default value.

   (param-opt \"page\" \"1\" (fn [page] (complete page)))"
  [param-name default-value inner-fn]
  (.parameterOptional directives
                      param-name
                      (reify Function
                        (apply [_ opt-value]
                          (let [value (if (.isPresent opt-value)
                                        (.get opt-value)
                                        default-value)]
                            (inner-fn value))))))

;; ---------------------------------------------------------------------------
;; Header Directives
;; ---------------------------------------------------------------------------

(defn header-value
  "Extract a required header value.

   (header-value \"Authorization\" (fn [auth] ...))"
  [header-name inner-fn]
  (.headerValueByName directives
                      header-name
                      (reify Function
                        (apply [_ value]
                          (inner-fn value)))))

(defn header-value-opt
  "Extract an optional header value.

   (header-value-opt \"X-Custom\" (fn [maybe-value] ...))"
  [header-name inner-fn]
  (.optionalHeaderValueByName directives
                              header-name
                              (reify Function
                                (apply [_ opt-value]
                                  (let [value (when (.isPresent opt-value)
                                                (.get opt-value))]
                                    (inner-fn value))))))

(defn respond-with-header
  "Add a response header.

   (respond-with-header \"X-Custom\" \"value\" inner-route)"
  [header-name header-value inner-route]
  (.respondWithHeader directives
                      (org.apache.pekko.http.javadsl.model.headers.RawHeader/create
                       header-name header-value)
                      (reify Supplier
                        (get [_] inner-route))))

;; ---------------------------------------------------------------------------
;; Entity Directives
;; ---------------------------------------------------------------------------

(defn extract-request
  "Extract the full HttpRequest.

   (extract-request (fn [req] ...))"
  [inner-fn]
  (.extractRequest directives
                   (reify Function
                     (apply [_ request]
                       (inner-fn request)))))

(defn extract-uri
  "Extract the request URI.

   (extract-uri (fn [uri] ...))"
  [inner-fn]
  (.extractUri directives
               (reify Function
                 (apply [_ uri]
                   (inner-fn uri)))))

(defn extract-materializer
  "Extract the materializer for stream operations.

   (extract-materializer (fn [mat] ...))"
  [inner-fn]
  (.extractMaterializer directives
                        (reify Function
                          (apply [_ mat]
                            (inner-fn mat)))))

(defn extract-strict-entity
  "Extract the request entity as a strict (fully-buffered) entity.
   timeout-millis: maximum time to wait for the entity.

   (extract-strict-entity 5000 (fn [entity] ...))"
  [timeout-millis inner-fn]
  (.toStrictEntity directives
                   (long timeout-millis)
                   (reify Supplier
                     (get [_]
                       (extract-request
                        (fn [req]
                          (let [entity (.entity req)]
                            (inner-fn entity))))))))

;; ---------------------------------------------------------------------------
;; Compojure-style Macros
;; ---------------------------------------------------------------------------

(defmacro GET
  "Define a GET route with path matching.

   (GET \"/users\" []
     (complete :ok (json users)))

   (GET \"/users/:id\" [id]
     (complete :ok (json (get-user id))))"
  [path-pattern bindings & body]
  (if (some #(.startsWith (str %) ":") (clojure.string/split path-pattern #"/"))
    ;; Path with parameters - use extract-request
    `(method-get
      (extract-request
       (fn [req#]
         (let [params# (http/match-path-pattern (http/request-path req#) ~path-pattern)]
           (if params#
             (let [{:keys ~bindings} params#]
               ~@body)
             (reject))))))
    ;; Simple path
    `(path ~path-pattern
           (method-get
            (path-end
             (let ~bindings
               ~@body))))))

(defmacro POST
  "Define a POST route with path matching.

   (POST \"/users\" []
     (complete :created (json new-user)))"
  [path-pattern bindings & body]
  (if (some #(.startsWith (str %) ":") (clojure.string/split path-pattern #"/"))
    `(method-post
      (extract-request
       (fn [req#]
         (let [params# (http/match-path-pattern (http/request-path req#) ~path-pattern)]
           (if params#
             (let [{:keys ~bindings} params#]
               ~@body)
             (reject))))))
    `(path ~path-pattern
           (method-post
            (path-end
             (let ~bindings
               ~@body))))))

(defmacro PUT
  "Define a PUT route with path matching."
  [path-pattern bindings & body]
  (if (some #(.startsWith (str %) ":") (clojure.string/split path-pattern #"/"))
    `(method-put
      (extract-request
       (fn [req#]
         (let [params# (http/match-path-pattern (http/request-path req#) ~path-pattern)]
           (if params#
             (let [{:keys ~bindings} params#]
               ~@body)
             (reject))))))
    `(path ~path-pattern
           (method-put
            (path-end
             (let ~bindings
               ~@body))))))

(defmacro DELETE
  "Define a DELETE route with path matching."
  [path-pattern bindings & body]
  (if (some #(.startsWith (str %) ":") (clojure.string/split path-pattern #"/"))
    `(method-delete
      (extract-request
       (fn [req#]
         (let [params# (http/match-path-pattern (http/request-path req#) ~path-pattern)]
           (if params#
             (let [{:keys ~bindings} params#]
               ~@body)
             (reject))))))
    `(path ~path-pattern
           (method-delete
            (path-end
             (let ~bindings
               ~@body))))))

(defmacro PATCH
  "Define a PATCH route with path matching."
  [path-pattern bindings & body]
  (if (some #(.startsWith (str %) ":") (clojure.string/split path-pattern #"/"))
    `(method-patch
      (extract-request
       (fn [req#]
         (let [params# (http/match-path-pattern (http/request-path req#) ~path-pattern)]
           (if params#
             (let [{:keys ~bindings} params#]
               ~@body)
             (reject))))))
    `(path ~path-pattern
           (method-patch
            (path-end
             (let ~bindings
               ~@body))))))

;; ---------------------------------------------------------------------------
;; Utility Functions
;; ---------------------------------------------------------------------------

(defn with-request-body
  "Extract and process the request body as a string.

   (with-request-body
     (fn [body] (complete :ok body)))"
  [handler-fn]
  (extract-request
   (fn [req]
     (extract-materializer
      (fn [mat]
        (complete-future
         (-> (http/entity->string req mat)
             (.thenApply
              (reify Function
                (apply [_ body-str]
                  (let [route (handler-fn body-str)]
                    ;; Route needs to be converted to response
                    ;; For simplicity, return the route directly
                    ;; The caller should ensure handler-fn returns a response
                    route)))))))))))

(defn handle-request
  "Create a route from a request handler function.
   handler-fn: (fn [request] -> HttpResponse or CompletionStage<HttpResponse>)"
  [handler-fn]
  (extract-request
   (fn [req]
     (let [result (handler-fn req)]
       (if (instance? CompletionStage result)
         (complete-future result)
         (complete result))))))
