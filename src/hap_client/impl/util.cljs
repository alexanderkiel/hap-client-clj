(ns hap-client.impl.util
  (:require [plumbing.core :refer [map-keys]]
            [clojure.string :as str]
            [hap-client.impl.transit :as t]))

(defn keyword-header [header]
  (keyword (str/lower-case header)))

(defn keyword-headers [headers]
  (map-keys keyword-header headers))

(defn- set-parameter-value! [write-opts uri k v]
  (.setParameterValue uri (name k) (t/write write-opts v)))

(defn set-query!
  "Takes kvs from params and puts them as query params into the URI."
  [write-opts uri params]
  (reduce-kv (partial set-parameter-value! write-opts) uri params))
