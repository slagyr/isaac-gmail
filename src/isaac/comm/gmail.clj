(ns isaac.comm.gmail
  "Gmail comm factory. Inbound lives on the google handler; on-reply
   posts a reply on the originating thread.

   A comm speaks for one Google organization, so every send runs as that
   organization and uses its token (isaac-1zkz)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [isaac.comm.factory :as factory]
    [isaac.comm.gmail.api :as api]
    [isaac.comm.gmail.rfc2822 :as rfc2822]
    [isaac.comm.gmail.tenant :as tenant]
    [isaac.comm.protocol :as comm]
    [isaac.config.root :as root]
    [isaac.google.tenants :as tenants]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defonce ^:private origin-by-session (atom {}))

;; Sessions whose model already answered the origin with gmail__send this
;; turn (isaac-3t0z). That send is the reply; on-reply* stays silent.
(defonce ^:private replied-via-tool (atom #{}))

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

(defn- send-reply! [origin text]
  (let [raw (api/encode-raw (rfc2822/reply-raw {:from       (:from origin)
                                                :subject    (:subject origin)
                                                :message-id (or (:rfc-id origin) (:message-id origin))
                                                :body       text}))]
    (api/messages-send! {:raw raw :thread-id (or (:thread-id origin) (:threadId origin))})))

(defn- send!* [comm record]
  (try
    (let [text   (str/trim (str (:content record)))
          origin (or (get @origin-by-session (:session-key record))
                     (select-keys record [:from :subject :message-id :thread-id :rfc-id :kind]))]
      (cond
        (str/blank? text)
        {:ok false :transient? false}

        :else
        (do (as-comm-organization comm #(send-reply! origin text))
            {:ok true})))
    (catch Exception e
      (log/error :gmail.send/failed :error (.getMessage e))
      {:ok false :transient? true :error (.getMessage e)})))

(defn- on-cycle-start* [_comm session-key cycle]
  (when-let [origin (:origin cycle)]
    (remember-origin! session-key origin)))

(defn- tool-args [arguments]
  (let [arguments (if (string? arguments)
                    (try (json/parse-string arguments) (catch Exception _ {}))
                    arguments)]
    (reduce-kv (fn [m k v] (assoc m (name k) v)) {} (or arguments {}))))

(defn reply-to-origin?
  "True when `tool-call` is a gmail__send replying to the origin message:
   that send is the turn's reply (isaac-3t0z)."
  [origin tool-call]
  (let [reply-to (some-> (get (tool-args (:arguments tool-call)) "reply-to-id") str str/trim)]
    (boolean (and (= "gmail__send" (:name tool-call))
                  (seq reply-to)
                  (= reply-to (:message-id origin))))))

(defn- on-tool-call* [_comm session-key tool-call]
  (when (reply-to-origin? (get @origin-by-session session-key) tool-call)
    (swap! replied-via-tool conj session-key)))

(defn- on-reply* [comm session-key text]
  (when-let [origin (get @origin-by-session session-key)]
    (if (contains? @replied-via-tool session-key)
      (log/debug :gmail/reply-deduped :session session-key)
      (when (seq (str/trim (str text)))
      (try
        (as-comm-organization comm #(send-reply! origin text))
        (catch Exception e
          (log/error :gmail.reply/failed :error (.getMessage e))))))))

(defn- on-turn-end* [_comm session-key _result]
  (swap! replied-via-tool disj session-key)
  (swap! origin-by-session dissoc session-key))

(deftype GmailComm [host cfg])

(extend GmailComm
  comm/Comm
  (merge comm/defaults
         {:send!          send!*
          :on-cycle-start on-cycle-start*
          :on-tool-call   on-tool-call*
          :on-reply       on-reply*
          :on-turn-end    on-turn-end*}))

(defn make [host]
  (->GmailComm host (atom nil)))

(defmethod factory/create :gmail [node-path slice]
  (let [comm (make {:name (last node-path)
                    :root (or (nexus/get :root) (root/current-root))})]
    (reset! (.-cfg comm) slice)
    comm))
