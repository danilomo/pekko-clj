(defproject pekko-clj "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.match "1.1.0"]
                 [com.cognitect/transit-clj "1.0.333"]
                 [org.apache.pekko/pekko-actor_3 "1.1.3"]
                 [org.apache.pekko/pekko-stream_3 "1.1.3"]
                 [org.apache.pekko/pekko-persistence_3 "1.1.3"]
                 ;; Clustering
                 [org.apache.pekko/pekko-cluster_3 "1.1.3"]
                 [org.apache.pekko/pekko-cluster-sharding_3 "1.1.3"]
                 [org.apache.pekko/pekko-cluster-tools_3 "1.1.3"]
                 ;; HTTP
                 [org.apache.pekko/pekko-http_3 "1.1.0"]
                 ;; LevelDB Java port for persistence tests
                 [org.iq80.leveldb/leveldb "0.12"]]
  :main ^:skip-aot pekko-clj.core
  :java-source-paths ["src/main/java"]
  :source-paths ["src/main/clj"]

  :test-paths ["test/clj"]
  :resource-paths ["test/resources"]
  :target-path "target/%s"
  ;; JVM options for Java 17+ LevelDB compatibility
  :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"]
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
