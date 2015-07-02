(ns hap-client.impl.util
  (:require [plumbing.core :refer [map-keys]]
            [clojure.string :as str]
            [cognitect.transit :as transit]
            [outpace.schema-transit :as st]))

(defn keyword-header [header]
  (keyword (str/lower-case header)))

(defn keyword-headers [headers]
  (map-keys keyword-header headers))

(defn write-transit [val]
  (let [opts {:handlers st/write-handlers}
        writer (transit/writer :json opts)]
    (try
      (transit/write writer val)
      (catch js/Error e
        (throw (ex-info "Error while writing Transit"
                        {:val val :opts opts} e))))))

(defn- set-parameter-value! [uri k v]
  (.setParameterValue uri (name k) (write-transit v)))

(defn set-query!
  "Takes kvs from params and puts them as query params into the URI."
  [uri params]
  (reduce-kv set-parameter-value! uri params))
