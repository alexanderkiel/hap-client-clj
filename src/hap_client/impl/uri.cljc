(ns hap-client.impl.uri
  #?(:clj
     (:import [java.net URI]))
  #?(:cljs
     (:import [goog Uri]))
  (:refer-clojure :exclude [resolve]))

#?(:clj (set! *warn-on-reflection* true))

(defn create [s]
  #?(:clj (URI/create s))
  #?(:cljs (.parse Uri s)))

(defn resolve [#?(:clj ^URI base-uri) #?(:cljs base-uri)
               #?(:clj ^String uri) #?(:cljs uri)]
  #?(:clj (.resolve base-uri uri))
  #?(:cljs (.resolve base-uri (.parse Uri uri))))
