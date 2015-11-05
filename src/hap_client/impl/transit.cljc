(ns hap-client.impl.transit
  (:require [cognitect.transit :as t])
  #?(:clj
     (:import [java.io ByteArrayOutputStream])))

#?(:clj (set! *warn-on-reflection* true))

(defn write [write-opts x]
  #?(:clj
     (let [out (ByteArrayOutputStream.)]
       (t/write (t/writer out :json write-opts) x)
       (.toByteArray out))
     :cljs
     (let [writer (t/writer :json write-opts)]
       (try
         (t/write writer x)
         (catch js/Error e
           (throw (ex-info "Error while writing Transit."
                           {:val x :opts write-opts} e)))))))

(defn write-str [write-opts x]
  #?(:clj  (String. ^bytes (write write-opts x) "utf-8")
     :cljs (write write-opts x)))
