(ns hap-client.impl.schema
  #?(:clj
     (:use plumbing.core))
  (:require [clojure.walk :refer [postwalk]]
            [schema.core :as s]
            #?(:cljs [plumbing.core :refer [map-vals]])))

(declare resolve-schema)

(defn resolve-schema-constructor [[constructor & args :as form]]
  (condp = constructor
    'either (apply s/either (map resolve-schema args))
    'both (apply s/both (map resolve-schema args))
    'enum (apply s/enum args)
    form))

(defn resolve-schema-var [sym]
  (condp = sym
    'Str s/Str
    'Bool s/Bool
    'Num s/Num
    'Int s/Int
    'Keyword s/Keyword
    'Symbol s/Symbol
    'Regex s/Regex
    'Inst s/Inst
    'Uuid s/Uuid
    sym))

(defn resolve-schema [t]
  (-> (fn [form]
        (cond
          (symbol? form) (resolve-schema-var form)
          (seq? form) (resolve-schema-constructor form)
          :else form))
      (postwalk t)))

(defn resolve-param-schema [param]
  (clojure.core/update param :type resolve-schema))

(defn resolve-form-schemas [form]
  (clojure.core/update form :params #(map-vals resolve-param-schema %)))

(defn resolve-schemas [doc]
  (-> (clojure.core/update doc :queries #(map-vals resolve-form-schemas %))
      (clojure.core/update :forms #(map-vals resolve-form-schemas %))))
