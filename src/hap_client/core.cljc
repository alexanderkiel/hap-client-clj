(ns hap-client.core
  #?(:clj
     (:use plumbing.core))
  (:require
    #?@(:clj [[clojure.core.async :as async]
              [clojure.java.io :as io]
              [clojure.walk :refer [postwalk]]
              [org.httpkit.client :as http]])
    #?@(:cljs [[plumbing.core :refer [map-vals]]
               [cljs.core.async :as async]
               [goog.events :as events]
               [hap-client.impl.util :as util]
               [hap-client.impl.walk :refer [postwalk]]])
              [cognitect.transit :as transit]
              [hap-client.impl.uri :as uri]
              [transit-schema.core :as ts])
  #?(:clj
     (:import [java.io ByteArrayOutputStream]))
  #?(:cljs
     (:import [goog.net XhrIo EventType]))
  (:refer-clojure :exclude [update]))

#?(:clj (set! *warn-on-reflection* true))

(defn resource
  "Creates a client-side representation of the remote resource from a link or a
  uri."
  [link-or-uri]
  (if-let [uri (:href link-or-uri)]
    uri
    link-or-uri))

(def ^:private media-types {"application/transit+json" :json
                            "application/transit+msgpack" :msgpack})

(def ^:private read-opts
  {:handlers
   (assoc ts/read-handlers "r" (transit/read-handler uri/create))})

(defn- read-transit [in format]
  #?(:clj (transit/read (transit/reader in format read-opts)))
  #?(:cljs (transit/read (transit/reader format read-opts) in)))

(defn- write-transit [o]
  #?(:clj
     (let [out (ByteArrayOutputStream.)]
       (transit/write (transit/writer out :json {:handlers ts/write-handlers}) o)
       (io/input-stream (.toByteArray out)))))

(defn- transit-write-str [o]
  #?(:clj
     (let [out (ByteArrayOutputStream.)]
       (transit/write (transit/writer out :json {:handlers ts/write-handlers}) o)
       (String. (.toByteArray out)))))

(defn ensure-ops-set [doc]
  (if (set? (:ops doc)) doc (clojure.core/update doc :ops set)))

(defn- parse-body [opts format body]
  {:pre [(:url opts) format body] :post [%]}
  (->> (read-transit body format)
       (ensure-ops-set)
       (uri/resolve-all (uri/create (:url opts)))))

(defn- content-type-ex-info [opts content-type status]
  (ex-info (str (if content-type "Invalid" "Missing") " Content-Type "
                content-type " while fetching " (:url opts))
           {:content-type content-type
            :uri (uri/create (:url opts))
            :status status}))

(defn- parse-response [{:keys [opts headers status] :as resp}]
  (let [content-type (:content-type headers)]
    (if-let [format (media-types content-type)]
      (clojure.core/update resp :body #(parse-body opts format %))
      (throw (content-type-ex-info opts content-type status)))))

(defn- error-ex-data [opts error]
  {:error error :uri (uri/create (:url opts))})

(defn- status-ex-data [opts status body]
  {:status status :uri (uri/create (:url opts)) :body body})

;; ---- Fetch -----------------------------------------------------------------

(defn- fetch-error-ex-info [opts error]
  (ex-info (str "Error while fetching the resource at " (:url opts))
           (error-ex-data opts error)))

(defn- fetch-status-ex-info [opts status body]
  (ex-info (str "Non-ok status " status " while fetching the resource at "
                (:url opts))
           (status-ex-data opts status body)))

(defn- process-fetch-resp [{:keys [opts error status] :as resp}]
  (when error
    (throw (fetch-error-ex-info opts error)))
  (let [resp (parse-response resp)]
    (condp = status
      200 (with-meta (:body resp) (select-keys (:headers resp) [:etag]))
      (throw (fetch-status-ex-info opts status (:body resp))))))

(defn fetch
  "Returns a channel conveying the current representation of the remote
  resource.

  Opts can be a map of custom :headers to support authentication an other
  things.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([resource] (fetch resource {}))
  ([resource opts]
   (let [ch (async/chan)]
     #?(:clj
        (http/request
          (merge
            {:method :get
             :url (str resource)
             :headers {"Accept" "application/transit+json"}
             :as :stream}
            opts)
          (fn [resp]
            (try
              (some->> (process-fetch-resp resp) (async/put! ch))
              (catch Throwable t (async/put! ch t)))
            (async/close! ch))))
     #?(:cljs
        (let [xhr (XhrIo.)]
          (events/listen
            xhr EventType.COMPLETE
            (fn [_]
              (try
                (some->> {:opts {:url (str resource)}
                          :status (.getStatus xhr)
                          :headers (-> (js->clj (.getResponseHeaders xhr))
                                       (util/keyword-headers))
                          :body (.getResponseText xhr)}
                         (process-fetch-resp)
                         (async/put! ch))
                (catch js/Error e (async/put! ch e)))
              (async/close! ch)))
          (.send xhr (str resource) "GET" nil
                 #js {"Accept" "application/transit+json"})))
     ch)))

;; ---- Query -----------------------------------------------------------------

(defn execute
  "Executes the query using args and optional opts.

  Args is a map of query param name to value.

  Returns a channel conveying the result of the query.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([query args] (execute query args {}))
  ([query args opts]
    #?(:clj
       (let [ch (async/chan)]
         (http/request
           (merge
             {:method :get
              :url (str (:href query))
              :headers {"Accept" "application/transit+json"}
              :query-params (map-vals transit-write-str args)
              :as :stream}
             opts)
           (fn [resp]
             (try
               (some->> (process-fetch-resp resp) (async/put! ch))
               (catch Throwable t (async/put! ch t)))
             (async/close! ch))) ch))
    #?(:cljs
       (fetch (resource (util/set-query! (:href query) args)) opts))))

;; ---- Create ----------------------------------------------------------------

(defn- create-error-ex-info [opts error]
  (ex-info (str "Error while creating a resource using " (:url opts))
           (error-ex-data opts error)))

(defn- create-status-ex-info [opts status body]
  (ex-info (str "Non-created status " status " while creating a resource using "
                (:url opts))
           (status-ex-data opts status body)))

(defn- missing-location-ex-info [opts]
  (ex-info (str "Missing location header while creating a resource using "
                (:url opts))
           {:uri (uri/create (:url opts))}))

(defn- process-create-resp [{:keys [opts error status headers] :as resp}]
  (when error
    (throw (create-error-ex-info opts error)))
  (when (not= 201 status)
    (throw (create-status-ex-info opts status (:body (parse-response resp)))))
  (if-let [location (:location headers)]
    (resource (uri/resolve (uri/create (:url opts)) (uri/create location)))
    (throw (missing-location-ex-info opts))))

(defn create
  "Creates a resource as described by the form using args and optional opts.

  Opts can be a map of custom :headers to support authentication an other
  things.

  Returns a channel conveying the client-side representation of the resource
  created."
  ([form args] (create form args {}))
  ([form args opts]
   (let [ch (async/chan)]
     #?(:clj
        (http/request
          (merge
            {:method :post
             :url (str (:href form))
             :headers {"Accept" "application/transit+json"
                       "Content-Type" "application/transit+json"}
             :body (write-transit args)
             :follow-redirects false
             :as :stream}
            opts)
          (fn [resp]
            (try
              (async/put! ch (process-create-resp resp))
              (catch Throwable t (async/put! ch t)))
            (async/close! ch))))
     #?(:cljs
        (let [xhr (XhrIo.)]
          (events/listen
            xhr EventType.COMPLETE
            (fn [_]
              (try
                (->> {:opts {:url (str (:href form))}
                      :status (.getStatus xhr)
                      :headers (-> (js->clj (.getResponseHeaders xhr))
                                   (util/keyword-headers))
                      :body (.getResponseText xhr)}
                     (process-create-resp)
                     (async/put! ch))
                (catch js/Error e (async/put! ch e)))
              (async/close! ch)))
          (.send xhr (:href form) "POST" (util/write-transit args)
                 #js {"Accept" "application/transit+json"
                      "Content-Type" "application/transit+json"})))
     ch)))

;; ---- Update ----------------------------------------------------------------

(defn- if-match [rep]
  (if-let [etag (:etag (meta rep))] etag "*"))

(defn- update-error-ex-info [opts error]
  (ex-info (str "Error while updating the resource at " (:url opts))
           (error-ex-data opts error)))

(defn- update-status-ex-info [opts status body]
  (ex-info (str "Non 204 (No Content) status " status " while updating the "
                "resource at " (:url opts))
           (status-ex-data opts status body)))

(defn- process-update-resp [{:keys [opts error status headers] :as resp} rep]
  (when error
    (throw (update-error-ex-info opts error)))
  (when (not= 204 status)
    (throw (update-status-ex-info opts status (:body (parse-response resp)))))
  (with-meta rep (assoc (meta rep) :etag (:etag headers))))

(defn- remove-controls [representation]
  (dissoc representation :links :queries :forms :embedded :ops))

(defn- remove-embedded [representation]
  (dissoc representation :embedded))

(defn update
  "Updates the resource to reflect the state of the given representation using
  optional opts and returns a channel which conveys the given representation
  with the new ETag after the resource was updated.

  Opts can be a map of custom :headers to support authentication an other
  things.

  Uses the ETag from representation for the conditional update if the
  representation contains one."
  ([resource representation] (update resource representation {}))
  ([resource representation opts]
   (let [ch (async/chan)]
     #?(:clj
        (http/request
          (merge
            {:method :put
             :url (str resource)
             :headers {"Accept" "application/transit+json"
                       "Content-Type" "application/transit+json"
                       "If-Match" (if-match representation)}
             :body (write-transit (-> representation
                                      remove-controls
                                      remove-embedded))
             :follow-redirects false
             :as :stream}
            opts)
          (fn [resp]
            (try
              (async/put! ch (process-update-resp resp representation))
              (catch Throwable t (async/put! ch t)))
            (async/close! ch))))
     #?(:cljs
        (let [xhr (XhrIo.)]
          (events/listen
            xhr EventType.COMPLETE
            (fn [_]
              (try
                (let [resp {:opts {:url (str resource)}
                            :status (.getStatus xhr)
                            :headers (-> (js->clj (.getResponseHeaders xhr))
                                         (util/keyword-headers))
                            :body (.getResponseText xhr)}]
                  (async/put! ch (process-update-resp resp representation)))
                (catch js/Error e (async/put! ch e)))
              (async/close! ch)))
          (.send xhr (str resource) "PUT"
                 (util/write-transit (-> representation
                                         remove-controls
                                         remove-embedded))
                 #js {"Accept" "application/transit+json"
                      "Content-Type" "application/transit+json"
                      "If-Match" (if-match representation)})))
     ch)))

;; ---- Delete ----------------------------------------------------------------

(defn- delete-error-ex-info [opts error]
  (ex-info (str "Error while deleting the resource at " (:url opts))
           (error-ex-data opts error)))

(defn- delete-status-ex-info [opts status body]
  (ex-info (str "Non 204 (No Content) status " status " while deleting the "
                "resource at " (:url opts))
           (status-ex-data opts status body)))

(defn- process-delete-resp [{:keys [opts error status] :as resp}]
  (when error
    (throw (delete-error-ex-info opts error)))
  (when (not= 204 status)
    (throw (delete-status-ex-info opts status (:body (parse-response resp))))))

(defn delete
  "Deletes the resource using optional opts and returns a channel which closes
  after the resource was deleted.

  Opts can be a map of custom :headers to support authentication an other
  things.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([resource] (delete resource {}))
  ([resource opts]
   (let [ch (async/chan)]
     #?(:clj
        (http/request
          (merge
            {:method :delete
             :url (str resource)
             :follow-redirects false
             :as :stream}
            opts)
          (fn [resp]
            (try
              (process-delete-resp resp)
              (catch Throwable t (async/put! ch t)))
            (async/close! ch))))
     #?(:cljs
        (let [xhr (XhrIo.)]
          (events/listen
            xhr EventType.COMPLETE
            (fn [_]
              (try
                (some->> {:opts {:url (str resource)}
                          :status (.getStatus xhr)
                          :headers (-> (js->clj (.getResponseHeaders xhr))
                                       (util/keyword-headers))
                          :body (.getResponseText xhr)}
                         (process-delete-resp)
                         (async/put! ch))
                (catch js/Error e (async/put! ch e)))
              (async/close! ch)))
          (.send xhr resource "DELETE" nil
                 #js {"Accept" "application/transit+json"})))
     ch)))
