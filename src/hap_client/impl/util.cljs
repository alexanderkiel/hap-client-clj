(ns hap-client.impl.util
  (:require [plumbing.core :refer [map-keys]]
            [clojure.string :as str]
            [cognitect.transit :as transit]))

(defn keyword-header [header]
  (keyword (str/lower-case header)))

(defn keyword-headers [headers]
  (map-keys keyword-header headers))

(defn write-transit [x]
  (transit/write (transit/writer :json) x))

(defn- set-parameter-value! [uri k v]
  (.setParameterValue uri (name k) (write-transit v)))

(defn set-query!
  "Takes kvs from params and puts them as query params into the URI."
  [uri params]
  (reduce-kv set-parameter-value! uri params))
