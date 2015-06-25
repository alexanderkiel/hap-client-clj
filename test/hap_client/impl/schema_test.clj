(ns hap-client.impl.schema-test
  (:require [clojure.test :refer :all]
            [hap-client.impl.schema :refer :all]
            [schema.core :as s]))

(deftest resolve-schema-test
  (testing "Schemas for atomic value types"
    (are [var schema] (= schema (resolve-schema var))
      'Str s/Str
      'Int s/Int
      'Bool s/Bool
      'Num s/Num
      'Int s/Int
      'Keyword s/Keyword
      'Symbol s/Symbol
      'Regex s/Regex
      'Inst s/Inst
      'Uuid s/Uuid))
  (testing "Simple composite schemas"
    (are [var schema] (= schema (resolve-schema var))
      '(either Str Int) (s/either s/Str s/Int)
      '(both Str Int) (s/both s/Str s/Int)
      '(enum :a) (s/enum :a)))
  (testing "Just returns the symbol on unkonwn atomics"
    (is (= 'Foo (resolve-schema 'Foo))))
  (testing "Just returns the form on unkonwn composite schemas"
    (is (= '(foo :a) (resolve-schema '(foo :a))))))
