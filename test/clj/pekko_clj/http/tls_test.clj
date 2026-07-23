(ns pekko-clj.http.tls-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [pekko-clj.http.tls :as tls])
  (:import [javax.net.ssl SSLContext]
           [org.apache.pekko.http.javadsl HttpsConnectionContext]))

(deftest ssl-context-requires-a-store-test
  (testing "ssl-context with neither keystore nor truststore is rejected"
    (is (thrown-with-msg? IllegalArgumentException #":keystore or :truststore"
          (tls/ssl-context {})))))

(deftest ssl-context-builds-from-keystore-test
  (testing "a keystore yields an initialized SSLContext (server identity)"
    (is (instance? SSLContext
                   (tls/ssl-context {:keystore (io/resource "certs/server.p12")
                                     :keystore-password "changeit"})))))

(deftest ssl-context-builds-from-truststore-test
  (testing "a truststore yields an initialized SSLContext (client trust)"
    (is (instance? SSLContext
                   (tls/ssl-context {:truststore (io/resource "certs/truststore.p12")
                                     :truststore-password "changeit"})))))

(deftest https-contexts-build-from-opts-or-context-test
  (testing "https-server/client-context accept an opts map or a ready SSLContext"
    (let [server-ctx (tls/ssl-context {:keystore (io/resource "certs/server.p12")
                                       :keystore-password "changeit"})]
      ;; from an opts map
      (is (instance? HttpsConnectionContext
                     (tls/https-server-context {:keystore (io/resource "certs/server.p12")
                                                :keystore-password "changeit"})))
      ;; from an already-built SSLContext
      (is (instance? HttpsConnectionContext (tls/https-server-context server-ctx)))
      (is (instance? HttpsConnectionContext
                     (tls/https-client-context {:truststore (io/resource "certs/truststore.p12")
                                                :truststore-password "changeit"}))))))
