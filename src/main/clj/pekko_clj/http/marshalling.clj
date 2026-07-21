(ns pekko-clj.http.marshalling
  "JSON and EDN marshalling for HTTP entities.

   A thin layer between Clojure data and request/response bodies: JSON via
   Cheshire, EDN via `clojure.edn` (read is always safe — no eval, no arbitrary
   record construction).

   Used by `pekko-clj.http.response` (`json`, `edn` entity builders) and
   `pekko-clj.http.routing` (`with-json-body`, `with-edn-body`, `complete-json`,
   `complete-edn`), but also usable directly:

     (->json {:a 1})            ;; => \"{\\\"a\\\":1}\"
     (json-> \"{\\\"a\\\":1}\")     ;; => {:a 1}
     (unmarshal \"application/json; charset=UTF-8\" body)

   Keys are keywordized when reading JSON (`json->` takes a flag to opt out)."
  (:require [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [org.apache.pekko.http.javadsl.model ContentType HttpCharsets MediaTypes]))

(set! *warn-on-reflection* true)

(def edn-content-type
  "ContentType for application/edn (UTF-8)."
  ^ContentType (.toContentType (MediaTypes/applicationWithFixedCharset
                                "edn" HttpCharsets/UTF_8 (into-array String ["edn"]))))

;; ---------------------------------------------------------------------------
;; JSON
;; ---------------------------------------------------------------------------

(defn ->json
  "Encode Clojure data as a JSON string. Strings are passed through unchanged, so
   an already-encoded body is never double-encoded."
  ^String [data]
  (if (string? data) data (cheshire/generate-string data)))

(defn json->
  "Parse a JSON string into Clojure data. Object keys become keywords unless
   `keywordize?` is false."
  ([^String s] (json-> s true))
  ([^String s keywordize?]
   (cheshire/parse-string s (boolean keywordize?))))

;; ---------------------------------------------------------------------------
;; EDN
;; ---------------------------------------------------------------------------

(defn ->edn
  "Encode Clojure data as an EDN string. Strings are passed through unchanged."
  ^String [data]
  (if (string? data) data (pr-str data)))

(defn edn->
  "Parse an EDN string into Clojure data using `clojure.edn/read-string` (safe:
   no code evaluation). `opts` is passed to `clojure.edn/read-string`."
  ([^String s] (edn/read-string s))
  ([opts ^String s] (edn/read-string opts s)))

;; ---------------------------------------------------------------------------
;; Content-type dispatch
;; ---------------------------------------------------------------------------

(defn unmarshal
  "Parse a body string according to a content type (a ContentType, a
   `\"application/json; charset=UTF-8\"` string, or :json/:edn).

   JSON and EDN are decoded; anything else is returned unchanged, so handlers can
   fall back to the raw string."
  [content-type ^String body]
  (let [ct (str/lower-case (str (if (keyword? content-type) (name content-type) content-type)))]
    (cond
      (str/includes? ct "json") (json-> body)
      (str/includes? ct "edn") (edn-> body)
      :else body)))

(set! *warn-on-reflection* false)
