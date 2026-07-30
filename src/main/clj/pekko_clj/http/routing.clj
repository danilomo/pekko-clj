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
   - static content: `from-resource`/`from-resource-directory`, `from-file`/
     `from-directory`, with content types resolved from the file extension
   - auth: `basic-auth` (challenge + 401 handled for you), `bearer-token`
   - compression: `encode-response`/`decode-request` (gzip/deflate negotiated)
   - timeouts: `with-request-timeout`/`without-request-timeout`
   - websockets: `websocket` over a stream Flow of Messages (`text-flow`)
   - server-sent events: `sse` over a stream Source of events
   - async routes: `on-success`/`on-complete` drive a CompletionStage (an actor
     `<?>`) into a Route without blocking
   - client IP: `extract-client-ip` (needs `remote-address-attribute = on`)

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
            PathMatchers Rejection]
           [org.apache.pekko.http.javadsl.server.directives
            SecurityDirectives$ProvidedCredentials]
           [org.apache.pekko.http.javadsl.model ContentType HttpResponse HttpEntity$Strict
            HttpRequest RemoteAddress ResponseEntity StatusCode Uri
            StatusCodes]
           [org.apache.pekko.http.javadsl.model.sse ServerSentEvent]
           [org.apache.pekko.http.javadsl.model.ws Message TextMessage]
           [org.apache.pekko.http.javadsl.marshalling Marshaller]
           [org.apache.pekko.http.javadsl.marshalling.sse EventStreamMarshalling]
           [org.apache.pekko.http.javadsl.coding Coder]
           [org.apache.pekko.stream.javadsl Source]
           [org.apache.pekko.japi.pf FI$Apply]
           [java.net InetAddress]
           [java.time Duration]
           [java.util Optional OptionalInt]
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

(defn- segment-prefix
  "Match one static segment of the *unmatched* path, then continue."
  [^String segment inner-route]
  (.pathPrefix directives
               segment
               (reify Supplier
                 (get [_] inner-route))))

(defn- segment-exact
  "Match one static segment and require the path to end there."
  [^String segment inner-route]
  (.path directives
         segment
         (reify Supplier
           (get [_] inner-route))))

(defn- pattern->segments
  "Split a path pattern into segments, tolerating leading/trailing/duplicate
   slashes: \"/api/v1/\" and \"api/v1\" both give [\"api\" \"v1\"]."
  [pattern]
  (vec (remove str/blank? (str/split (str pattern) #"/"))))

(defn path
  "Match a path exactly (the whole remaining, unmatched path).

   The pattern may be a single segment or several; a leading slash is optional,
   so all three of these are the same route:

   (path \"users\" inner-route)
   (path \"/users\" inner-route)
   (path \"api/users\" inner-route)

   Composes under `path-prefix` — it only ever looks at the unmatched path."
  [path-str inner-route]
  (let [segments (pattern->segments path-str)]
    (if (empty? segments)
      (.pathEnd directives (reify Supplier (get [_] inner-route)))
      (reduce (fn [route segment] (segment-prefix segment route))
              (segment-exact (peek segments) inner-route)
              (rseq (pop segments))))))

(defn path-prefix
  "Match a path prefix, leaving the rest of the path for inner routes.

   Like `path`, the pattern may span several segments and the leading slash is
   optional.

   (path-prefix \"api\"
     (routes
       user-routes
       post-routes))"
  [prefix inner-route]
  (reduce (fn [route segment] (segment-prefix segment route))
          inner-route
          (rseq (pattern->segments prefix))))

(defn path-var
  "Match one path segment, bind it as a string, and require the path to end
   there. inner-fn returns the Route for that value.

   (path-var (fn [id] (complete (str \"user \" id))))"
  [inner-fn]
  (.path directives
         (PathMatchers/segment)
         (reify Function
           (apply [_ value] (inner-fn value)))))

(defn path-prefix-var
  "Match one path segment, bind it as a string, and continue matching the rest
   of the path inside inner-fn's Route.

   (path-prefix-var (fn [id] (path \"posts\" (complete (str id \"'s posts\")))))"
  [inner-fn]
  (.pathPrefix directives
               (PathMatchers/segment)
               (reify Function
                 (apply [_ value] (inner-fn value)))))

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
;; Static Content
;; ---------------------------------------------------------------------------
;;
;; Content types come from Pekko's default resolver, which reads the file
;; extension (`.css` -> text/css, `.png` -> image/png, ...), so nothing has to be
;; declared per file. The directory forms resolve the *unmatched* path against the
;; directory, so they nest under `path-prefix` the way the route macros do.

(defn from-resource
  "Serve a single file from the classpath.

   (path \"favicon.ico\" (from-resource \"public/favicon.ico\"))"
  ([^String resource-path]
   (.getFromResource directives resource-path))
  ([^String resource-path ^ContentType content-type]
   (.getFromResource directives resource-path content-type)))

(defn from-resource-directory
  "Serve a classpath directory, resolving the still-unmatched path inside it.

   (path-prefix \"assets\" (from-resource-directory \"public\"))
   ;; GET /assets/css/app.css  ->  classpath resource public/css/app.css"
  [^String resource-directory]
  (.getFromResourceDirectory directives resource-directory))

(defn from-file
  "Serve a single file from the filesystem. `path` is a String or a java.io.File;
   the 2-arity (explicit ContentType) needs a File."
  ([path]
   (if (instance? java.io.File path)
     (.getFromFile directives ^java.io.File path)
     (.getFromFile directives ^String path)))
  ([path ^ContentType content-type]
   (.getFromFile directives
                 (if (instance? java.io.File path) ^java.io.File path (java.io.File. ^String path))
                 content-type)))

(defn from-directory
  "Serve a filesystem directory, resolving the still-unmatched path inside it.

   Pekko refuses to serve outside the directory, so `..` segments cannot escape it.

   (path-prefix \"static\" (from-directory \"/var/www\"))"
  [^String directory]
  (.getFromDirectory directives directory))

;; ---------------------------------------------------------------------------
;; Authentication
;; ---------------------------------------------------------------------------

(defn basic-auth
  "HTTP Basic authentication.

   Arguments:
   - realm: named in the `WWW-Authenticate` challenge sent with the 401
   - authenticator: (fn [user verify] principal-or-nil). `user` is the supplied
     username; `verify` is a predicate taking *your* known secret for that user and
     returning true when it matches what the client sent. Return any value to
     accept (it is handed to inner-fn) or nil to reject.
   - inner-fn: (fn [principal] route)

   The supplied password is deliberately not reachable: Pekko exposes only
   `verify`, which compares in constant time, so a wrapper handing back the raw
   password would trade that away for nothing.

   A missing or wrong credential is rejected with 401 and the challenge; there is
   nothing to handle in the route.

   Example:
     (basic-auth \"admin area\"
       (fn [user verify]
         (when-let [secret (get users user)]
           (when (verify secret) {:user user})))
       (fn [principal] (complete (str \"hi \" (:user principal)))))"
  [^String realm authenticator inner-fn]
  (.authenticateBasic
   directives
   realm
   (reify Function
     (apply [_ opt-credentials]
       (let [^java.util.Optional opt opt-credentials]
         (if (.isPresent opt)
           (let [^SecurityDirectives$ProvidedCredentials creds (.get opt)]
             (java.util.Optional/ofNullable
              (authenticator (.identifier creds)
                             (fn [secret] (.verify creds (str secret))))))
           (java.util.Optional/empty)))))
   (reify Function
     (apply [_ principal] (inner-fn principal)))))

(defn bearer-token
  "Extract the token from an `Authorization: Bearer <token>` header.

   `inner-fn` receives the token, or nil when the header is absent or uses another
   scheme — deciding what that means (401, anonymous access, ...) is the route's
   job, since this is extraction, not authentication. For a challenge-based OAuth2
   flow use Pekko's `authenticateOAuth2` directly.

   Example:
     (bearer-token (fn [token]
                     (if-let [user (verify-jwt token)]
                       (complete-json user)
                       (complete StatusCodes/UNAUTHORIZED \"nope\"))))"
  [inner-fn]
  (header-value-opt
   "Authorization"
   (fn [value]
     (inner-fn (when (and value (str/starts-with? (str/lower-case value) "bearer "))
                 (str/trim (subs value (count "Bearer "))))))))

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
;; Compression (content coding)
;; ---------------------------------------------------------------------------

(defn- ->coder
  "Resolve a coder keyword to a Pekko Coder (a Coder passes through)."
  ^Coder [coder]
  (cond
    (instance? Coder coder) coder
    (= coder :gzip)    Coder/Gzip
    (= coder :deflate) Coder/Deflate
    (or (= coder :none) (= coder :identity)) Coder/NoCoding
    :else (throw (IllegalArgumentException.
                  (str "Unknown coder " (pr-str coder)
                       " — expected :gzip, :deflate, :none, or a Coder")))))

(defn encode-response
  "Compress the response with whichever encoding the client asked for in
   `Accept-Encoding` (gzip/deflate/identity), negotiated automatically. A client
   that asks for none gets the response unchanged.

   (encode-response my-routes)"
  [inner-route]
  (.encodeResponse directives (reify Supplier (get [_] inner-route))))

(defn encode-response-with
  "Like `encode-response`, but restrict the offered encodings to `coders` (a coll
   of :gzip / :deflate / :none keywords or Coder values), in preference order.

   (encode-response-with [:gzip] my-routes)"
  [coders inner-route]
  (.encodeResponseWith directives
                       ^Iterable (mapv ->coder coders)
                       (reify Supplier (get [_] inner-route))))

(defn decode-request
  "Decode a compressed request entity (per its `Content-Encoding`) before inner
   routes read the body. gzip, deflate and identity are handled automatically.

   (decode-request (with-request-body (fn [body] ...)))"
  [inner-route]
  (.decodeRequest directives (reify Supplier (get [_] inner-route))))

(defn decode-request-with
  "Like `decode-request`, but only accept the single `coder` (a :gzip / :deflate /
   :none keyword or a Coder); a request in any other encoding is rejected."
  [coder inner-route]
  (.decodeRequestWith directives
                      (->coder coder)
                      (reify Supplier (get [_] inner-route))))

;; ---------------------------------------------------------------------------
;; Request Timeouts
;; ---------------------------------------------------------------------------

(defn with-request-timeout
  "Override the server's request timeout for `inner-route`. If the route has not
   completed within `timeout-ms`, Pekko finishes the request with 503 Service
   Unavailable — or with `timeout-response` (an HttpResponse) when the 3-arity is
   used.

   (with-request-timeout 2000 slow-routes)
   (with-request-timeout 2000 (resp/response :service-unavailable \"too slow\") slow-routes)"
  ([timeout-ms inner-route]
   (.withRequestTimeout directives
                        (Duration/ofMillis (long timeout-ms))
                        (reify Supplier (get [_] inner-route))))
  ([timeout-ms ^HttpResponse timeout-response inner-route]
   (.withRequestTimeout directives
                        (Duration/ofMillis (long timeout-ms))
                        (reify Function (apply [_ _req] timeout-response))
                        (reify Supplier (get [_] inner-route)))))

(defn without-request-timeout
  "Disable the request timeout for `inner-route` (for long-lived responses such as
   server-sent events or large downloads)."
  [inner-route]
  (.withoutRequestTimeout directives (reify Supplier (get [_] inner-route))))

;; ---------------------------------------------------------------------------
;; Compojure-style Macros
;; ---------------------------------------------------------------------------

(defn- path-pattern-form
  "Expand a Compojure-style path pattern (\"/users/:id\") into a nested chain of
   path directives ending in `terminal`.

   Every segment consumes from the *unmatched* path — static segments through
   `path-prefix`/`path`, `:param` segments through `path-prefix-var`/`path-var`
   — so a macro route composes under an enclosing `path-prefix` and requires the
   path to end where the pattern does. `terminal` is called with the collected
   [param-keyword binding-symbol] pairs and returns the innermost form."
  [pattern terminal]
  (let [segments (pattern->segments pattern)
        last-idx (dec (count segments))]
    (letfn [(step [i params]
              (let [segment (nth segments i)
                    last? (= i last-idx)]
                (if (str/starts-with? segment ":")
                  (let [sym (gensym "path-segment-")
                        params (conj params [(keyword (subs segment 1)) sym])]
                    `(~(if last? `path-var `path-prefix-var)
                      (fn [~sym]
                        ~(if last? (terminal params) (step (inc i) params)))))
                  `(~(if last? `path `path-prefix)
                    ~segment
                    ~(if last? (terminal params) (step (inc i) params))))))]
      (if (empty? segments)
        `(path-end ~(terminal []))
        (step 0 [])))))

(defn- method-route-form
  "The body of a Compojure-style macro: match the pattern, then the method, then
   run `body` with the pattern's `:param` segments bound via `bindings`."
  [method-fn pattern bindings body]
  (path-pattern-form
   pattern
   (fn [params]
     `(~method-fn
       (let [{:keys ~bindings} ~(into {} params)]
         ~@body)))))

(defmacro GET
  "Define a GET route with path matching.

   The pattern is split on \"/\" (a leading slash is optional); `:name` segments
   are captured and bound by name through `bindings`, which destructures like
   `{:keys …}`:

   (GET \"/users\" []
     (complete-json users))

   (GET \"/users/:id\" [id]
     (complete-json (get-user id)))

   Only the pattern's own segments are consumed, so the route nests:

   (path-prefix \"api\" (GET \"users/:id\" [id] …))   ;; GET /api/users/42"
  [path-pattern bindings & body]
  (method-route-form `method-get path-pattern bindings body))

(defmacro POST
  "Define a POST route with path matching (see `GET` for the pattern rules).

   (POST \"/users\" []
     (with-json-body #(complete-json :created (create! %))))"
  [path-pattern bindings & body]
  (method-route-form `method-post path-pattern bindings body))

(defmacro PUT
  "Define a PUT route with path matching (see `GET` for the pattern rules)."
  [path-pattern bindings & body]
  (method-route-form `method-put path-pattern bindings body))

(defmacro DELETE
  "Define a DELETE route with path matching (see `GET` for the pattern rules)."
  [path-pattern bindings & body]
  (method-route-form `method-delete path-pattern bindings body))

(defmacro PATCH
  "Define a PATCH route with path matching (see `GET` for the pattern rules)."
  [path-pattern bindings & body]
  (method-route-form `method-patch path-pattern bindings body))

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

;; ---------------------------------------------------------------------------
;; Server-Sent Events (SSE)
;; ---------------------------------------------------------------------------

(defn- opt-str ^Optional [v] (if (some? v) (Optional/of (str v)) (Optional/empty)))
(defn- opt-int ^OptionalInt [v] (if (some? v) (OptionalInt/of (int v)) (OptionalInt/empty)))

(defn ->server-sent-event
  "Coerce a Clojure value to a Pekko ServerSentEvent (a ServerSentEvent passes
   through). A string becomes a data-only event; a map may carry :data (the payload,
   required), :event (the event type/name), :id (the last-event-id), and :retry (the
   client reconnection delay in ms)."
  ^ServerSentEvent [event]
  (cond
    (instance? ServerSentEvent event) event
    (string? event) (ServerSentEvent/create ^String event)
    (map? event) (ServerSentEvent/create ^String (str (:data event))
                                         (opt-str (:event event))
                                         (opt-str (:id event))
                                         (opt-int (:retry event)))
    :else (throw (IllegalArgumentException.
                  (str "SSE event must be a ServerSentEvent, a string, or a "
                       "{:data … :event … :id … :retry …} map, got " (pr-str event))))))

(defn sse
  "Complete the route with a Server-Sent Events (`text/event-stream`) response.

   `events` is a stream Source whose elements are events — each a ServerSentEvent, a
   string (data only), or a `{:data … :event … :id … :retry …}` map (see
   `->server-sent-event`). The connection stays open for the life of the Source, so
   wrap the route in `without-request-timeout` for an unbounded stream.

   (without-request-timeout
     (sse (stream/source-tick 0 1000 {:data \"tick\"})))"
  [events]
  (.complete directives
             ^StatusCode StatusCodes/OK
             ^Source (stream/smap events ->server-sent-event)
             ^Marshaller (EventStreamMarshalling/toEventStream)))

;; ---------------------------------------------------------------------------
;; Async routes (drive a CompletionStage, e.g. an actor ask, without blocking)
;; ---------------------------------------------------------------------------

(defn on-success
  "Wait for `stage` (a CompletionStage — e.g. `core/<?>` against an actor) to
   complete SUCCESSFULLY, bind its value, and build the inner Route from it. If the
   stage fails, Pekko routes the failure to the exception handler (a 500 by
   default); use `on-complete` to handle the failure yourself.

   (on-success (core/<?> actor :get 3000)
     (fn [reply] (complete-json reply)))"
  [^CompletionStage stage inner-fn]
  (.onSuccess directives stage
              (reify Function
                (apply [_ value] (inner-fn value)))))

(defn on-complete
  "Wait for `stage` (a CompletionStage) to complete either way, then build the inner
   Route. `inner-fn` receives a map: `{:success true :value v}` on success, or
   `{:success false :error throwable}` on failure — so you can turn a failed actor
   ask into a chosen response instead of a bare 500.

   (on-complete (core/<?> actor :get 3000)
     (fn [{:keys [success value error]}]
       (if success (complete-json value)
                   (complete :internal-server-error (.getMessage error)))))"
  [^CompletionStage stage inner-fn]
  (.onComplete directives stage
               (reify Function
                 (apply [_ result]
                   (let [^scala.util.Try t result]
                     (inner-fn (if (.isSuccess t)
                                 {:success true :value (.get t)}
                                 {:success false :error (.get (.failed t))})))))))

;; ---------------------------------------------------------------------------
;; Client IP
;; ---------------------------------------------------------------------------

(defn extract-client-ip
  "Extract the client's IP address as a string, passing it (or nil when unknown) to
   `inner-fn`.

   REQUIRES `pekko.http.server.remote-address-attribute = on` in the server config;
   without it Pekko never captures the peer address and the IP is always nil. Behind
   a reverse proxy the directive also honours a trusted `X-Forwarded-For` /
   `Remote-Address` per Pekko's rules.

   (extract-client-ip (fn [ip] (complete (str \"hello from \" ip))))"
  [inner-fn]
  (.extractClientIP directives
                    (reify Function
                      (apply [_ remote-address]
                        (let [^RemoteAddress ra remote-address
                              ^Optional addr (.getAddress ra)]
                          (inner-fn (when (.isPresent addr)
                                      (.getHostAddress ^InetAddress (.get addr)))))))))

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
