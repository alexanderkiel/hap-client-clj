(defproject org.clojars.akiel/hap-client-clj "0.7"
  :description "A Clojure(Script) HAP client library."
  :url "https://github.com/alexanderkiel/hap-client-clj"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :min-lein-version "2.5.2"
  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/tools.logging "0.3.1"]
                 [prismatic/schema "1.1.7"]
                 [http-kit "2.1.18"]
                 [com.cognitect/transit-clj "0.8.300"]
                 [com.cognitect/transit-cljs "0.8.243"]
                 [org.clojars.akiel/transit-schema "0.4"]]

  :profiles {:dev
             {:dependencies
              [[org.clojure/clojurescript "1.9.946"]
               [http-kit.fake "0.2.1"]]}})
