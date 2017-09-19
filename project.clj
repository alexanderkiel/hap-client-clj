(defproject org.clojars.akiel/hap-client-clj "0.5.1"
  :description "A Clojure(Script) HAP client library."
  :url "https://github.com/alexanderkiel/hap-client-clj"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "1.7.228"]
                 [org.clojure/core.async "0.2.371"]
                 [org.clojure/tools.logging "0.3.1"]
                 [prismatic/schema "1.0.4"]
                 [http-kit "2.1.18"]
                 [com.cognitect/transit-clj "0.8.285"]
                 [com.cognitect/transit-cljs "0.8.237"]
                 [org.clojars.akiel/transit-schema "0.4"]]

  :profiles {:dev {:dependencies [[http-kit.fake "0.2.1"]]}})
