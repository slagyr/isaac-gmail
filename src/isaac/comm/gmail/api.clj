(ns isaac.comm.gmail.api
  "Gmail REST client. Feature steps redef -http! — production hits Gmail as the Google user."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str])
  (:import
    (java.net URLEncoder)
    (java.util Base64)))

(def HISTORY-URL "https://gmail.googleapis.com/gmail/v1/users/me/history")
(def MESSAGES-URL "https://gmail.googleapis.com/gmail/v1/users/me/messages")
(def SEND-URL "https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
(def LABELS-URL "https://gmail.googleapis.com/gmail/v1/users/me/labels")

(defn- parse-body [body]
  (try
    (json/parse-string body true)
    (catch Exception _
      body)))

(defn- encode [s]
  (URLEncoder/encode (str s) "UTF-8"))

(defn- with-query [url query]
  (if (seq query)
    (str url "?"
         (str/join "&" (map (fn [[k v]]
                              (str (name k) "=" (encode v)))
                            query)))
    url))

(defn encode-raw [rfc2822]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (str rfc2822) "UTF-8")))

(defn decode-raw [b64]
  (String. (.decode (Base64/getUrlDecoder) (str b64)) "UTF-8"))

(defn access-token
  "Valid Google access token. Feature steps redef this; production resolves isaac.google.token."
  []
  ((requiring-resolve 'isaac.google.token/token)))

(defn -http!
  "Internal HTTP seam. Returns {:status n :body parsed :url :method :headers}."
  [{:keys [method url headers query body]}]
  (let [http     (requiring-resolve 'babashka.http-client/request)
        full     (with-query url query)
        payload  (when body (json/generate-string body))
        opts     (cond-> {:method  (keyword (str/lower-case (name method)))
                          :uri     full
                          :headers (or headers {})
                          :throw   false}
                   payload (assoc :body payload))
        response (http opts)
        status   (:status response 0)
        parsed   (parse-body (:body response))]
    {:status  status
     :body    parsed
     :url     full
     :method  (str/upper-case (name method))
     :headers headers}))

(defn- auth-headers []
  {"Authorization" (str "Bearer " (access-token))
   "Content-Type"  "application/json"})

(defn history-list!
  "GET users.history.list from startHistoryId. Returns the parsed page or {:status 404}."
  [start-history-id]
  (let [resp (-http! {:method  "GET"
                      :url     HISTORY-URL
                      :headers (auth-headers)
                      :query   {:startHistoryId (str start-history-id)
                                :historyTypes   "messageAdded"
                                :labelId        "INBOX"}})]
    (if (= 404 (:status resp))
      {:status 404 :error :not-found}
      (:body resp))))

(defn messages-get!
  "GET users.messages.get format=full."
  [id]
  (let [resp (-http! {:method  "GET"
                      :url     (str MESSAGES-URL "/" id)
                      :headers (auth-headers)
                      :query   {:format "full"}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Gmail messages.get failed: " (:status resp))
                      {:status (:status resp) :id id :body (:body resp)})))))

(defn messages-list!
  "GET users.messages.list on INBOX, optionally q=after:<unix>."
  [{:keys [after]}]
  (let [query (cond-> {:labelIds "INBOX"}
                after (assoc :q (str "after:" after)))
        resp  (-http! {:method  "GET"
                       :url     MESSAGES-URL
                       :headers (auth-headers)
                       :query   query})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Gmail messages.list failed: " (:status resp))
                      {:status (:status resp) :body (:body resp)})))))

(defn messages-send!
  "POST users.messages.send with raw RFC 2822 + threadId."
  [{:keys [raw thread-id]}]
  (let [resp (-http! {:method  "POST"
                      :url     SEND-URL
                      :headers (auth-headers)
                      :body    {:raw raw :threadId thread-id}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Gmail messages.send failed: " (:status resp))
                      {:status (:status resp) :body (:body resp)})))))

(defn messages-search!
  "GET users.messages.list with Gmail's own query syntax (isaac-jqk2)."
  [{:keys [q limit page-token]}]
  (let [resp (-http! {:method  "GET"
                      :url     MESSAGES-URL
                      :headers (auth-headers)
                      :query   (cond-> {:maxResults (or limit 25)}
                                 (seq q)          (assoc :q q)
                                 (seq page-token) (assoc :pageToken page-token))})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Gmail messages.list failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :q q})))))

(defn labels-list!
  "GET users.labels.list."
  []
  (let [resp (-http! {:method "GET" :url LABELS-URL :headers (auth-headers)})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Gmail labels.list failed: " (:status resp))
                      {:status (:status resp) :body (:body resp)})))))

(defn labels-create!
  "POST users.labels.create. Gmail's own reply carries the label's real id;
   callers cache it (isaac-sb6d)."
  [name]
  (let [resp (-http! {:method  "POST"
                      :url     LABELS-URL
                      :headers (auth-headers)
                      :body    {:name name :labelListVisibility "labelShow" :messageListVisibility "show"}})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Gmail labels.create failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :name name})))))

(defn messages-modify!
  "POST users.messages.modify — add/remove label ids on one message."
  [id {:keys [add remove]}]
  (let [resp (-http! {:method  "POST"
                      :url     (str MESSAGES-URL "/" id "/modify")
                      :headers (auth-headers)
                      :body    (cond-> {}
                                 (seq add)    (assoc :addLabelIds (vec add))
                                 (seq remove) (assoc :removeLabelIds (vec remove)))})]
    (if (<= 200 (:status resp) 299)
      (:body resp)
      (throw (ex-info (str "Gmail messages.modify failed: " (:status resp))
                      {:status (:status resp) :body (:body resp) :id id})))))
