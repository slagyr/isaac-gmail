(ns isaac.comm.gmail.tools
  "Gmail tools for the agent, on Isaac's own token and scopes (isaac-jqk2).

   Inbound stays deterministic — the gate decides whose mail starts a turn.
   These are for looking things up: search the mailbox, read a message,
   list labels. Sending is comm__send's job (gmail's :send-schema,
   isaac-iwio) — gmail__send retired."
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.message :as message]))

(defn- error [message] {:isError true :error message})

(defn- args-of [arguments]
  (reduce-kv (fn [m k v] (assoc m (str/lower-case (name k)) v)) {} (or arguments {})))

(defn- summary [msg]
  (select-keys msg [:id :threadId :from :to :subject :message-id]))

(defn search
  "Gmail's own query syntax: from:, subject:, newer_than:, is:unread…"
  [arguments]
  (let [args  (args-of arguments)
        q     (some-> (get args "q") str str/trim)
        limit (or (some-> (get args "limit") str parse-long) 25)]
    (if (str/blank? q)
      (error "q is required: a Gmail search query, e.g. from:micah@tonotop.com newer_than:7d")
      (try
        (let [response (gmail-api/messages-search! {:q q :limit limit})
              ids      (mapv :id (:messages response))
              messages (mapv (fn [id]
                               (summary (message/from-api (gmail-api/messages-get! id))))
                             ids)]
          {:result {:query    q
                    :messages messages
                    :more?    (boolean (seq (str (or (:nextPageToken response) ""))))}})
        (catch Exception e
          (error (str "Gmail search failed: " (.getMessage e))))))))

(defn read-message
  "One message, decoded: headers and plain-text body."
  [arguments]
  (let [args (args-of arguments)
        id   (some-> (get args "id") str str/trim)]
    (if (str/blank? id)
      (error "id is required: a Gmail message id (from gmail__search)")
      (try
        (let [msg (message/from-api (gmail-api/messages-get! id))]
          {:result (select-keys msg [:id :threadId :from :to :subject :message-id :body :labelIds])})
        (catch Exception e
          (error (str "Gmail read failed: " (.getMessage e))))))))

(defn labels
  "The mailbox's labels."
  [_arguments]
  (try
    {:result {:labels (mapv (fn [l] (select-keys l [:id :name :type])) (:labels (gmail-api/labels-list!)))}}
    (catch Exception e
      (error (str "Gmail labels.list failed: " (.getMessage e))))))

(defn search-tool-factory [_]
  {:description (str "Search this Isaac account's mailbox with Gmail's own query syntax "
                     "(from:, subject:, newer_than:, is:unread, has:attachment). Returns "
                     "message ids and headers; read one with gmail__read.")
   :parameters  {:type       "object"
                 :properties {"q"     {:type "string" :description "Gmail search query"}
                              "limit" {:type "integer" :description "Messages to return (default 25)"}}
                 :required   ["q"]}
   :handler     #'search})

(defn read-tool-factory [_]
  {:description "Read one Gmail message by id: headers and decoded plain-text body."
   :parameters  {:type       "object"
                 :properties {"id" {:type "string" :description "Gmail message id"}}
                 :required   ["id"]}
   :handler     #'read-message})

(defn labels-tool-factory [_]
  {:description "List the labels in this Isaac account's mailbox."
   :parameters  {:type "object" :properties {}}
   :handler     #'labels})
