(defproject pekko-clj "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [org.clojure/core.match "1.1.0"]
                 [com.cognitect/transit-clj "1.0.333"]
                 [org.apache.pekko/pekko-actor_3 "1.1.3"]]
  :main ^:skip-aot pekko-clj.core
  :java-source-paths ["src/main/java"]
  :source-paths ["src/main/clj"]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})
