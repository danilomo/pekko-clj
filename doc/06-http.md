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

;; Note: You can retrieve port/address info
;; (let [binding (Await/result binding-future ...)]
;;   (println "Server online at:" (http/local-address binding)))
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
