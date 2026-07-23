(ns pekko-clj.http.marshalling-test
  "Tests for N7's JSON/EDN marshalling layer and the entity builders on top of it."
  (:require [clojure.test :refer [deftest is]]
            [pekko-clj.http.marshalling :as marshal]
            [pekko-clj.http.response :as resp])
  (:import [org.apache.pekko.http.javadsl.model ContentType ContentTypes]))

;; ---------------------------------------------------------------------------
;; JSON
;; ---------------------------------------------------------------------------

(deftest json-round-trip-test
  (let [data {:name "ada" :ids [1 2 3] :nested {:ok true} :missing nil}]
    (is (= data (marshal/json-> (marshal/->json data)))))
  ;; Keys are keywordized by default, opt out with the flag
  (is (= {:a 1} (marshal/json-> "{\"a\":1}")))
  (is (= {"a" 1} (marshal/json-> "{\"a\":1}" false))))

(deftest json-encodes-real-json-test
  ;; Regression: `->json`/`resp/json` used to render EDN via pr-str, so a map came
  ;; out as {:a 1} under an application/json content type.
  (is (= "{\"a\":1}" (marshal/->json {:a 1})))
  (is (= "[1,\"two\"]" (marshal/->json [1 "two"])))
  ;; N15: a bare string is a JSON *value*, so it is encoded like anything else —
  ;; the old passthrough made `(->json "hello")` emit invalid JSON. A pre-encoded
  ;; body says so explicitly with raw-body.
  (is (= "\"hello\"" (marshal/->json "hello")))
  (is (= "hello" (marshal/json-> (marshal/->json "hello"))) "and it round trips")
  (is (= "{\"a\":1}" (marshal/->json (marshal/raw-body "{\"a\":1}"))))
  (is (marshal/raw-body? (marshal/raw-body "x")))
  (is (not (marshal/raw-body? {:raw "x"})) "an unqualified :raw key is not a marker"))

(deftest json-malformed-throws-test
  (is (thrown? Exception (marshal/json-> "{not json"))))

;; ---------------------------------------------------------------------------
;; EDN
;; ---------------------------------------------------------------------------

(deftest edn-round-trip-test
  (let [data {:name "ada" :ids [1 2 3] :set #{:x} :ratio 1/3}]
    (is (= data (marshal/edn-> (marshal/->edn data)))))
  ;; N15: same rule as JSON — strings are encoded, raw-body opts out.
  (is (= "\"hi\"" (marshal/->edn "hi")))
  (is (= "{:a 1}" (marshal/->edn (marshal/raw-body "{:a 1}")))))

(deftest edn-read-is-safe-test
  ;; clojure.edn/read-string does not eval, so reader-eval payloads are rejected
  ;; rather than executed.
  (is (thrown? Exception (marshal/edn-> "#=(clojure.java.io/delete-file \"x\")"))))

;; ---------------------------------------------------------------------------
;; Content-type dispatch
;; ---------------------------------------------------------------------------

(deftest unmarshal-by-content-type-test
  (is (= {:a 1} (marshal/unmarshal "application/json; charset=UTF-8" "{\"a\":1}")))
  (is (= {:a 1} (marshal/unmarshal :json "{\"a\":1}")))
  (is (= {:a 1} (marshal/unmarshal "application/edn" "{:a 1}")))
  (is (= "plain body" (marshal/unmarshal "text/plain; charset=UTF-8" "plain body"))
      "unknown content types pass the body through untouched")
  (is (= "x" (marshal/unmarshal nil "x"))))

(deftest edn-content-type-test
  (is (instance? ContentType marshal/edn-content-type))
  (is (= "application/edn" (str (.mediaType ^ContentType marshal/edn-content-type)))))

;; ---------------------------------------------------------------------------
;; Entity builders
;; ---------------------------------------------------------------------------

(defn- entity-string [entity]
  (.utf8String (.getData entity)))

(deftest json-entity-test
  (let [e (resp/json {:name "ada"})]
    (is (= "{\"name\":\"ada\"}" (entity-string e)))
    (is (= ContentTypes/APPLICATION_JSON (.getContentType e))))
  (is (= "{\"raw\":true}" (entity-string (resp/json (marshal/raw-body "{\"raw\":true}"))))))

(deftest edn-entity-test
  (let [e (resp/edn {:name "ada" :ids [1 2]})]
    (is (= {:name "ada" :ids [1 2]} (marshal/edn-> (entity-string e))))
    (is (= "application/edn" (str (.mediaType (.getContentType e)))))))
