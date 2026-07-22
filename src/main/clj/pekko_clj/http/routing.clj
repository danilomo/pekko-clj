(ns pekko-clj.http.routing
  "Routing DSL for Pekko HTTP.

   Provides a Compojure-style routing API with Pekko HTTP backend.

   Beyond paths and methods it covers:
   - extraction: `param`/`param-opt`/`params`, `form-field`/`form-fields`,
     `header-value`/`header-value-opt`, `with-request-body`
   - marshalling: `with-json-body`/`with-edn-body`/`with-body` in,
     `complete-json`/`complete-edn` out (see `pekko-clj.http.marshalling`)
   - failure handling: `handle-rejections`/`handle-exceptions` with the
     `rejection-handler`/`exception-handler` builders
   - websockets: `websocket` over a stream Flow of Messages (`text-flow`)

   Example:
     (routes
       (path \"users\"
         (routes
           (method-get (path-end (complete-json (list-users))))
           (method-post (path-end (with-json-body #(complete-json :created (create! %)))))))
       (path \"echo\" (websocket (text-flow str/upper-case))))"
  (:require [pekko-clj.http.core :as http]
            [pekko-clj.http.response :as resp]
            [pekko-clj.http.marshalling :as marshal]
            [pekko-clj.stream :as stream]
            [clojure.string :as str])
  (:import [org.apache.pekko.http.javadsl.server Route AllDirectives
            ExceptionHandler RejectionHandler RejectionHandlerBuilder
            Rejection]
           [org.apache.pekko.http.javadsl.model HttpResponse HttpEntity$Strict
            HttpRequest ResponseEntity Uri
            StatusCodes]
           [org.apache.pekko.http.javadsl.model.ws Message TextMessage]
           [org.apache.pekko.japi.pf FI$Apply]
           [java.time Duration]
           [java.util.function Supplier Function]
           [java.util.concurrent CompletionStage]))

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
                 ^Route first-route
                 ^"[Lorg.apache.pekko.http.javadsl.server.Route;"
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
     (.complete directives ^HttpResponse body)

     (instance? ResponseEntity body)
     (.complete directives StatusCodes/OK ^ResponseEntity body)

     :else
     (.complete directives ^String (str body))))
  ([status body]
   (let [sc (resp/->status-code status)]
     (cond
       (instance? ResponseEntity body)
       (.complete directives sc ^ResponseEntity body)

       :else
       (.complete directives sc ^String (str body)))))
  ([status content-type body]
   ;; AllDirectives has no (StatusCode, ContentType, String) overload — build the
   ;; entity first and use (StatusCode, ResponseEntity). The direct call used to
   ;; throw "No matching method complete found taking 3 args".
   (let [sc (resp/->status-code status)]
     (.complete directives sc ^ResponseEntity (resp/entity (str body) content-type)))))

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
              (Uri/create ^String url)
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
         ^String path-str
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
               ^String prefix
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
                          (let [value (if (.isPresent ^java.util.Optional opt-value)
                                        (.get ^java.util.Optional opt-value)
                                        default-value)]
                            (inner-fn value))))))

(defn params
  "Extract all query parameters as a Clojure map of keyword -> string.
   Multi-valued parameters keep their last value (Pekko's parameterMap).

   (params (fn [{:keys [page size]}] (complete (str page \"/\" size))))"
  [inner-fn]
  (.parameterMap directives
                 (reify Function
                   (apply [_ m]
                     (inner-fn (into {} (map (fn [e] [(keyword (key e)) (val e)])) m))))))

;; ---------------------------------------------------------------------------
;; Form Field Directives
;; ---------------------------------------------------------------------------

(defn form-field
  "Extract a required form field (application/x-www-form-urlencoded body).

   (form-field \"username\" (fn [name] (complete name)))"
  [field-name inner-fn]
  (.formField directives
              field-name
              (reify Function
                (apply [_ value] (inner-fn value)))))

(defn form-field-opt
  "Extract an optional form field, using default-value when absent."
  [field-name default-value inner-fn]
  (.formFieldOptional directives
                      field-name
                      (reify Function
                        (apply [_ opt-value]
                          (inner-fn (if (.isPresent ^java.util.Optional opt-value)
                                      (.get ^java.util.Optional opt-value)
                                      default-value))))))

(defn form-fields
  "Extract all form fields as a Clojure map of keyword -> string.

   (form-fields (fn [{:keys [username password]}] ...))"
  [inner-fn]
  (.formFieldMap directives
                 (reify Function
                   (apply [_ m]
                     (inner-fn (into {} (map (fn [e] [(keyword (key e)) (val e)])) m))))))

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
                                  (let [value (when (.isPresent ^java.util.Optional opt-value)
                                                (.get ^java.util.Optional opt-value))]
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
  ;; toStrictEntity takes a java.time.Duration (or FiniteDuration + long), never a
  ;; bare long — passing one threw "No matching method toStrictEntity".
  (.toStrictEntity directives
                   (Duration/ofMillis (long timeout-millis))
                   (reify Supplier
                     (get [_]
                       (extract-request
                        (fn [req]
                          (inner-fn (.entity ^HttpRequest req))))))))

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
  (if (some #(.startsWith (str %) ":") (str/split path-pattern #"/"))
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
  (if (some #(.startsWith (str %) ":") (str/split path-pattern #"/"))
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
  (if (some #(.startsWith (str %) ":") (str/split path-pattern #"/"))
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
  (if (some #(.startsWith (str %) ":") (str/split path-pattern #"/"))
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
  (if (some #(.startsWith (str %) ":") (str/split path-pattern #"/"))
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
  "Extract the request body as a string and pass it to handler-fn, which returns a
   Route. The body is buffered (made strict) first, so handler-fn is called with a
   plain string — no futures to thread through.

   (with-request-body
     (fn [body] (complete :ok body)))
   (with-request-body 10000 (fn [body] ...))   ;; custom buffering timeout (ms)"
  ([handler-fn] (with-request-body 5000 handler-fn))
  ([timeout-ms handler-fn]
   (.extractStrictEntity directives
                         (Duration/ofMillis (long timeout-ms))
                         (reify Function
                           (apply [_ strict]
                             (handler-fn (.utf8String (.getData ^HttpEntity$Strict strict))))))))

(defn- with-parsed-body
  "Shared body parsing: buffer the body, parse it with parse-fn, and hand the data
   to handler-fn. A parse failure completes 400 instead of throwing."
  [timeout-ms parse-fn label handler-fn]
  (with-request-body
    timeout-ms
    (fn [body]
      (let [parsed (try
                     {:ok (parse-fn body)}
                     (catch Exception e
                       {:error (.getMessage e)}))]
        (if (contains? parsed :ok)
          (handler-fn (:ok parsed))
          (complete :bad-request (str "Malformed " label " body: " (:error parsed))))))))

(defn with-json-body
  "Parse the request body as JSON (keys keywordized) and pass the data to
   handler-fn, which returns a Route. A malformed body completes 400.

   (with-json-body (fn [{:keys [name]}] (complete-json :created {:name name})))"
  ([handler-fn] (with-json-body 5000 handler-fn))
  ([timeout-ms handler-fn]
   (with-parsed-body timeout-ms marshal/json-> "JSON" handler-fn)))

(defn with-edn-body
  "Parse the request body as EDN (via clojure.edn — no code evaluation) and pass
   the data to handler-fn, which returns a Route. A malformed body completes 400."
  ([handler-fn] (with-edn-body 5000 handler-fn))
  ([timeout-ms handler-fn]
   (with-parsed-body timeout-ms marshal/edn-> "EDN" handler-fn)))

(defn with-body
  "Parse the request body according to its Content-Type — JSON and EDN become
   Clojure data, anything else stays a string — and pass it to handler-fn."
  ([handler-fn] (with-body 5000 handler-fn))
  ([timeout-ms handler-fn]
   (extract-request
    (fn [req]
      (with-parsed-body timeout-ms
        (fn [body] (marshal/unmarshal (http/request-content-type req) body))
        "request"
        handler-fn)))))

;; ---------------------------------------------------------------------------
;; Marshalled Completion
;; ---------------------------------------------------------------------------

(defn complete-json
  "Complete with Clojure data encoded as JSON (application/json).

   (complete-json {:id 1})
   (complete-json :created {:id 1})"
  ([data] (complete-json :ok data))
  ([status data] (complete status (resp/json data))))

(defn complete-edn
  "Complete with Clojure data encoded as EDN (application/edn)."
  ([data] (complete-edn :ok data))
  ([status data] (complete status (resp/edn data))))

;; ---------------------------------------------------------------------------
;; Rejection & Exception Handling
;; ---------------------------------------------------------------------------

(defn rejection-handler
  "Build a Pekko RejectionHandler.

   Options map:
   - :not-found - Route used when no route matched (Pekko's 'handleNotFound')
   - :all       - (fn [rejections] route) handling every remaining rejection; the
                  argument is a Clojure seq of Rejection objects
   - :handle    - map of Rejection class -> (fn [rejection] route)

   Returns: RejectionHandler — pass it to `handle-rejections`.

   Example:
     (rejection-handler
       {:not-found (complete :not-found \"nothing here\")
        :handle {MethodRejection (fn [_] (complete :method-not-allowed \"nope\"))}})"
  ^RejectionHandler [{:keys [not-found all handle]}]
  (let [^RejectionHandlerBuilder builder (RejectionHandler/newBuilder)
        ^RejectionHandlerBuilder builder
        (reduce (fn [^RejectionHandlerBuilder b [klass f]]
                  (.handle b klass (reify Function
                                     (apply [_ rejection] (f rejection)))))
                builder
                handle)
        ^RejectionHandlerBuilder builder
        (if all
          (.handleAll builder Rejection
                      (reify Function
                        (apply [_ rejections] (all (seq rejections)))))
          builder)
        ^RejectionHandlerBuilder builder
        (if not-found (.handleNotFound builder ^Route not-found) builder)]
    (.build builder)))

(defn handle-rejections
  "Run `route` with a RejectionHandler (from `rejection-handler`, or a raw
   RejectionHandler) in scope.

   (handle-rejections (rejection-handler {:not-found (complete :not-found \"…\")})
     my-routes)"
  [^RejectionHandler handler route]
  (.handleRejections directives handler (reify Supplier (get [_] route))))

(defn exception-handler
  "Build a Pekko ExceptionHandler from a map of Throwable class -> (fn [ex] route),
   or from a single (fn [ex] route) applied to any Throwable.

   Example:
     (exception-handler
       {IllegalArgumentException (fn [e] (complete :bad-request (.getMessage e)))
        Throwable                (fn [_] (complete :internal-server-error \"boom\"))})"
  ^ExceptionHandler [handlers]
  (let [builder (ExceptionHandler/newBuilder)]
    (if (map? handlers)
      (do (doseq [[klass f] handlers]
            (.match builder klass (reify FI$Apply (apply [_ ex] (f ex)))))
          (.build builder))
      (-> builder
          (.matchAny (reify FI$Apply (apply [_ ex] (handlers ex))))
          (.build)))))

(defn handle-exceptions
  "Run `route` with an ExceptionHandler (from `exception-handler`, or a raw
   ExceptionHandler) in scope, turning thrown exceptions into responses.

   (handle-exceptions (exception-handler {Throwable (fn [e] (complete :internal-server-error …))})
     my-routes)"
  [^ExceptionHandler handler route]
  (.handleExceptions directives handler (reify Supplier (get [_] route))))

;; ---------------------------------------------------------------------------
;; WebSockets
;; ---------------------------------------------------------------------------

(defn text-message
  "Create a strict WebSocket text Message."
  ^TextMessage [^String s]
  (TextMessage/create s))

(defn message->text
  "The text of a strict WebSocket text message, or nil for binary or streamed
   messages (stream those yourself with `.getStreamedText`)."
  [^Message msg]
  (when (and (.isText msg) (.isStrict msg))
    (.getStrictText (.asTextMessage msg))))

(defn text-flow
  "Build a Flow of WebSocket Messages that answers each incoming *text* message
   with (f text). Returning nil from f drops the message; binary and streamed
   messages are dropped too.

   (websocket (text-flow str/upper-case))"
  [f]
  (-> (stream/flow-of Message)
      (stream/smap (fn [msg] (some-> (message->text msg) f)))
      (stream/sfilter some?)
      (stream/smap (fn [text] (text-message (str text))))))

(defn websocket
  "Handle a WebSocket upgrade on this route with a Flow of Messages (see
   `text-flow` for the common text case). Requests that are not upgrades are
   rejected, so this composes with `routes`.

   (path \"echo\" (websocket (text-flow identity)))
   (path \"chat\" (websocket flow \"chat-v1\"))   ;; require a subprotocol"
  ([flow] (.handleWebSocketMessages directives flow))
  ([flow ^String protocol] (.handleWebSocketMessagesForProtocol directives flow protocol)))

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
