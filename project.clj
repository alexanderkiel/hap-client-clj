(defproject org.clojars.akiel/hap-client-clj "0.2-SNAPSHOT"
  :description "A Clojure(Script) HAP client library."
  :url "https://github.com/alexanderkiel/hap-client-clj"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.48"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/tools.logging "0.3.1"]
                 [prismatic/plumbing "0.4.3"]
                 [http-kit "2.1.18"]
                 [com.cognitect/transit-clj "0.8.281"]
                 [com.cognitect/transit-cljs "0.8.225"]
                 [org.clojars.akiel/transit-schema "0.2"]]

  :profiles {:dev {:dependencies [[http-kit.fake "0.2.1"]]}})
