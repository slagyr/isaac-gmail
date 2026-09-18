(ns isaac.comm.gmail.message
  "Decode a Gmail messages.get payload into from/to/subject/body/message-id."
  (:require
    [clojure.string :as str]))

(defn- header [headers name]
  (some (fn [h]
          (when (= (str/lower-case (or (:name h) (get h "name") ""))
                   (str/lower-case name))
            (or (:value h) (get h "value"))))
        headers))

(defn- payload-headers [message]
  (or (get-in message [:payload :headers])
      (get-in message ["payload" "headers"])
      (:headers message)
      []))

(defn- decode-body [data]
  (when (seq data)
    (let [decoder (java.util.Base64/getUrlDecoder)]
      (try
        (String. (.decode decoder (str data)) "UTF-8")
        (catch Exception _
          data)))))

(defn- first-text-part [payload]
  (or (when-let [data (get-in payload [:body :data])]
        (decode-body data))
      (some (fn [part]
              (let [mime (or (:mimeType part) (get part "mimeType"))]
                (when (or (nil? mime) (re-find #"text/plain" (str mime)))
                  (decode-body (or (get-in part [:body :data])
                                   (get-in part ["body" "data"]))))))
            (or (:parts payload) (get payload "parts") []))))

(defn- compact [m]
  (into {} (remove (fn [[_ v]] (or (nil? v) (= [] v))) m)))

(defn from-api
  "Normalize a Gmail messages.get body (or a test stub map) into a gate/dispatch message."
  [raw]
  (let [headers (payload-headers raw)
        payload (or (:payload raw) (get raw "payload") {})]
    (compact
      {:id         (or (:id raw) (get raw "id"))
       :threadId   (or (:threadId raw) (:thread-id raw) (get raw "threadId"))
       :historyId  (or (:historyId raw) (:history-id raw) (get raw "historyId"))
       :labelIds   (or (:labelIds raw) (:label-ids raw) (get raw "labelIds"))
       :from       (or (:from raw) (header headers "From"))
       :to         (or (:to raw) (header headers "To"))
       :subject    (or (:subject raw) (header headers "Subject"))
       :message-id (or (:message-id raw) (header headers "Message-ID") (header headers "Message-Id"))
       :body       (or (when (string? (:body raw)) (:body raw))
                       (first-text-part payload)
                       (:snippet raw))})))
