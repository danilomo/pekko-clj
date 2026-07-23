---
title: HTTP Setup and Routing
---
# HTTP Setup and Routing

`pekko-clj` leverages `pekko-http_3` to provide a robust HTTP server utilizing internal stream mechanics to process TCP connections without blocking threads.

It provides a Compojure-esque routing DSL structure utilizing Clojure macros to define endpoints neatly.

## Basic Setup & Server Bindings

To launch a server, we define a route and then bind a server port using `pekko-clj.http.core`.

```clojure
(ns my-app.http-server
  (:require [pekko-clj.http.core :as http]
            [pekko-clj.http.routing :as r :refer [GET POST complete routes]]
            [pekko-clj.http.response :as resp]))

;; 1. Define routes declaratively
(def my-routes
  (routes
    ;; Simple endpoint
    (GET "/hello" []
      (complete :ok "Hello, from pekko-clj HTTP!"))
    
    ;; Endpoint extracting variables directly from the path
    ;; Notice `[id]` bindings are injected into lexical scope
    (GET "/user/:id" [id]
      (complete :ok (str "Requested user ID: " id)))
      
    ;; Endpoint processing POST requests
    (POST "/submit" []
      (complete :created "Data Submitted!"))))

;; 2. Bind the server async
(def binding-future (http/bind-server sys "localhost" 8080 my-routes))

;; Note: bind-server returns a java.util.concurrent.CompletionStage<ServerBinding>;
;; `stream/await-completion` blocks for it with a millisecond timeout.
;; (let [binding (stream/await-completion binding-future 5000)]
;;   (println "Server online at:" (http/local-address binding)))
```

### Path patterns

The pattern is split on `/` — the leading slash is optional, so `"/users"` and
`"users"` are the same route. Each segment is matched against the *unmatched*
part of the request path and the route only matches if the path ends where the
pattern does (`/users/42/extra` does not match `"/users/:id"`). Because the
macros consume only their own segments, they nest inside `path-prefix`:

```clojure
(r/path-prefix "api"
  (r/path-prefix "v1"
    (routes
      (GET "users" []          (complete :ok "all users"))
      (GET "users/:id" [id]    (complete :ok (str "user " id)))
      ;; several params bind by name, in any order in the vector
      (GET "users/:id/posts/:post-id" [post-id id]
        (complete :ok (str "user " id ", post " post-id))))))
```

A request with a matching path but the wrong method gets a `405`, not a `404`.

For hand-built routes, the same capture is available as a directive —
`path-var` (last segment) and `path-prefix-var` (keep matching afterwards):

```clojure
(r/path-prefix "users"
  (r/path-prefix-var
    (fn [id]
      (r/path "posts"
        (r/method-get (complete :ok (str "posts of " id)))))))
```

## Extracting Request Information

The API provides specific helper directives to pull headers and queries dynamically from nested layers before execution reaches the inner completion logic:

```clojure
(GET "/api/search" []
  ;; Extracts the `topic` query param
  (r/param "topic" 
    (fn [topic]
      ;; Optional query parameter with a default
      (r/param-opt "limit" "10" 
        (fn [limit]
          (complete :ok (str "Searching " limit " entries for: " topic)))))))
```

Since the HTTP requests use Pekko Streams underneath to stream bytes smoothly without loading an entire file into RAM, the body payload needs to be specifically requested using `with-request-body`:

```clojure
(POST "/upload" []
  ;; Materializes the request body to a buffered string
  (r/with-request-body 
    (fn [body-str]
      (println "Received string:" body-str)
      (complete :ok "Length processed!"))))
```

## Static Content

Serve files from the classpath or the filesystem. Content types come from the
file extension, so nothing has to be declared per file. The directory forms
resolve the *still-unmatched* path, so they nest under `path-prefix`:

```clojure
(r/routes
  ;; One file
  (r/path "favicon.ico" (r/from-resource "public/favicon.ico"))
  ;; A whole tree: GET /assets/css/app.css -> classpath public/css/app.css
  (r/path-prefix "assets" (r/from-resource-directory "public"))
  ;; …or from disk (Pekko refuses to serve outside the directory)
  (r/path-prefix "files" (r/from-directory "/var/www")))
```

## Authentication

`basic-auth` handles the 401 and the `WWW-Authenticate` challenge for you. The
supplied password is deliberately not reachable — Pekko exposes only `verify`,
which compares your known secret against it in constant time:

```clojure
(r/path "admin"
  (r/basic-auth "admin area"
    (fn [user verify]
      (when-let [secret (get users user)]
        (when (verify secret) {:user user})))         ; nil rejects
    (fn [principal]
      (complete (str "hi " (:user principal))))))
```

`bearer-token` is extraction only — it hands the inner function the token from
an `Authorization: Bearer …` header, or nil when the header is absent or uses
another scheme, and the route decides what that means.

## Marshalling: strings are values, not pre-encoded bodies

`->json` / `->edn` (and therefore `resp/json`, `complete-json`, …) encode every
value, strings included:

```clojure
(complete-json "hello")                        ;; => "hello"  (a JSON string)
(complete-json (marshal/raw-body "{\"a\":1}")) ;; => {"a":1}  (verbatim)
```

Strings used to be passed through unchanged, on the theory that a string must
already be encoded. That made `(resp/json "hello")` emit the bare characters
`hello` — not valid JSON, with nothing to say so. Pre-encoded bodies are now
explicit via `marshal/raw-body`.

## Contrast with Scala (Pekko HTTP)

The original Scala `Route` DSL relies on a deeply nested sequence of function combinators (`~`) mapping into execution blocks.

```scala
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.Http

val route =
  concat(
    path("hello") {
      get {
        complete("Hello, from Scala HTTP!")
      }
    },
    path("user" / Segment) { id =>
      get {
        complete(s"Requested user ID: $id")
      }
    }
  )

val bindingFuture = Http().newServerAt("localhost", 8080).bind(route)
```

**Key Differences:**
1. **Macros vs Extractor Chaining**: In Scala, variables extracted from the URL like `path("user" / Segment)` necessitate passing an anonymous function ` { id => }` downwards. `pekko-clj` streamlines this radically by wrapping common patterns into structural macros `(GET "/user/:id" [id] ...)`. 
2. **Body Access**: Direct `entity(as[String])` unmarshalling inside Scala invokes type classes. Clojure achieves explicit stream manifestation via `with-request-body` combined with completion wrappers to bridge the functional side-effects seamlessly.
