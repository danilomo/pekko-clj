(defproject pekko-clj "0.1.0-SNAPSHOT"
  :description "An ergonomic Clojure wrapper for Apache Pekko: actors, event sourcing, streams, clustering, sharding, singletons, routing, and HTTP."
  :url "https://github.com/danilomo/pekko-clj"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.match "1.1.0"]
                 [com.cognitect/transit-clj "1.0.333"]
                 ;; JSON marshalling for pekko-clj.http
                 [cheshire "5.13.0"]
                 [org.apache.pekko/pekko-actor_3 "1.6.0"]
                 [org.apache.pekko/pekko-stream_3 "1.6.0"]
                 [org.apache.pekko/pekko-persistence_3 "1.6.0"]
                 [org.apache.pekko/pekko-persistence-query_3 "1.6.0"]
                 ;; Clustering
                 [org.apache.pekko/pekko-cluster_3 "1.6.0"]
                 [org.apache.pekko/pekko-cluster-sharding_3 "1.6.0"]
                 [org.apache.pekko/pekko-cluster-tools_3 "1.6.0"]
                 ;; CRDTs (pekko-clj.cluster.ddata) — also a pekko-cluster transitive
                 [org.apache.pekko/pekko-distributed-data_3 "1.6.0"]
                 ;; Typed sharding — only for ShardedDaemonProcess, which has no
                 ;; classic API (see pekko-clj.cluster.daemon)
                 [org.apache.pekko/pekko-cluster-sharding-typed_3 "1.6.0"]
                 ;; HTTP
                 [org.apache.pekko/pekko-http_3 "1.3.0"]
                 ;; TestKit — powers the pekko-clj.test companion namespace
                 [org.apache.pekko/pekko-testkit_3 "1.6.0"]
                 [org.apache.pekko/pekko-stream-testkit_3 "1.6.0"]]
  :java-source-paths ["src/main/java"]
  :source-paths ["src/main/clj"]

  :test-paths ["test/clj"]
  ;; Ships resources/clj-kondo.exports, which teaches clj-kondo to read `defactor`
  ;; in downstream projects. The HOCON test configs live in the :test/:dev profiles
  ;; so they stay out of the published jar.
  :resource-paths ["resources"]
  :target-path "target/%s"
  ;; JVM options for Java 17+ LevelDB compatibility
  :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"]

  ;; Interop in src/ is fully hinted; `lein check` must stay free of reflection
  ;; warnings (CI greps for them). Set here rather than per-namespace: a
  ;; (set! *warn-on-reflection* true) leaks into whatever compiles next in the
  ;; same JVM, so which namespaces got checked depended on load order.
  :global-vars {*warn-on-reflection* true}

  :plugins [[com.github.clj-kondo/lein-clj-kondo "2026.05.25"]
            ;; dev.weavejester/… is the maintained line; the old lein-cljfmt group
            ;; stopped at 0.9.2, before :extra-indents existed.
            [dev.weavejester/lein-cljfmt "0.16.5"]]

  ;; `lein lint` — static analysis plus a formatting check; both are CI-ready and
  ;; fail the build on any finding. `lein lint-fix` applies what can be automated.
  :aliases {"lint"     ["do" ["clj-kondo"] ["cljfmt" "check"]]
            "lint-fix" ["cljfmt" "fix"]}

  ;; The defactor / defactor-persistent clauses are body forms, not function calls:
  ;; without these, cljfmt would align each clause body under its pattern instead of
  ;; indenting it two spaces. [:block n] keeps the first n args on the head's line
  ;; (the pattern, the binding vector) and indents the body.
  :cljfmt {:remove-consecutive-blank-lines? false
           :extra-indents {init                      [[:block 1]]
                           handle                    [[:block 1]]
                           command                   [[:block 1]]
                           event                     [[:block 1]]
                           tagger                    [[:block 1]]
                           on-error                  [[:block 1]]
                           on-restart                [[:block 1]]
                           on-recovery-complete      [[:block 1]]
                           on-stop                   [[:block 0]]
                           supervision               [[:block 0]]
                           ;; Compojure-style route macros: path and bindings on
                           ;; the head's line, handler body indented.
                           GET                       [[:block 2]]
                           POST                      [[:block 2]]
                           PUT                       [[:block 2]]
                           DELETE                    [[:block 2]]
                           PATCH                     [[:block 2]]

                           ;; The routing combinators are ordinary functions, but
                           ;; they nest as a tree-shaped DSL. Aligning arguments
                           ;; under the opening paren pushes deeply nested routes
                           ;; off the right margin, so indent their children as a
                           ;; body instead.
                           pekko-clj.http.routing/routes             [[:block 0]]
                           pekko-clj.http.routing/path               [[:block 1]]
                           pekko-clj.http.routing/path-prefix        [[:block 1]]
                           pekko-clj.http.routing/path-var           [[:block 0]]
                           pekko-clj.http.routing/path-prefix-var    [[:block 0]]
                           pekko-clj.http.routing/path-end           [[:block 0]]
                           pekko-clj.http.routing/method-get         [[:block 0]]
                           pekko-clj.http.routing/method-post        [[:block 0]]
                           pekko-clj.http.routing/method-put         [[:block 0]]
                           pekko-clj.http.routing/method-delete      [[:block 0]]
                           pekko-clj.http.routing/method-head        [[:block 0]]
                           pekko-clj.http.routing/method-options     [[:block 0]]
                           pekko-clj.http.routing/method-patch       [[:block 0]]
                           pekko-clj.http.routing/param              [[:block 1]]
                           pekko-clj.http.routing/params             [[:block 1]]
                           pekko-clj.http.routing/handle-rejections  [[:block 1]]
                           pekko-clj.http.routing/handle-exceptions  [[:block 1]]
                           pekko-clj.http.routing/with-json-body     [[:block 1]]
                           pekko-clj.http.routing/with-edn-body      [[:block 1]]
                           pekko-clj.http.routing/with-parsed-body   [[:block 1]]

                           ;; (thrown-with-msg? Class #"re" body...) — the class and
                           ;; regex are the assertion, the rest is the body under test.
                           thrown-with-msg?          [[:block 2]]
                           thrown?                   [[:block 1]]}}

  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}
             ;; Test code does plenty of ad-hoc interop where reflection is
             ;; irrelevant; warning there would bury the run in noise and dilute
             ;; the signal from src/. LevelDB is a test-only journal/snapshot
             ;; store (see test/resources/persistence-test.conf) — production
             ;; users supply their own journal plugin (see README), so it has
             ;; no place in the published jar's main :dependencies.
             :dev  {:resource-paths ["test/resources"]
                    :dependencies [[org.iq80.leveldb/leveldb "0.12"]]
                    :global-vars {*warn-on-reflection* false}}
             :test {:resource-paths ["test/resources"]
                    :dependencies [[org.iq80.leveldb/leveldb "0.12"]]
                    :global-vars {*warn-on-reflection* false}}})
