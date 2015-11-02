(ns hap-client.core
  (:require
    #?@(:clj  [[plumbing.core :refer [letk]]
               [clojure.core.async :as async]
               [clojure.java.io :as io]
               [clojure.tools.logging :refer [debug]]
               [org.httpkit.client :as http]]
        :cljs [[plumbing.core :refer [map-vals] :refer-macros [letk]]
               [cljs.core.async :as async]
               [goog.events :as events]
               [hap-client.impl.util :as util]])
               [cognitect.transit :as transit]
               [plumbing.core :refer [assoc-when map-vals]]
               [schema.core :as s :refer [Str Bool]]
               [hap-client.impl.uri :as uri]
               [hap-client.impl.transit :as t]
               [transit-schema.core :as ts])
  (:import
    #?@(:clj  [[java.io ByteArrayOutputStream]
               [java.net URI]]
        :cljs [[goog.net XhrIo EventType]
               [goog Uri]]))
  (:refer-clojure :exclude [update]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- Schemas ---------------------------------------------------------------

(def Uri
  #?(:clj  URI
     :cljs Uri))

(def Link
  {:href Uri
   (s/optional-key :label) Str
   s/Any s/Any})

(def Param
  {:type s/Any
   (s/optional-key :optional) Bool
   (s/optional-key :label) Str
   s/Any s/Any})

(def Params
  {s/Keyword Param})

(def Query
  {:href Uri
   :params Params
   (s/optional-key :label) Str
   s/Any s/Any})

(def Args
  "A map of query/form param keyword to value."
  {s/Keyword s/Any})

(def Form
  {:href Uri
   :params Params
   (s/optional-key :label) Str
   s/Any s/Any})

(def Resource
  "Client side representation of a remote resource."
  (s/either Uri Str Link))

(def CustomRequestHeaders
  "Custom request headers."
  {Str Str})

(def TagName
  "A Transit tag name."
  Str)

(def ReadHandler
  "A Transit read handler."
  s/Any)

(def WriteHandler
  "A Transit write handler."
  s/Any)

(def Opts
  "Request options to support authentication and other things through custom
  request headers."
  {(s/optional-key :headers) CustomRequestHeaders
   (s/optional-key :read-handlers) {TagName ReadHandler}
   (s/optional-key :write-handlers) {s/Any WriteHandler}})

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

(def Response
  {s/Any s/Any})

(def ProcessFn
  "A function which processes a response."
  (s/=> (s/either Representation Uri) Response))

;; ---- Private ---------------------------------------------------------------

(def ^:private media-types {"application/transit+json" :json
                            "application/transit+msgpack" :msgpack})

(def ^:private ^:dynamic *base-uri* nil)

(defn- resolve-uri [uri]
  (uri/resolve *base-uri* uri))

(def ^:private default-read-handlers
  (assoc ts/read-handlers
    "r" (transit/read-handler resolve-uri)
    #?@(:cljs ["u" (transit/read-handler uuid)])))

#?(:clj
   (deftype HandlerMapContainer [m]
     transit/HandlerMapProvider
     (handler-map [_] m)))

#?(:clj
   (def ^:private read-handler-map-provider
     (transit/read-handler-map default-read-handlers)))

(defn read-handler-map [custom-handlers]
  #?(:clj
     (HandlerMapContainer.
       (transit/handler-map
         (transit/read-handler-map (merge default-read-handlers custom-handlers))))
     :cljs
     (merge default-read-handlers custom-handlers)))

(defn- mk-read-handlers [read-handlers]
  (cond
    (nil? read-handlers)
    #?(:clj read-handler-map-provider :cljs default-read-handlers)
    #?@(:clj [(instance? HandlerMapContainer read-handlers) read-handlers])
    :else (read-handler-map read-handlers)))

(def ^:private default-write-handlers
  ts/write-handlers)

#?(:clj
   (def ^:private write-handler-map-provider
     (transit/write-handler-map default-write-handlers)))

(defn write-handler-map [custom-handlers]
  #?(:clj
     (HandlerMapContainer.
       (transit/handler-map
         (transit/write-handler-map (merge default-write-handlers custom-handlers))))
     :cljs
     (merge default-write-handlers custom-handlers)))

(defn- mk-write-handlers [write-handlers]
  (cond
    (nil? write-handlers)
    #?(:clj write-handler-map-provider :cljs default-write-handlers)
    #?@(:clj [(instance? HandlerMapContainer write-handlers) write-handlers])
    :else (write-handler-map write-handlers)))

(defn- read-transit [in format read-opts]
  #?(:clj  (transit/read (transit/reader in format read-opts))
     :cljs (transit/read (transit/reader format read-opts) in)))

(defn- ensure-ops-set [doc]
  (if (set? (:ops doc)) doc (clojure.core/update doc :ops set)))

(defn- parse-body [opts format read-opts body]
  (->> (binding [*base-uri* (uri/create (:url opts))]
         (read-transit body format read-opts))
       (ensure-ops-set)))

(defn- content-type-ex-info [opts content-type status]
  (ex-info (str
             (if content-type
               (str "Invalid Content-Type " content-type " ")
               (str "Missing Content-Type "))
             "while fetching " (:url opts) ".")
           {:content-type content-type
            :uri (uri/create (:url opts))
            :status status}))

(defn- parse-response [read-opts {:keys [opts headers status] :as resp}]
  (let [content-type (:content-type headers)]
    (if-let [format (media-types content-type)]
      (clojure.core/update resp :body #(parse-body opts format read-opts %))
      (throw (content-type-ex-info opts content-type status)))))

(defn- error-ex-data [opts error]
  {:error error :uri (uri/create (:url opts))})

(defn- status-ex-data [opts status body]
  {:status status :uri (uri/create (:url opts)) :body body})

#?(:clj
   (s/defn ^:private callback [ch process-fn :- ProcessFn]
     (fn [resp]
       (try
         (async/put! ch (process-fn resp))
         (catch Throwable t (async/put! ch t)))
       (async/close! ch))))

;; ---- Fetch -----------------------------------------------------------------

(defn- fetch-error-ex-info [opts error]
  (ex-info (str "Error while fetching the resource at " (:url opts) ": " error)
           (error-ex-data opts error)))

(defn- fetch-status-ex-info [opts status body]
  (ex-info (str "Non-ok status " status " while fetching the resource at "
                (:url opts))
           (status-ex-data opts status body)))

(s/defn ^:private process-fetch-resp :- Representation
  [read-opts {:keys [opts error status headers] :as resp}]
  (when error
    (throw (fetch-error-ex-info opts error)))
  (letk [[body] (parse-response read-opts resp)]
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
          ch (async/chan)
          headers {"Accept" "application/transit+json"}
          read-opts {:handlers (mk-read-handlers (:read-handlers opts))}]
      #?(:clj (debug "Fetch" uri))
      #?(:clj
         (http/request
           (merge-with merge
             {:method :get
              :url (str uri)
              :headers headers
              :as :stream}
             opts)
           (callback ch #(process-fetch-resp read-opts %)))
         :cljs
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
                          (process-fetch-resp read-opts)
                          (async/put! ch))
                 (catch js/Error e (async/put! ch e)))
               (async/close! ch)))
           (.send xhr uri "GET" nil (clj->js (merge headers (:headers opts))))))
      ch)))

;; ---- Query -----------------------------------------------------------------

(s/defn execute
  "Executes the query using args and optional opts.

  Returns a channel conveying the result of the query.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([query :- Query args :- Args] (execute query args {}))
  ([query :- Query args :- Args opts :- Opts]
    (let [write-opts {:handlers (mk-write-handlers (:write-handlers opts))}]
      #?(:clj
         (let [ch (async/chan)
               read-opts {:handlers (mk-read-handlers (:read-handlers opts))}]
           (http/request
             (merge-with merge
               {:method :get
                :url (str (:href query))
                :headers {"Accept" "application/transit+json"}
                :query-params (map-vals (partial t/write-str write-opts) args)
                :as :stream}
               opts)
             (callback ch #(process-fetch-resp read-opts %)))
           ch)
         :cljs
         (fetch (util/set-query! write-opts (:href query) args) opts)))))

;; ---- Create ----------------------------------------------------------------

(defn- create-error-ex-info [opts error]
  (ex-info (str "Error while creating a resource using " (:url opts) ": " error)
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
  [read-opts {:keys [opts error status headers] :as resp}]
  (when error
    (throw (create-error-ex-info opts error)))
  (when (not= 201 status)
    (throw (create-status-ex-info opts status (:body (parse-response read-opts resp)))))
  (if-let [location (:location headers)]
    (uri/resolve (uri/create (:url opts)) location)
    (throw (missing-location-ex-info opts))))

(s/defn create
  "Creates a resource as described by the form using args and optional opts.

  Returns a channel conveying the client-side representation of the resource
  created."
  ([form :- Form args :- Args] (create form args {}))
  ([form :- Form args :- Args opts :- Opts]
    (let [ch (async/chan)
          headers {"Accept" "application/transit+json"
                   "Content-Type" "application/transit+json"}
          read-opts {:handlers (mk-read-handlers (:read-handlers opts))}
          write-opts {:handlers (mk-write-handlers (:write-handlers opts))}]
      #?(:clj
         (http/request
           (merge-with merge
             {:method :post
              :url (str (:href form))
              :headers headers
              :body (io/input-stream (t/write write-opts args))
              :follow-redirects false
              :as :stream}
             opts)
           (callback ch #(process-create-resp read-opts %)))
         :cljs
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
                      (process-create-resp read-opts)
                      (async/put! ch))
                 (catch js/Error e (async/put! ch e)))
               (async/close! ch)))
           (.send xhr (:href form) "POST" (t/write write-opts args)
                  (clj->js (merge headers (:headers opts))))))
      ch)))

;; ---- Update ----------------------------------------------------------------

(defn- if-match [rep]
  (or (:etag rep) "*"))

(defn- update-error-ex-info [opts error]
  (ex-info (str "Error while updating the resource at " (:url opts) ": " error)
           (error-ex-data opts error)))

(defn- update-status-ex-info [opts status body]
  (ex-info (str "Non 204 (No Content) status " status " while updating the "
                "resource at " (:url opts))
           (status-ex-data opts status body)))

(s/defn ^:private process-update-resp :- Representation
  [read-opts {:keys [opts error status headers] :as resp} rep]
  (when error
    (throw (update-error-ex-info opts error)))
  (when (not= 204 status)
    (throw (update-status-ex-info opts status (:body (parse-response read-opts resp)))))
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
          ch (async/chan)
          headers {"Accept" "application/transit+json"
                   "Content-Type" "application/transit+json"
                   "If-Match" (if-match representation)}
          read-opts {:handlers (mk-read-handlers (:read-handlers opts))}
          write-opts {:handlers (mk-write-handlers (:write-handlers opts))}]
      #?(:clj
         (http/request
           (merge-with merge
             {:method :put
              :url (str uri)
              :headers headers
              :body (->> representation
                         remove-controls
                         remove-embedded
                         (t/write write-opts)
                         io/input-stream)
              :follow-redirects false
              :as :stream}
             opts)
           (callback ch #(process-update-resp read-opts % representation)))
         :cljs
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
                   (async/put! ch (process-update-resp read-opts resp representation)))
                 (catch js/Error e (async/put! ch e)))
               (async/close! ch)))
           (.send xhr uri "PUT"
                  (->> representation
                       remove-controls
                       remove-embedded
                       (t/write write-opts))
                  (clj->js (merge headers (:headers opts))))))
      ch)))

;; ---- Delete ----------------------------------------------------------------

(defn- delete-error-ex-info [opts error]
  (ex-info (str "Error while deleting the resource at " (:url opts) ": " error)
           (error-ex-data opts error)))

(defn- delete-status-ex-info [opts status body]
  (ex-info (str "Non 204 (No Content) status " status " while deleting the "
                "resource at " (:url opts))
           (status-ex-data opts status body)))

(defn- process-delete-resp [read-opts {:keys [opts error status] :as resp}]
  (when error
    (throw (delete-error-ex-info opts error)))
  (when (not= 204 status)
    (throw (delete-status-ex-info opts status (:body (parse-response read-opts resp))))))

(s/defn delete
  "Deletes the resource using optional opts and returns a channel which closes
  after the resource was deleted.

  Puts an ExceptionInfo onto the channel if there is any problem including non
  200 (Ok) responses. The exception data of non 200 responses contains :status
  and :body."
  ([resource :- Resource] (delete resource {}))
  ([resource :- Resource opts :- Opts]
    (let [uri (extract-uri resource)
          ch (async/chan)
          headers {"Accept" "application/transit+json"}
          read-opts {:handlers (mk-read-handlers (:read-handlers opts))}]
      #?(:clj
         (http/request
           (merge-with merge
             {:method :delete
              :url (str uri)
              :headers headers
              :follow-redirects false
              :as :stream}
             opts)
           (fn [resp]
             (try
               (process-delete-resp read-opts resp)
               (catch Throwable t (async/put! ch t)))
             (async/close! ch)))
         :cljs
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
                          (process-delete-resp read-opts)
                          (async/put! ch))
                 (catch js/Error e (async/put! ch e)))
               (async/close! ch)))
           (.send xhr uri "DELETE" nil
                  (clj->js (merge headers (:headers opts))))))
      ch)))
