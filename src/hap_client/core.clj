(ns hap-client.core
  (:use plumbing.core)
  (:require [clojure.core.async :as async :refer [go <!]]
            [clojure.java.io :as io]
            [org.httpkit.client :as http]
            [cognitect.transit :as transit])
  (:import [java.net URI]
           [java.io ByteArrayOutputStream])
  (:refer-clojure :exclude [update]))

(set! *warn-on-reflection* true)

(defn resource
  "Creates a client-side representation of the remote resource at uri."
  [uri]
  uri)

(defn- to-uri [s]
  (URI/create s))

(def ^:private media-types {"application/transit+json" :json
                            "application/transit+msgpack" :msgpack})

(defn- read-transit [is format]
  (transit/read (transit/reader is format)))

(defn- write-transit [o]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) o)
    (io/input-stream (.toByteArray out))))

(defn- transit-write-str [o]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) o)
    (String. (.toByteArray out))))

(defn- resolve-uri [^URI base-uri uri]
  (.resolve base-uri (str uri)))

(defn- resolve-uri-in-form
  "Resolves relative URIs in :href values of form using base-uri."
  [base-uri form]
  (if (instance? com.cognitect.transit.URI form)
    (resolve-uri base-uri form)
    form))

(defn- resolve-uris
  "Resolves relative URIs in all :href values of doc using base-uri."
  [base-uri doc]
  (clojure.walk/postwalk #(resolve-uri-in-form base-uri %) doc))

(defn- create-resource [link]
  (resource (:href link)))

(defn- create-resources [doc]
  (let [doc (clojure.core/update doc :links #(map-vals create-resource %))]
    (if (:embedded doc)
      (clojure.core/update doc :embedded
                           (partial map-vals (partial mapv create-resources)))
      doc)))

(defn- parse-body [opts format body]
  (->> (read-transit body format)
       (resolve-uris (to-uri (:url opts)))
       (create-resources)))

(defn- content-type-ex-info [opts content-type]
  (ex-info (str "Invalid Content-Type " content-type " while fetching "
                (:url opts))
           {:content-type content-type
            :uri (to-uri (:url opts))}))

(defn- parse-response [{:keys [opts headers] :as resp}]
  (let [content-type (:content-type headers)]
    (if-let [format (media-types content-type)]
      (clojure.core/update resp :body #(parse-body opts format %))
      (throw (content-type-ex-info opts content-type)))))

(defn- error-ex-data [opts error]
  {:error error :uri (to-uri (:url opts))})

(defn- status-ex-data [opts status]
  {:status status :uri (to-uri (:url opts))})

;; ---- Fetch -----------------------------------------------------------------

(defn- fetch-error-ex-info [opts error]
  (ex-info (str "Error while fetching the resource at " (:url opts))
           (error-ex-data opts error)))

(defn- fetch-status-ex-info [opts status]
  (ex-info (str "Non-ok status " status " while fetching the resource at "
                (:url opts))
           (status-ex-data opts status)))

(defn- process-fetch-resp [{:keys [opts error status] :as resp}]
  (when error
    (throw (fetch-error-ex-info opts error)))
  (let [resp (parse-response resp)]
    (condp = status
      200 (with-meta (:body resp) (select-keys (:headers resp) [:etag]))
      404 nil
      (throw (fetch-status-ex-info opts status)))))

(defn fetch
  "Returns a channel conveying the current representation of the remote
  resource if it exists. Closes the channel if not.

  Opts can be a map of custom :headers to support authentication an other
  things.

  Links in representations are already replaced by there client-side
  representation of the remote resource the link to. So it's possible to fetch
  a link directly.

  Puts an ExceptionInfo onto the channel if there is any problem."
  ([resource] (fetch resource {}))
  ([resource opts]
   (let [ch (async/chan)]
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
         (async/close! ch)))
     ch)))

;; ---- Query -----------------------------------------------------------------

(defn execute
  "Executes the query using args and optional opts.

  Returns a channel conveying the result of the query if there is one. Closes
  the channel if not."
  ([query args] (execute query args {}))
  ([query args opts]
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
         (async/close! ch)))
     ch)))

;; ---- Create ----------------------------------------------------------------

(defn- create-error-ex-info [opts error]
  (ex-info (str "Error while creating a resource using " (:url opts))
           (error-ex-data opts error)))

(defn- create-status-ex-info [opts status]
  (ex-info (str "Non-created status " status " while creating a resource using "
                (:url opts))
           (status-ex-data opts status)))

(defn- missing-location-ex-info [opts]
  (ex-info (str "Missing location header while creating a resource using "
                (:url opts))
           {:uri (to-uri (:url opts))}))

(defn- process-create-resp [{:keys [opts error status headers]}]
  (when error
    (throw (create-error-ex-info opts error)))
  (when (not= 201 status)
    (throw (create-status-ex-info opts status)))
  (if-let [location (:location headers)]
    (resource (resolve-uri (to-uri (:url opts)) location))
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
     (http/request
       (merge
         {:method :post
          :url (str (:href form))
          :headers {"Accept" "application/transit+json"
                    "Content-Type" "application/transit+json"}
          :body (write-transit args)
          :follow-redirects false}
         opts)
       (fn [resp]
         (try
           (async/put! ch (process-create-resp resp))
           (catch Throwable t (async/put! ch t)))
         (async/close! ch)))
     ch)))

;; ---- Update ----------------------------------------------------------------

(defn- if-match [rep]
  (if-let [etag (:etag (meta rep))] etag "*"))

(defn- update-error-ex-info [opts error]
  (ex-info (str "Error while updating the resource at " (:url opts))
           (error-ex-data opts error)))

(defn- update-status-ex-info [opts status]
  (ex-info (str "Non-no-content status " status " while updating the resource "
                "at " (:url opts))
           (status-ex-data opts status)))

(defn- process-update-resp [{:keys [opts error status headers]} rep]
  (when error
    (throw (update-error-ex-info opts error)))
  (when (not= 204 status)
    (throw (update-status-ex-info opts status)))
  (with-meta rep (clojure.core/update (meta rep) :etag (:etag headers))))

(defn update
  "Updates the resource to reflect the state of the given representation using
  optional opts and returns a channel which conveys the given representation
  with the new ETag after the resource was updated.

  Opts can be a map of custom :headers to support authentication an other
  things.
  
  Uses the ETag from representation for the conditional update if the 
  representation contains one."
  ([resource representation] (create resource representation {}))
  ([resource representation opts]
   (let [ch (async/chan)]
     (http/request
       (merge
         {:method :put
          :url (str resource)
          :headers {"Accept" "application/transit+json"
                    "Content-Type" "application/transit+json"
                    "If-Match" (if-match representation)}
          :body (write-transit representation)
          :follow-redirects false}
         opts)
       (fn [resp]
         (try
           (async/put! ch (process-update-resp resp representation))
           (catch Throwable t (async/put! ch t)))
         (async/close! ch)))
     ch)))

;; ---- Delete ----------------------------------------------------------------

(defn- delete-error-ex-info [opts error]
  (ex-info (str "Error while deleting the resource at " (:url opts))
           (error-ex-data opts error)))

(defn- delete-status-ex-info [opts status]
  (ex-info (str "Non-no-content status " status " while deleting the resource "
                "at " (:url opts))
           (status-ex-data opts status)))

(defn- process-delete-resp [{:keys [opts error status]}]
  (when error
    (throw (delete-error-ex-info opts error)))
  (when (not= 204 status)
    (throw (delete-status-ex-info opts status))))

(defn delete
  "Deletes the resource using optional opts and returns a channel which closes 
  after the resource was deleted.

  Opts can be a map of custom :headers to support authentication an other
  things.

  Puts an ExceptionInfo onto the channel if there is any problem."
  ([resource] (delete resource {}))
  ([resource opts]
   (let [ch (async/chan)]
     (http/request
       (merge
         {:method :delete
          :url (str resource)
          :follow-redirects false}
         opts)
       (fn [resp]
         (try
           (process-delete-resp resp)
           (catch Throwable t (async/put! ch t)))
         (async/close! ch)))
     ch)))
