(ns isaac.comm.gmail.handler
  "Berth :isaac.google/handler for :gmail/watch — walk history, gate, start turns."
  (:require
    [clojure.string :as str]
    [isaac.api :as api]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gmail :as gmail]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.cursor :as cursor]
    [isaac.comm.gmail.gate :as gate]
    [isaac.comm.gmail.history :as history]
    [isaac.comm.gmail.message :as message]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]))

(defn- feature-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- auth-root []
  (or (nexus/get :root) (root/current-root) ""))

(defn- gmail-slice [cfg]
  (or (get-in cfg [:comms :gmail])
      (get-in cfg [:comms "gmail"])
      {}))

(defn- load-cfg []
  (let [root (auth-root)
        snap (try (loader/snapshot "gmail") (catch Exception _ {}))
        cfg  (or (when (seq (gmail-slice snap)) snap)
                 (when root
                   (:config (loader/load-config-result {:root root :fs (feature-fs)})))
                 snap
                 {})]
    cfg))

(defn- allow-from [cfg]
  (let [slice (gmail-slice cfg)]
    (or (:gmail/allow-from slice)
        (:allow-from slice)
        [])))

(defn- crew [cfg]
  (let [slice (gmail-slice cfg)]
    (or (:gmail/crew slice)
        (:crew slice)
        "main")))

(defn- session-key [thread-id]
  (str "gmail-" thread-id))

(defn- user-text [msg]
  (str/join " " (remove str/blank? [(:from msg) (:subject msg) (:body msg)])))

(defn- origin [msg]
  {:kind       :gmail
   :thread-id  (:threadId msg)
   :message-id (:id msg)
   :from       (:from msg)
   :to         (:to msg)
   :subject    (:subject msg)
   :rfc-id     (:message-id msg)})

(defn- live-comm [cfg]
  (or (comm-registry/comm-for "gmail")
      (try
        (comm-factory/create [:comms :gmail] (gmail-slice cfg))
        (catch Exception _ nil))))

(defn- ensure-session! [msg cfg]
  (let [key (session-key (:threadId msg))]
    (or (api/get-session key)
        (api/create-session! key {:channel "gmail"
                                  :crew    (crew cfg)
                                  :origin  (origin msg)}))
    key))

(defn- start-turn! [msg cfg]
  (let [key    (ensure-session! msg cfg)
        origin (origin msg)
        ch     (live-comm cfg)]
    (gmail/remember-origin! key origin)
    (api/dispatch! (cond-> {:session-key key
                            :input       (user-text msg)
                            :origin      origin
                            :crew        (crew cfg)
                            :config      cfg}
                     ch (assoc :comm ch)))))

(defn- enrich [stub]
  (let [full (try (gmail-api/messages-get! (:id stub))
                  (catch Exception _ {}))]
    (message/from-api (merge full stub))))

(defn- process-message! [stub cfg]
  (let [merged (enrich stub)
        reason (gate/drop-reason merged (allow-from cfg))]
    (if reason
      (log/debug :gmail/message-dropped :reason reason :id (:id merged))
      (start-turn! merged cfg))))

(defn- newest-history-id [msgs fallback]
  (or (some-> msgs last :historyId str)
      (str fallback)))

(defn- history-num [id]
  (or (try (parse-long (str id)) (catch Exception _ nil)) id))

(defn- resync! [root cursor history-id cfg]
  (let [listed (gmail-api/messages-list! {})
        msgs   (mapv (fn [m]
                       (message/from-api
                         (merge (try (gmail-api/messages-get! (:id m))
                                     (catch Exception _ {}))
                                m)))
                     (or (:messages listed) listed []))
        newest (newest-history-id msgs history-id)]
    (log/warn :gmail/resync :from (history-num cursor) :to (history-num newest))
    (doseq [msg msgs]
      (let [reason (gate/drop-reason msg (allow-from cfg))]
        (if reason
          (log/debug :gmail/message-dropped :reason reason :id (:id msg))
          (start-turn! msg cfg))))
    (cursor/save-cursor! root newest)))

(defn handle-watch!
  "Process a Pub/Sub gmail/watch event {:data {:emailAddress :historyId}}."
  [event]
  (let [data       (or (:data event) event)
        history-id (str (or (:historyId data) (:history-id data) (:historyId event)))
        root       (auth-root)
        cursor     (cursor/load-cursor root)
        cfg        (load-cfg)]
    (when (and (seq history-id) (not= (str cursor) history-id))
      (let [page (gmail-api/history-list! cursor)
            walk (history/walk-page page)]
        (if (:resync? walk)
          (resync! root cursor history-id cfg)
          (do
            (doseq [stub (:messages walk)]
              (process-message! stub cfg))
            (cursor/save-cursor! root history-id)))))))
