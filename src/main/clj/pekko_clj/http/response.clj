(ns pekko-clj.http.response
  "Response builders and content type helpers for Pekko HTTP.

   Provides idiomatic Clojure functions for creating HTTP responses
   with proper content types and status codes."
  (:require [pekko-clj.http.marshalling :as marshal])
  (:import [org.apache.pekko.http.javadsl.model HttpResponse StatusCodes StatusCode
            ContentTypes ContentType ContentType$NonBinary HttpEntities
            ResponseEntity]
           [org.apache.pekko.stream.javadsl Source]
           [org.apache.pekko.http.javadsl.model.headers RawHeader Location]))

;; ---------------------------------------------------------------------------
;; Status Codes
;; ---------------------------------------------------------------------------

(def status-codes
  "Map of keyword status codes to Pekko StatusCode objects."
  {:ok                    StatusCodes/OK
   :created               StatusCodes/CREATED
   :accepted              StatusCodes/ACCEPTED
   :no-content            StatusCodes/NO_CONTENT
   :moved-permanently     StatusCodes/MOVED_PERMANENTLY
   :found                 StatusCodes/FOUND
   :see-other             StatusCodes/SEE_OTHER
   :not-modified          StatusCodes/NOT_MODIFIED
   :temporary-redirect    StatusCodes/TEMPORARY_REDIRECT
   :permanent-redirect    StatusCodes/PERMANENT_REDIRECT
   :bad-request           StatusCodes/BAD_REQUEST
   :unauthorized          StatusCodes/UNAUTHORIZED
   :forbidden             StatusCodes/FORBIDDEN
   :not-found             StatusCodes/NOT_FOUND
   :method-not-allowed    StatusCodes/METHOD_NOT_ALLOWED
   :conflict              StatusCodes/CONFLICT
   :gone                  StatusCodes/GONE
   :unprocessable-entity  StatusCodes/UNPROCESSABLE_ENTITY
   :too-many-requests     StatusCodes/TOO_MANY_REQUESTS
   :internal-server-error StatusCodes/INTERNAL_SERVER_ERROR
   :not-implemented       StatusCodes/NOT_IMPLEMENTED
   :bad-gateway           StatusCodes/BAD_GATEWAY
   :service-unavailable   StatusCodes/SERVICE_UNAVAILABLE
   :gateway-timeout       StatusCodes/GATEWAY_TIMEOUT})

(defn ->status-code
  "Convert a status keyword or integer to a StatusCode.

   Keywords are the documented primary form (see `status-codes`). An integer
   resolves to the real registered StatusCode when Pekko knows it (so 201
   behaves exactly like :created — correct reason, isSuccess and allowsEntity
   flags); a genuinely unregistered code falls back to a custom StatusCode with
   sensible defaults (never an empty reason / isSuccess=false / allowsEntity=false,
   which renders a 500 and drops the body)."
  ^StatusCode [status]
  (cond
    (instance? StatusCode status) status
    (keyword? status) (or (get status-codes status)
                          (throw (ex-info (str "Unknown status code: " status)
                                          {:status status})))
    (integer? status) (let [n (int status)
                            registered (StatusCodes/lookup n)]
                        (if (.isPresent registered)
                          (.get registered)
                          (StatusCodes/custom n "Custom" "Custom")))
    :else (throw (ex-info "Invalid status type" {:status status}))))

;; ---------------------------------------------------------------------------
;; Content Types
;; ---------------------------------------------------------------------------

(def content-types
  "Map of content type keywords to Pekko ContentType objects."
  {:json       ContentTypes/APPLICATION_JSON
   :edn        marshal/edn-content-type
   :html       ContentTypes/TEXT_HTML_UTF8
   :plain      ContentTypes/TEXT_PLAIN_UTF8
   :xml        ContentTypes/TEXT_XML_UTF8
   :csv        ContentTypes/TEXT_CSV_UTF8
   :form       ContentTypes/APPLICATION_X_WWW_FORM_URLENCODED
   :binary     ContentTypes/APPLICATION_OCTET_STREAM})

(defn ->content-type
  "Convert a content type keyword to a ContentType."
  ^ContentType [ct]
  (cond
    (instance? ContentType ct) ct
    (keyword? ct) (or (get content-types ct)
                      (throw (ex-info (str "Unknown content type: " ct)
                                      {:content-type ct})))
    :else ct))

;; ---------------------------------------------------------------------------
;; Entity Builders
;; ---------------------------------------------------------------------------

(defn entity
  "Create an HTTP entity from content with a content type.
   content: string or byte array
   content-type: keyword or ContentType"
  [content content-type]
  ;; The (ContentType, String) overload is declared on ContentType$NonBinary — every
  ;; content type here is NonBinary except :binary, which takes the byte[] overload.
  (let [ct (->content-type content-type)]
    (if (string? content)
      (HttpEntities/create ^ContentType$NonBinary ct ^String content)
      (HttpEntities/create ct ^bytes content))))

(defn json
  "Create a JSON entity from Clojure data (encoded with Cheshire). A string is a
   JSON *value*, so it is encoded and comes back quoted — to emit a pre-encoded
   JSON body verbatim, wrap it with `pekko-clj.http.marshalling/raw-body` (N15).

     (json {:name \"ada\" :ids [1 2]})  ;; => {\"name\":\"ada\",\"ids\":[1,2]}
     (json \"hi\")                       ;; => \"hi\"  (a quoted JSON string)"
  [data]
  (entity (marshal/->json data) :json))

(defn edn
  "Create an application/edn entity from Clojure data (rendered with pr-str). A
   string is rendered as an EDN string literal, so to emit a pre-rendered EDN body
   verbatim wrap it with `pekko-clj.http.marshalling/raw-body` (N15)."
  [data]
  (entity (marshal/->edn data) :edn))

(defn html
  "Create an HTML entity from a string."
  [content]
  (entity content :html))

(defn text
  "Create a plain text entity from a string."
  [content]
  (entity content :plain))

(defn xml
  "Create an XML entity from a string."
  [content]
  (entity content :xml))

(defn stream
  "Create a streaming entity from a Pekko Source.
   source: a pekko-clj.stream Source of ByteString
   content-type: keyword or ContentType"
  [source content-type]
  (let [ct (->content-type content-type)]
    (HttpEntities/create ct ^Source source)))

;; ---------------------------------------------------------------------------
;; Response Builders
;; ---------------------------------------------------------------------------

(defn- ->headers
  "Build a sequence of HttpHeader (RawHeader) from a map of name -> value.
   Names may be keywords or strings; values are coerced with str."
  [headers]
  (map (fn [[k v]] (RawHeader/create (name k) (str v))) headers))

(defn response
  "Create an HTTP response.

   Arities:
   (response status body) - response with status and body
   (response status headers body) - response with status, headers map, and body

   status: keyword (:ok, :not-found, etc.) or integer
   headers: map of header names (keyword or string) to values, added as raw headers
   body: HttpEntity, string, or nil"
  ([status body]
   ;; Each branch picks its own withEntity overload. A single hinted `ent` would
   ;; not do: an entity body needs withEntity(ResponseEntity) while a string needs
   ;; withEntity(String), and hinting one would mis-dispatch the other.
   ;; scaladsl HttpEntity$Strict implements javadsl ResponseEntity, so the
   ;; ResponseEntity branch already covers it.
   (let [sc (->status-code status)
         ^HttpResponse resp (.withStatus (HttpResponse/create) ^StatusCode sc)]
     (cond
       (nil? body)                     (.withEntity resp "")
       (instance? ResponseEntity body) (.withEntity resp ^ResponseEntity body)
       (string? body)                  (.withEntity resp ^String body)
       :else                           (.withEntity resp ^String (str body)))))
  ([status headers body]
   (let [^HttpResponse resp (response status body)]
     (if (seq headers)
       (.addHeaders resp (java.util.ArrayList. ^java.util.Collection (->headers headers)))
       resp))))

(defn ok
  "Create an OK (200) response with the given body."
  [body]
  (response :ok body))

(defn created
  "Create a Created (201) response with the given body."
  [body]
  (response :created body))

(defn accepted
  "Create an Accepted (202) response with the given body."
  [body]
  (response :accepted body))

(defn no-content
  "Create a No Content (204) response."
  []
  (response :no-content nil))

(defn bad-request
  "Create a Bad Request (400) response with the given body."
  [body]
  (response :bad-request body))

(defn unauthorized
  "Create an Unauthorized (401) response with the given body."
  [body]
  (response :unauthorized body))

(defn forbidden
  "Create a Forbidden (403) response with the given body."
  [body]
  (response :forbidden body))

(defn not-found
  "Create a Not Found (404) response with the given body."
  [body]
  (response :not-found body))

(defn internal-server-error
  "Create an Internal Server Error (500) response with the given body."
  [body]
  (response :internal-server-error body))

(defn redirect
  "Create a redirect response to the given URL.
   status: :moved-permanently (301), :found (302), :see-other (303),
           :temporary-redirect (307), or :permanent-redirect (308)"
  ([url]
   (redirect url :found))
  ([url status]
   (let [^HttpResponse resp (response status (text (str "Redirecting to " url)))]
     (.addHeader resp (Location/create ^String url)))))
