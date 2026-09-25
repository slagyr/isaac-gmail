(ns isaac.comm.gmail.rfc2822
  "Build RFC 2822 messages for Gmail messages.send."
  (:require
    [clojure.string :as str]
    [isaac.fs :as fs])
  (:import
    (java.util Base64)))

(defn- re-subject [subject]
  (let [s (or subject "")]
    (if (re-find #"(?i)^re:\s*" s) s (str "Re: " s))))

(defn reply-raw
  "Plain-text RFC 2822 reply. Headers clients need to thread: To, Subject Re:,
   In-Reply-To, References."
  [{:keys [from subject message-id body]}]
  (str "To: " from "\r\n"
       "Subject: " (re-subject subject) "\r\n"
       (when (seq message-id)
         (str "In-Reply-To: " message-id "\r\n"
              "References: " message-id "\r\n"))
       "\r\n"
       (or body "")))

(defn message-raw
  "Plain-text RFC 2822 message that is not a reply: the subject is taken as
   written and no threading headers are added (isaac-jqk2)."
  [{:keys [to subject body]}]
  (str "To: " to "\r\n"
       "Subject: " (or subject "") "\r\n"
       "\r\n"
       (or body "")))

;; region ----- Multipart (isaac-8hi7) -----

(def ^:private extension->content-type
  {"pdf"  "application/pdf"
   "txt"  "text/plain"
   "csv"  "text/csv"
   "json" "application/json"
   "png"  "image/png"
   "jpg"  "image/jpeg"
   "jpeg" "image/jpeg"
   "gif"  "image/gif"
   "zip"  "application/zip"
   "doc"  "application/msword"
   "docx" "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
   "xls"  "application/vnd.ms-excel"
   "xlsx" "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
   "html" "text/html"
   "htm"  "text/html"})

(defn- extension [filename]
  (let [idx (str/last-index-of (or filename "") ".")]
    (when (and idx (< (inc idx) (count filename)))
      (str/lower-case (subs filename (inc idx))))))

(defn- content-type-for [filename]
  (get extension->content-type (extension filename) "application/octet-stream"))

(defn- new-boundary []
  (str "isaac-" (str (random-uuid))))

(defn- base64-body [bytes]
  (.encodeToString (Base64/getMimeEncoder) bytes))

(defn- text-part [boundary body]
  (str "--" boundary "\r\n"
       "Content-Type: text/plain; charset=\"UTF-8\"\r\n"
       "\r\n"
       (or body "") "\r\n"))

(defn- attachment-part [fs* boundary path]
  (let [filename (fs/filename path)
        size     (fs/size fs* path)
        bytes    (fs/read-bytes fs* path 0 size)]
    (str "--" boundary "\r\n"
         "Content-Type: " (content-type-for filename) "\r\n"
         "Content-Transfer-Encoding: base64\r\n"
         "Content-Disposition: attachment; filename=\"" filename "\"\r\n"
         "\r\n"
         (base64-body bytes) "\r\n")))

(defn- multipart-body [boundary body fs* attachments]
  (str (text-part boundary body)
       (apply str (map #(attachment-part fs* boundary %) attachments))
       "--" boundary "--"))

(defn multipart-message-raw
  "Multipart/mixed RFC 2822 message that is not a reply, carrying one part
   per file in :attachments (read via isaac.fs from :fs) alongside the
   text body. Same header shape as message-raw otherwise."
  [{:keys [to subject body attachments fs]}]
  (let [boundary (new-boundary)]
    (str "To: " to "\r\n"
         "Subject: " (or subject "") "\r\n"
         "MIME-Version: 1.0\r\n"
         "Content-Type: multipart/mixed; boundary=\"" boundary "\"\r\n"
         "\r\n"
         (multipart-body boundary body fs attachments))))

(defn multipart-reply-raw
  "Multipart/mixed RFC 2822 reply carrying the same threading headers as
   reply-raw, plus one part per file in :attachments (read via isaac.fs
   from :fs) alongside the text body."
  [{:keys [from subject message-id body attachments fs]}]
  (let [boundary (new-boundary)]
    (str "To: " from "\r\n"
         "Subject: " (re-subject subject) "\r\n"
         (when (seq message-id)
           (str "In-Reply-To: " message-id "\r\n"
                "References: " message-id "\r\n"))
         "MIME-Version: 1.0\r\n"
         "Content-Type: multipart/mixed; boundary=\"" boundary "\"\r\n"
         "\r\n"
         (multipart-body boundary body fs attachments))))

;; endregion
