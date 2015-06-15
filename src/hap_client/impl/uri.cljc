(ns hap-client.impl.uri
  (:require [clojure.walk :refer [postwalk]])
  #?(:clj
     (:import [java.net URI]))
  #?(:cljs
     (:import [goog Uri]))
  (:refer-clojure :exclude [resolve]))

#?(:clj (set! *warn-on-reflection* true))

(defn create [s]
  #?(:clj (URI/create s))
  #?(:cljs (.parse Uri s)))

(defn uri? [x]
  #?(:clj (instance? URI x))
  #?(:cljs (instance? Uri x)))

(defn resolve [#?(:clj ^URI base-uri) #?(:cljs base-uri)
               #?(:clj ^URI uri) #?(:cljs uri)]
  (.resolve base-uri uri))

(defn- resolve-in-form
  "Resolves relative URIs in form using base-uri."
  [base-uri form]
  (if (uri? form)
    (resolve base-uri form)
    form))

(defn resolve-all
  "Resolves all relative URIs of doc using base-uri."
  [base-uri doc]
  {:pre [(uri? base-uri)]}
  (postwalk #(resolve-in-form base-uri %) doc))
