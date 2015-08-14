(ns hap-client.impl.transit-test
  (:require [clojure.test :refer :all]
            [hap-client.impl.transit :refer :all]
            [schema.test :refer [validate-schemas]]))

(use-fixtures :once validate-schemas)

(deftest write-str-test
  (testing "strings"
    (are [i o] (= o (write-str i))
      "foo" "[\"~#'\",\"foo\"]"
      "bar" "[\"~#'\",\"bar\"]"
      "\u2020" "[\"~#'\",\"\u2020\"]")))
