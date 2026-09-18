(ns isaac.comm.gmail.rfc2822
  "Build RFC 2822 reply messages for Gmail messages.send.")

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
