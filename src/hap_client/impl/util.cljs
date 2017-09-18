(ns hap-client.impl.util
  (:require [clojure.string :as str]
            [hap-client.impl.transit :as t]))

(defn keyword-header [header]
  (keyword (str/lower-case header)))

(defn keyword-headers [headers]
  (into {} (map (fn [[k v]] [k (keyword-header v)])) headers))

(defn- set-parameter-value! [write-opts uri k v]
  (.setParameterValue uri (name k) (t/write write-opts v)))

(defn set-query!
  "Takes kvs from params and puts them as query params into the URI."
  [write-opts uri params]
  (reduce-kv (partial set-parameter-value! write-opts) uri params))
