(ns isaac.comm.gmail
  "Gmail comm factory. Inbound lives on the google handler; on-reply
   posts a reply on the originating thread. Outbound composition
   (comm__send, isaac-iwio) goes through send!* via the comm's
   :send-schema: :gmail/thread replies on that thread, :gmail/to (+
   :gmail/subject) writes a new message, and neither falls back to
   replying on the session's own origin thread — today's default.

   A comm speaks for one Google organization, so every send runs as that
   organization and uses its token (isaac-1zkz)."
  (:require
    [clojure.string :as str]
    [isaac.comm.factory :as factory]
    [isaac.comm.gmail.api :as api]
    [isaac.comm.gmail.message :as message]
    [isaac.comm.gmail.rfc2822 :as rfc2822]
    [isaac.comm.gmail.tenant :as tenant]
    [isaac.comm.protocol :as comm]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.tenants :as tenants]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defonce ^:private origin-by-session (atom {}))

(defn remember-origin! [session-key origin]
  (when (and session-key (= :gmail (:kind origin)))
    (swap! origin-by-session assoc session-key origin)))

(defn- slice [comm]
  (or @(.-cfg comm) {}))

(defn- as-comm-organization
  "Run `f` as the organization this comm speaks for, so the token, and
   anything else that asks which organization this is, answer for it."
  [comm f]
  (binding [tenants/*tenant* (tenant/of-comm (slice comm))]
    (f)))

(def ^:private max-attachments-bytes (* 25 1024 1024))

(defn- attachments-total-bytes [fs* paths]
  (reduce + 0 (map #(fs/size fs* %) paths)))

(defn- send-reply! [origin text attachments fs*]
  (let [raw (api/encode-raw
              (if (seq attachments)
                (rfc2822/multipart-reply-raw {:from        (:from origin)
                                              :subject     (:subject origin)
                                              :message-id  (or (:rfc-id origin) (:message-id origin))
                                              :body        text
                                              :attachments attachments
                                              :fs          fs*})
                (rfc2822/reply-raw {:from       (:from origin)
                                    :subject    (:subject origin)
                                    :message-id (or (:rfc-id origin) (:message-id origin))
                                    :body       text})))]
    (api/messages-send! {:raw raw :thread-id (or (:thread-id origin) (:threadId origin))})))

(defn- send-new! [to subject text attachments fs*]
  (let [raw (api/encode-raw
              (if (seq attachments)
                (rfc2822/multipart-message-raw {:to to :subject subject :body text
                                                :attachments attachments :fs fs*})
                (rfc2822/message-raw {:to to :subject subject :body text})))]
    (api/messages-send! {:raw raw})))

(defn- last-thread-message
  "The most recent message on `thread-id`, decoded. A :gmail/thread send
   crosses the delivery queue (isaac-iwio) — nothing about the session
   that queued it survives to the worker tick that runs send!*, so the
   reply headers (From, Subject, Message-ID) come fresh from the Gmail
   API rather than from the in-memory origin cache."
  [thread-id]
  (let [thread (api/threads-get! thread-id)
        raw    (last (:messages thread))]
    (when raw (message/from-api raw))))

(defn- send-on-thread! [thread-id text attachments fs*]
  (if-let [last-msg (last-thread-message thread-id)]
    (send-reply! {:from       (:from last-msg)
                  :subject    (:subject last-msg)
                  :message-id (:message-id last-msg)
                  :thread-id  thread-id}
                 text attachments fs*)
    (throw (ex-info "Gmail thread not found" {:thread-id thread-id}))))

(defn- send!* [comm record]
  (try
    (let [text        (str/trim (str (:content record)))
          thread      (:gmail/thread record)
          to          (:gmail/to record)
          subject     (:gmail/subject record)
          attachments (seq (:attachments record))
          fs*         (when attachments (fs/instance))]
      (cond
        (str/blank? text)
        {:ok false :transient? false}

        (and attachments (> (attachments-total-bytes fs* attachments) max-attachments-bytes))
        (do (log/error :gmail.send/attachments-too-large
                       :bytes (attachments-total-bytes fs* attachments)
                       :limit max-attachments-bytes)
            {:ok false :transient? false :error "attachments exceed the 25 MB Gmail limit"})

        (some? thread)
        (do (as-comm-organization comm #(send-on-thread! thread text attachments fs*))
            {:ok true})

        (some? to)
        (if (str/blank? (str subject))
          (do (log/error :gmail.send/missing-subject :to to)
              {:ok false :transient? false :error "gmail/subject is required with gmail/to"})
          (do (as-comm-organization comm #(send-new! to subject text attachments fs*))
              {:ok true}))

        :else
        (let [origin (or (get @origin-by-session (:session-key record))
                         (select-keys record [:from :subject :message-id :thread-id :rfc-id :kind]))]
          (as-comm-organization comm #(send-reply! origin text attachments fs*))
          {:ok true})))
    (catch Exception e
      (log/error :gmail.send/failed :error (.getMessage e))
      {:ok false :transient? true :error (.getMessage e)})))

(defn- on-cycle-start* [_comm session-key cycle]
  (when-let [origin (:origin cycle)]
    (remember-origin! session-key origin)))

(defn- on-reply* [comm session-key text]
  (when-let [origin (get @origin-by-session session-key)]
    (when (seq (str/trim (str text)))
      (try
        (as-comm-organization comm #(send-reply! origin text nil nil))
        (catch Exception e
          (log/error :gmail.reply/failed :error (.getMessage e)))))))

(defn- on-turn-end* [_comm session-key _result]
  (swap! origin-by-session dissoc session-key))

(deftype GmailComm [host cfg])

(extend GmailComm
  comm/Comm
  (merge comm/defaults
         {:send!          send!*
          :on-cycle-start on-cycle-start*
          :on-reply       on-reply*
          :on-turn-end    on-turn-end*}))

(defn make [host]
  (->GmailComm host (atom nil)))

(defmethod factory/create :gmail [node-path slice]
  (let [comm (make {:name (last node-path)
                    :root (or (nexus/get :root) (root/current-root))})]
    (reset! (.-cfg comm) slice)
    comm))
