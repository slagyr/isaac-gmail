(ns isaac.comm.gmail.tools
  "Gmail tools for the agent, on Isaac's own token and scopes (isaac-jqk2).

   Inbound stays deterministic — the gate decides whose mail starts a turn.
   These are for looking things up and answering: search the mailbox, read a
   message, send or reply, list labels."
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.message :as message]
    [isaac.comm.gmail.rfc2822 :as rfc2822]))

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

(defn send-mail
  "Reply to a message, or write a new one."
  [arguments]
  (let [args       (args-of arguments)
        to         (some-> (get args "to") str str/trim not-empty)
        subject    (some-> (get args "subject") str not-empty)
        body       (some-> (get args "body") str)
        reply-to   (some-> (get args "reply-to-id") str str/trim not-empty)]
    (cond
      (str/blank? (str (or body ""))) (error "body is required")
      (and (nil? to) (nil? reply-to)) (error "either to (a new message) or reply-to-id (a reply) is required")
      :else
      (try
        (let [original (when reply-to (message/from-api (gmail-api/messages-get! reply-to)))
              raw      (if original
                         (rfc2822/reply-raw {:from       (or to (:from original))
                                             :subject    (or subject (:subject original))
                                             :message-id (:message-id original)
                                             :body       body})
                         (rfc2822/message-raw {:to to :subject subject :body body}))
              sent     (gmail-api/messages-send! {:raw (gmail-api/encode-raw raw)
                                                  :thread-id (:threadId original)})]
          {:result {:id (:id sent) :threadId (:threadId sent)}})
        (catch Exception e
          (error (str "Gmail send failed: " (.getMessage e))))))))

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

(defn send-tool-factory [_]
  {:description (str "Send mail as this Isaac account: a reply when reply-to-id names a message "
                     "(threaded, Re: subject), otherwise a new message to `to`. Side-effecting: "
                     "it leaves the building.")
   :parameters  {:type       "object"
                 :properties {"to"          {:type "string" :description "Recipient, for a new message"}
                              "subject"     {:type "string" :description "Subject; defaults to Re: the original"}
                              "body"        {:type "string" :description "Plain-text body"}
                              "reply-to-id" {:type "string" :description "Gmail message id to reply to"}}
                 :required   ["body"]}
   :handler     #'send-mail})

(defn labels-tool-factory [_]
  {:description "List the labels in this Isaac account's mailbox."
   :parameters  {:type "object" :properties {}}
   :handler     #'labels})
