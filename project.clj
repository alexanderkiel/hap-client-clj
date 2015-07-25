(defproject
  org.clojars.akiel/hap-client-clj "0.1-SNAPSHOT"
  :description "A Clojure HAP client library."
  :url "https://github.com/alexanderkiel/hap-client-clj"

  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3211"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [prismatic/plumbing "0.4.3"]
                 [http-kit "2.1.18"]
                 [com.cognitect/transit-clj "dev"]
                 [com.cognitect/transit-cljs "0.8.220"]
                 [org.clojars.akiel/transit-schema "0.1-SNAPSHOT"]]

  :profiles {:dev {:dependencies [[http-kit.fake "0.2.1"]]}})
