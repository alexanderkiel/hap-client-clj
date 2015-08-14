(ns hap-client.impl.transit
  (:require [cognitect.transit :as t]
            [transit-schema.core :as ts])
  #?(:clj
     (:import [java.io ByteArrayOutputStream])))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private write-opts
  {:handlers
   #?(:clj  (t/write-handler-map ts/write-handlers)
      :cljs ts/write-handlers)})

(defn write [x]
  #?(:clj
     (let [out (ByteArrayOutputStream.)]
       (t/write (t/writer out :json write-opts) x)
       (.toByteArray out))
     :cljs
     (let [writer (t/writer :json write-opts)]
       (try
         (t/write writer x)
         (catch js/Error e
           (throw (ex-info "Error while writing Transit"
                           {:val x :opts write-opts} e)))))))

(defn write-str [x]
  #?(:clj  (String. ^bytes (write x) "utf-8")
     :cljs (write x)))
