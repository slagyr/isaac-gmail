(ns isaac.comm.gmail
  "Gmail comm factory. Inbound lives on the google handler; on-reply
   posts a reply on the originating thread."
  (:require
    [clojure.string :as str]
    [isaac.comm.factory :as factory]
    [isaac.comm.gmail.api :as api]
    [isaac.comm.gmail.rfc2822 :as rfc2822]
    [isaac.comm.protocol :as comm]
    [isaac.config.root :as root]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defonce ^:private origin-by-session (atom {}))

(defn remember-origin! [session-key origin]
  (when (and session-key (= :gmail (:kind origin)))
    (swap! origin-by-session assoc session-key origin)))

(defn- send-reply! [origin text]
  (let [raw (api/encode-raw (rfc2822/reply-raw {:from       (:from origin)
                                                :subject    (:subject origin)
                                                :message-id (or (:rfc-id origin) (:message-id origin))
                                                :body       text}))]
    (api/messages-send! {:raw raw :thread-id (or (:thread-id origin) (:threadId origin))})))

(defn- send!* [_comm record]
  (try
    (let [text   (str/trim (str (:content record)))
          origin (or (get @origin-by-session (:session-key record))
                     (select-keys record [:from :subject :message-id :thread-id :rfc-id :kind]))]
      (cond
        (str/blank? text)
        {:ok false :transient? false}

        :else
        (do (send-reply! origin text)
            {:ok true})))
    (catch Exception e
      (log/error :gmail.send/failed :error (.getMessage e))
      {:ok false :transient? true :error (.getMessage e)})))

(defn- on-cycle-start* [_comm session-key cycle]
  (when-let [origin (:origin cycle)]
    (remember-origin! session-key origin)))

(defn- on-reply* [_comm session-key text]
  (when-let [origin (get @origin-by-session session-key)]
    (when (seq (str/trim (str text)))
      (try
        (send-reply! origin text)
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
