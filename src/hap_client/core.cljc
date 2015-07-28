(ns hap-client.core
  (:require
    #?@(:clj [[clojure.core.async :as async]
              [clojure.java.io :as io]
              [clojure.tools.logging :refer [debug]]
              [org.httpkit.client :as http]])
    #?@(:cljs [[plumbing.core :refer [map-vals]]
               [cljs.core.async :as async]
               [goog.events :as events]
               [hap-client.impl.util :as util]])
              [cognitect.transit :as transit]
              [plumbing.core :refer [assoc-when map-vals]]
              [schema.core :as s :refer [Str]]
              [hap-client.impl.uri :as uri]
              [transit-schema.core :as ts])
  #?(:clj
     (:import [java.io ByteArrayOutputStream]
              [java.net URI]))
  #?(:cljs
     (:import [goog.net XhrIo EventType]
       [goog Uri]))
  (:refer-clojure :exclude [update]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- Schemas ---------------------------------------------------------------

(def Uri
  #?(:clj URI)
  #?(:cljs Uri))

(def Link
  {:href Uri})

(def Query
  {:href Uri})

(def Args
  "A map of query/form param keyword to value."
  {s/Keyword s/Any})

(def Form
  {:href Uri})

(def Resource
  "Client side representation of a remote resource."
  (s/either Uri Str Link))

(def CustomRequestHeaders
  "Custom request headers."
  {Str Str})

(def Opts
  "Request options to support authentication and other things through custom
  request headers."
  {(s/optional-key :headers) CustomRequestHeaders})

(def Links
  {s/Keyword (s/either Link [Link])})

(def Queries
  {s/Keyword Query})

(def Forms
  {s/Keyword Form})

(def Operations
  #{(s/enum :update :delete)})

(declare Representation)

(def Embedded
  {s/Keyword (s/either (s/recursive #'Representation)
                       [(s/recursive #'Representation)])})

(def Representation
  {(s/optional-key :data) s/Any
   (s/optional-key :links) Links
   (s/optional-key :queries) Queries
   (s/optional-key :forms) Forms
   (s/optional-key :embedded) Embedded
   (s/optional-key :ops) Operations
   s/Any s/Any})

;; ---- Private ---------------------------------------------------------------

(def ^:private media-types {"application/transit+json" :json
                            "application/transit+msgpack" :msgpack})

(def ^:private ^:dynamic *base-uri* nil)

(defn- resolve-uri [uri]
  (uri/resolve *base-uri* uri))

(def ^:private read-opts
  {:handlers
   (assoc ts/read-handlers
     "r" (transit/read-handler resolve-uri))})

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

(defn- ensure-ops-set [doc]
  (if (set? (:ops doc)) doc (clojure.core/update doc :ops set)))

(defn- parse-body [opts format body]
  (->> (binding [*base-uri* (uri/create (:url opts))]
         (read-transit body format))
       (ensure-ops-set)))

(defn- content-type-ex-info [opts content-type status]
  (ex-info (str
             (if content-type
               (str "Invalid Content-Type " content-type " ")
               (str "Missing Content-Type "))
             "while fetching " (:url opts))
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

(s/defn ^:private process-fetch-resp :- Representation
  [{:keys [opts error status headers] :as resp}]
  (when error
    (throw (fetch-error-ex-info opts error)))
  (let [{:keys [body]} (parse-response resp)]
    (condp = status
      200 (assoc-when body :etag (:etag headers))
      (throw (fetch-status-ex-info opts status body)))))

(defn- extract-uri [resource]
  (if-let [uri (:href resource)]
    uri
    resource))

(s/defn fetch
  "Returns a channel conveying the current representation of the remote
  resource.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([resource :- Resource] (fetch resource {}))
  ([resource :- Resource opts :- Opts]
    (let [uri (extract-uri resource)
          ch (async/chan)]
      #?(:clj (debug "Fetch" (str uri)))
      #?(:clj
         (http/request
           (merge-with merge
             {:method :get
              :url (str uri)
              :headers {"Accept" "application/transit+json"}
              :as :stream}
             opts)
           (fn [resp]
             (try
               (async/put! ch (process-fetch-resp resp))
               (catch Throwable t (async/put! ch t)))
             (async/close! ch))))
      #?(:cljs
         (let [xhr (XhrIo.)]
           (events/listen
             xhr EventType.COMPLETE
             (fn [_]
               (try
                 (some->> {:opts {:url (str uri)}
                           :status (.getStatus xhr)
                           :headers (-> (js->clj (.getResponseHeaders xhr))
                                        (util/keyword-headers))
                           :body (.getResponseText xhr)}
                          (process-fetch-resp)
                          (async/put! ch))
                 (catch js/Error e (async/put! ch e)))
               (async/close! ch)))
           (.send xhr uri "GET" nil
                  #js {"Accept" "application/transit+json"})))
      ch)))

;; ---- Query -----------------------------------------------------------------

(s/defn execute
  "Executes the query using args and optional opts.

  Returns a channel conveying the result of the query.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([query :- Query args :- Args] (execute query args {}))
  ([query :- Query args :- Args opts]
    #?(:clj
       (let [ch (async/chan)]
         (http/request
           (merge-with merge
             {:method :get
              :url (str (:href query))
              :headers {"Accept" "application/transit+json"}
              :query-params (map-vals transit-write-str args)
              :as :stream}
             opts)
           (fn [resp]
             (try
               (async/put! ch (process-fetch-resp resp))
               (catch Throwable t (async/put! ch t)))
             (async/close! ch)))
         ch))
    #?(:cljs
       (fetch (util/set-query! (:href query) args) opts))))

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

(s/defn ^:private process-create-resp :- Uri
  [{:keys [opts error status headers] :as resp}]
  (when error
    (throw (create-error-ex-info opts error)))
  (when (not= 201 status)
    (throw (create-status-ex-info opts status (:body (parse-response resp)))))
  (if-let [location (:location headers)]
    (uri/resolve (uri/create (:url opts)) location)
    (throw (missing-location-ex-info opts))))

(s/defn create
  "Creates a resource as described by the form using args and optional opts.

  Returns a channel conveying the client-side representation of the resource
  created."
  ([form :- Form args :- Args] (create form args {}))
  ([form :- Form args :- Args opts :- Opts]
    (let [ch (async/chan)]
      #?(:clj
         (http/request
           (merge-with merge
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
  (or (:etag rep) "*"))

(defn- update-error-ex-info [opts error]
  (ex-info (str "Error while updating the resource at " (:url opts))
           (error-ex-data opts error)))

(defn- update-status-ex-info [opts status body]
  (ex-info (str "Non 204 (No Content) status " status " while updating the "
                "resource at " (:url opts))
           (status-ex-data opts status body)))

(s/defn ^:private process-update-resp :- Representation
  [{:keys [opts error status headers] :as resp} rep]
  (when error
    (throw (update-error-ex-info opts error)))
  (when (not= 204 status)
    (throw (update-status-ex-info opts status (:body (parse-response resp)))))
  (assoc-when rep :etag (:etag headers)))

(defn- remove-controls [representation]
  (dissoc representation :links :queries :forms :embedded :ops))

(defn- remove-embedded [representation]
  (dissoc representation :embedded))

(s/defn update
  "Updates the resource to reflect the state of the given representation using
  optional opts and returns a channel which conveys the given representation
  with the new ETag after the resource was updated.

  Uses the ETag from representation for the conditional update if the
  representation contains one."
  ([resource :- Resource representation :- Representation]
    (update resource representation {}))
  ([resource :- Resource representation :- Representation opts :- Opts]
    (let [uri (extract-uri resource)
          ch (async/chan)]
      #?(:clj
         (http/request
           (merge-with merge
             {:method :put
              :url (str uri)
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
                 (let [resp {:opts {:url (str uri)}
                             :status (.getStatus xhr)
                             :headers (-> (js->clj (.getResponseHeaders xhr))
                                          (util/keyword-headers))
                             :body (.getResponseText xhr)}]
                   (async/put! ch (process-update-resp resp representation)))
                 (catch js/Error e (async/put! ch e)))
               (async/close! ch)))
           (.send xhr uri "PUT"
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

(s/defn delete
  "Deletes the resource using optional opts and returns a channel which closes
  after the resource was deleted.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([resource :- Resource] (delete resource {}))
  ([resource :- Resource opts :- Opts]
    (let [uri (extract-uri resource)
          ch (async/chan)]
      #?(:clj
         (http/request
           (merge-with merge
             {:method :delete
              :url (str uri)
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
                 (some->> {:opts {:url (str uri)}
                           :status (.getStatus xhr)
                           :headers (-> (js->clj (.getResponseHeaders xhr))
                                        (util/keyword-headers))
                           :body (.getResponseText xhr)}
                          (process-delete-resp)
                          (async/put! ch))
                 (catch js/Error e (async/put! ch e)))
               (async/close! ch)))
           (.send xhr uri "DELETE" nil
                  #js {"Accept" "application/transit+json"})))
      ch)))
