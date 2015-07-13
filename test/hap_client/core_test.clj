(ns hap-client.core-test
  (:require [clojure.test :refer :all]
            [hap-client.core :refer :all]
            [org.httpkit.fake :refer :all]
            [clojure.core.async :refer [<!!]]
            [schema.test :refer [validate-schemas]])
  (:import [java.net URI])
  (:refer-clojure :exclude [update]))

(use-fixtures :once validate-schemas)

(deftest fetch-test
  (testing "200 response returns just the body"
    (with-fake-http ["uri-122654"
                     {:status 200
                      :headers {:content-type "application/transit+json"}
                      :body (#'hap-client.core/write-transit {})}]
      (let [resp (<!! (fetch (URI/create "uri-122654")))]
        (is (map? resp)))))

  (testing "404 response returns exception with status and body"
    (with-fake-http ["uri-124919"
                     {:status 404
                      :headers {:content-type "application/transit+json"}
                      :body (#'hap-client.core/write-transit {})}]
      (let [resp (<!! (fetch (URI/create "uri-124919")))]
        (is (instance? Exception resp))
        (is (= 404 (:status (ex-data resp))))
        (is (:body (ex-data resp))))))

  (testing "Accepts links as resource"
    (with-fake-http ["uri-125417"
                     {:status 200
                      :headers {:content-type "application/transit+json"}
                      :body (#'hap-client.core/write-transit {})}]
      (let [resp (<!! (fetch {:href (URI/create "uri-125417")}))]
        (is (map? resp))))))

(deftest execute-test
  (testing "200 response returns just the body"
    (with-fake-http ["uri-142522"
                     {:status 200
                      :headers {:content-type "application/transit+json"}
                      :body (#'hap-client.core/write-transit {})}]
      (let [resp (<!! (execute {:href (URI/create "uri-142522")} {}))]
        (is (map? resp))))))

(deftest create-test
  (testing "201 response returns the resource created"
    (with-fake-http [{:method :post
                      :url "uri-141743"}
                     {:status 201
                      :headers {:location "uri-141811"}}]
      (let [resp (<!! (create {:href (URI/create "uri-141743")} {}))]
        (is (= (URI/create "uri-141811") resp))))))

(deftest update-test
  (testing "204 response returns the given representation"
    (with-fake-http [{:method :put
                      :url "uri-142830"}
                     {:status 204}]
      (let [rep {}
            resp (<!! (update (URI/create "uri-142830") rep))]
        (is (= rep resp)))))

  (testing "Accepts links as resource"
    (with-fake-http [{:method :put
                      :url "uri-142830"}
                     {:status 204}]
      (let [rep {}
            resp (<!! (update {:href (URI/create "uri-142830")} rep))]
        (is (= rep resp))))))

(deftest delete-test
  (testing "204 response closes the channel"
    (with-fake-http [{:method :delete
                      :url "uri-145340"}
                     {:status 204}]
      (let [resp (<!! (delete (URI/create "uri-145340")))]
        (is (nil? resp))))))
