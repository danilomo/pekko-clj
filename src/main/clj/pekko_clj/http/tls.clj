(ns pekko-clj.http.tls
  "TLS/HTTPS support for Pekko HTTP.

   Builds a `javax.net.ssl.SSLContext` from keystore/truststore options and wraps
   it as a Pekko `HttpsConnectionContext` for either the server side
   (`https-server-context`, passed to `pekko-clj.http.core/bind-server` as
   `{:https …}`) or the client side (`https-client-context`, passed to
   `pekko-clj.http.client` requests as `{:https-context …}` or installed as the
   system default with `set-default-client-https-context!`)."
  (:require [clojure.java.io :as io])
  (:import [org.apache.pekko.http.javadsl ConnectionContext HttpsConnectionContext]
           [javax.net.ssl SSLContext KeyManagerFactory TrustManagerFactory]
           [java.security KeyStore]))

(defn- ->chars
  "Coerce a password to a char[]. A String becomes its characters; a char[]
   passes through; nil stays nil (KeyStore/load tolerates a null password)."
  ^chars [password]
  (cond
    (nil? password) nil
    (string? password) (.toCharArray ^String password)
    :else password))

(defn- load-keystore
  "Load a KeyStore. `source` may be an already-built KeyStore (returned as-is), or
   anything `clojure.java.io/input-stream` accepts (a path String, File, URL, or
   InputStream — e.g. `(io/resource \"certs/server.p12\")`)."
  ^KeyStore [source ^String type ^chars password]
  (if (instance? KeyStore source)
    source
    (let [ks (KeyStore/getInstance type)]
      (with-open [in (io/input-stream source)]
        (.load ks in password))
      ks)))

(defn ssl-context
  "Build a `javax.net.ssl.SSLContext` from keystore/truststore options.

   Options:
   - :keystore            the server identity store (a KeyStore, or a path/File/URL/
                          InputStream). When present, its key managers are installed.
   - :keystore-password   String or char[] guarding the store (default nil).
   - :key-password        String or char[] guarding the private key
                          (default: :keystore-password).
   - :keystore-type       \"PKCS12\" (default) or \"JKS\".
   - :truststore          trusted certificates (same accepted shapes as :keystore).
                          When present, its trust managers are installed.
   - :truststore-password String or char[] (default nil).
   - :truststore-type     \"PKCS12\" (default) or \"JKS\".
   - :protocol            SSLContext protocol, \"TLS\" (default).

   At least one of :keystore / :truststore is required. A server context usually
   needs only :keystore; a client trusting a self-signed server needs :truststore.

   Returns an initialized SSLContext."
  ^SSLContext [{:keys [keystore keystore-password key-password keystore-type
                       truststore truststore-password truststore-type protocol]
                :or {keystore-type "PKCS12" truststore-type "PKCS12" protocol "TLS"}}]
  (when-not (or keystore truststore)
    (throw (IllegalArgumentException.
            "ssl-context needs at least one of :keystore or :truststore")))
  (let [^"[Ljavax.net.ssl.KeyManager;" kms
        (when keystore
          (let [ks (load-keystore keystore keystore-type (->chars keystore-password))
                kmf (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))]
            (.init kmf ks (->chars (or key-password keystore-password)))
            (.getKeyManagers kmf)))
        ^"[Ljavax.net.ssl.TrustManager;" tms
        (when truststore
          (let [ts (load-keystore truststore truststore-type (->chars truststore-password))
                tmf (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))]
            (.init tmf ts)
            (.getTrustManagers tmf)))
        ctx (SSLContext/getInstance protocol)]
    (.init ctx kms tms nil)
    ctx))

(defn- ->ssl-context
  "An SSLContext from either an SSLContext or an `ssl-context` options map."
  ^SSLContext [ctx-or-opts]
  (if (map? ctx-or-opts) (ssl-context ctx-or-opts) ctx-or-opts))

(defn https-server-context
  "A server-side `HttpsConnectionContext`. Pass either an `SSLContext` or an
   `ssl-context` options map (built for you). Hand the result to
   `pekko-clj.http.core/bind-server` as `{:https …}`."
  ^HttpsConnectionContext [ctx-or-opts]
  (let [^SSLContext ctx (->ssl-context ctx-or-opts)]
    (ConnectionContext/httpsServer ctx)))

(defn https-client-context
  "A client-side `HttpsConnectionContext`. Pass either an `SSLContext` or an
   `ssl-context` options map. Use it per request (`{:https-context …}`) or install
   it as the default with `set-default-client-https-context!`."
  ^HttpsConnectionContext [ctx-or-opts]
  (let [^SSLContext ctx (->ssl-context ctx-or-opts)]
    (ConnectionContext/httpsClient ctx)))
