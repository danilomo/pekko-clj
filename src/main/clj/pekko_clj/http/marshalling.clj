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

   Keys are keywordized when reading JSON (`json->` takes a flag to opt out).

   A Clojure string is encoded like any other value — `(->json \"hi\")` is the JSON
   string `\"hi\"`, not the bare characters `hi`. To serve a body you have already
   encoded, wrap it in `raw-body`."
  (:require [cheshire.core :as cheshire]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [org.apache.pekko.http.javadsl.model ContentType HttpCharsets MediaTypes]))

(def edn-content-type
  "ContentType for application/edn (UTF-8)."
  ^ContentType (.toContentType (MediaTypes/applicationWithFixedCharset
                                "edn" HttpCharsets/UTF_8 (into-array String ["edn"]))))

;; ---------------------------------------------------------------------------
;; Pre-encoded bodies
;; ---------------------------------------------------------------------------

(defn raw-body
  "Mark `s` as an already-encoded body that `->json` / `->edn` must emit verbatim.

   `->json` and `->edn` used to pass *every* string through unchanged, on the
   theory that a string must already be encoded. That made it impossible to serve
   a Clojure string as a JSON string value: `(->json \"hello\")` emitted the bare
   characters `hello`, which is not valid JSON, and no error said so. Encoding is
   now uniform and pre-encoded bodies are explicit:

     (->json {:a 1})                    ;; => \"{\\\"a\\\":1}\"
     (->json \"hello\")                   ;; => \"\\\"hello\\\"\"    (a JSON string)
     (->json (raw-body \"{\\\"a\\\":1}\"))    ;; => \"{\\\"a\\\":1}\"   (verbatim)

   The marker is a plain map with a namespaced key, so nothing about it depends on
   a type surviving serialization."
  [s]
  {::raw (str s)})

(defn raw-body?
  "True for a value produced by `raw-body`."
  [x]
  (and (map? x) (contains? x ::raw)))

;; ---------------------------------------------------------------------------
;; JSON
;; ---------------------------------------------------------------------------

(defn ->json
  "Encode Clojure data as a JSON string.

   Every value is encoded, strings included — `(->json \"hi\")` is `\"hi\"` with the
   quotes. Wrap an already-encoded body in `raw-body` to emit it verbatim."
  ^String [data]
  (if (raw-body? data) (::raw data) (cheshire/generate-string data)))

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
  "Encode Clojure data as an EDN string.

   Every value is encoded, strings included — `(->edn \"hi\")` is `\"hi\"` with the
   quotes. Wrap an already-encoded body in `raw-body` to emit it verbatim."
  ^String [data]
  (if (raw-body? data) (::raw data) (pr-str data)))

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
