(ns pekko-clj.http.response
  "Response builders and content type helpers for Pekko HTTP.

   Provides idiomatic Clojure functions for creating HTTP responses
   with proper content types and status codes."
  (:import [org.apache.pekko.http.javadsl.model HttpResponse StatusCodes StatusCode
                                                  ContentTypes ContentType HttpEntities
                                                  ResponseEntity]
           [org.apache.pekko.http.scaladsl.model HttpEntity$Strict]
           [org.apache.pekko.util ByteString]
           [org.apache.pekko.stream.javadsl Source]))

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
  "Convert a status keyword or integer to a StatusCode."
  [status]
  (cond
    (instance? StatusCode status) status
    (keyword? status) (or (get status-codes status)
                          (throw (ex-info (str "Unknown status code: " status)
                                          {:status status})))
    (integer? status) (StatusCodes/custom (int status) "" "" false false)
    :else (throw (ex-info "Invalid status type" {:status status}))))

;; ---------------------------------------------------------------------------
;; Content Types
;; ---------------------------------------------------------------------------

(def content-types
  "Map of content type keywords to Pekko ContentType objects."
  {:json       ContentTypes/APPLICATION_JSON
   :html       ContentTypes/TEXT_HTML_UTF8
   :plain      ContentTypes/TEXT_PLAIN_UTF8
   :xml        ContentTypes/TEXT_XML_UTF8
   :csv        ContentTypes/TEXT_CSV_UTF8
   :form       ContentTypes/APPLICATION_X_WWW_FORM_URLENCODED
   :binary     ContentTypes/APPLICATION_OCTET_STREAM})

(defn ->content-type
  "Convert a content type keyword to a ContentType."
  [ct]
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
  (let [ct (->content-type content-type)]
    (if (string? content)
      (HttpEntities/create ct ^String content)
      (HttpEntities/create ct ^bytes content))))

(defn json
  "Create a JSON entity from a string or data structure.
   If given a map/vector, converts to JSON string using pr-str.
   For production, use a proper JSON library like cheshire."
  [data]
  (let [content (if (string? data)
                  data
                  (pr-str data))]
    (entity content :json)))

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
    (HttpEntities/create ct source)))

;; ---------------------------------------------------------------------------
;; Response Builders
;; ---------------------------------------------------------------------------

(defn response
  "Create an HTTP response.

   Arities:
   (response status body) - response with status and body
   (response status headers body) - response with status, headers map, and body

   status: keyword (:ok, :not-found, etc.) or integer
   headers: map of header names to values (not yet implemented, reserved)
   body: HttpEntity, string, or nil"
  ([status body]
   (let [sc (->status-code status)
         ent (cond
               (nil? body) ""
               (instance? ResponseEntity body) body
               (instance? HttpEntity$Strict body) body
               (string? body) body
               :else (str body))]
     (-> (HttpResponse/create)
         (.withStatus sc)
         (.withEntity ^String ent))))
  ([status headers body]
   ;; For now, ignore headers (would require building HttpHeader list)
   (response status body)))

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
   ;; Redirect responses typically include a Location header
   ;; For now, return basic redirect response
   (response status (text (str "Redirecting to " url)))))
