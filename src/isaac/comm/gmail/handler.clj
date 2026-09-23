(ns isaac.comm.gmail.handler
  "Berth :isaac.google/handler for :gmail/watch — walk history, gate, route,
   label, and start turns.

   Every gated message runs the same pipeline before any turn starts: is it
   even INBOX mail (gate/not-inbox?), has an earlier pass already routed it
   (labels/already-routed?), then isaac.comm.gmail.routes/decide picks a
   route, and the verdict label is applied — before dispatch — whether the
   message converses, is ignored, or is unrouted (isaac-sb6d)."
  (:require
    [clojure.string :as str]
    [isaac.api :as api]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gmail :as gmail]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.cursor :as cursor]
    [isaac.comm.gmail.gate :as gate]
    [isaac.comm.gmail.history :as history]
    [isaac.comm.gmail.labels :as labels]
    [isaac.comm.gmail.message :as message]
    [isaac.comm.gmail.routes :as routes]
    [isaac.comm.gmail.tasks :as tasks]
    [isaac.comm.gmail.triage :as triage]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.defaults :as defaults]
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

(defn- tenant-of [cfg]
  (:gmail/google (gmail-slice cfg)))

(defn- ignore-marks-read? [cfg]
  (let [slice (gmail-slice cfg)]
    (if (contains? slice :gmail/ignore-marks-read)
      (boolean (:gmail/ignore-marks-read slice))
      true)))

(defn- crew-name
  "A crew id as the string the drive wants; nil when nothing was configured."
  [c]
  (cond (keyword? c) (name c)
        (and (string? c) (seq c)) c))

(defn- default-crew
  "The comm's own crew, else the operator's default crew
   ([:defaults :frequencies :crew]), else nil - the drive resolves nil to that
   default itself and there is no crew named main (isaac-rfmh, isaac-zule)."
  [cfg]
  (let [slice (gmail-slice cfg)]
    (or (:gmail/crew slice)
        (:crew slice)
        (crew-name (defaults/crew-id cfg)))))

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

(defn- ensure-session! [msg crew-id]
  (let [key (session-key (:threadId msg))]
    (or (api/get-session key)
        (api/create-session! key {:channel "gmail"
                                  :crew    crew-id
                                  :origin  (origin msg)}))
    key))

(defn- start-turn! [msg cfg crew-id]
  (let [key    (ensure-session! msg crew-id)
        origin (origin msg)
        ch     (live-comm cfg)]
    (gmail/remember-origin! key origin)
    (api/dispatch! (cond-> {:session-key key
                            :input       (user-text msg)
                            :origin      origin
                            :crew        crew-id
                            :config      cfg}
                     ch (assoc :comm ch)))))

(defn- label-message! [cfg merged decision]
  (labels/apply-label! (tenant-of cfg) (gmail-slice cfg) merged (:route decision)
                       :remove-unread? (and (= :ignore (:action decision)) (ignore-marks-read? cfg))))

(declare dispatch-decision!)

(defn- triage-decision!
  "The message is :unrouted and gmail/triage is configured: run the triage
   turn, always label isaac/triage/<verdict>, log one line, and — with
   gmail/triage.apply true and the verdict naming a real route — redispatch
   through that route's own action so its own label lands too (isaac-betb)."
  [cfg merged]
  (let [verdict (triage/decide! cfg merged)
        route   (when (triage/apply? cfg) (routes/find-route cfg verdict))]
    (labels/apply-label! (tenant-of cfg) (gmail-slice cfg) merged (str "triage/" verdict))
    (log/info :gmail/triage-verdict :id (:id merged) :from (:from merged)
             :subject (:subject merged) :verdict verdict :applied? (boolean route))
    (when route
      (dispatch-decision! cfg merged (routes/route-decision route)))))

(defn- dispatch-decision! [cfg merged decision]
  (if (and (= :unrouted (:action decision))
          (empty? (:blocked decision))
          (triage/configured? cfg))
    (triage-decision! cfg merged)
    (do
      (label-message! cfg merged decision)
      (case (:action decision)
        :converse (start-turn! merged cfg (or (:crew decision) (default-crew cfg)))
        :task     (tasks/dispatch! (tenant-of cfg) (gmail-slice cfg) merged decision)
        :unrouted (if (seq (:blocked decision))
                   (log/warn :gmail/message-dropped :reason :unauthenticated :id (:id merged) :from (:from merged))
                   (log/info :gmail/unrouted :from (:from merged) :subject (:subject merged)))
        :ignore   nil))))

(defn- enrich [stub]
  ;; The fetched record is authoritative; the history-walk stub only fills
  ;; gaps (it defaults :labelIds to ["INBOX"], which would otherwise erase a
  ;; verdict label a prior pass already applied).
  (let [full (try (gmail-api/messages-get! (:id stub))
                  (catch Exception _ {}))]
    (message/from-api (merge stub full))))

(defn- handle-enriched! [merged cfg]
  (cond
    (gate/not-inbox? merged)
    (log/debug :gmail/message-dropped :reason :not-inbox :id (:id merged))

    (labels/already-routed? (gmail-slice cfg) merged)
    (log/debug :gmail/already-routed :id (:id merged))

    :else
    (dispatch-decision! cfg merged (routes/decide cfg merged))))

(defn process-message!
  "Gate, route, label, and (when the route converses) start a turn for one
   history-walk message stub. Public: isaac.comm.gmail.pull reuses this so a
   pull tick's pipeline is identical to a push's (isaac-u80t)."
  [stub cfg]
  (handle-enriched! (enrich stub) cfg))

(defn- newest-history-id [msgs fallback]
  (or (some-> msgs last :historyId str)
      (str fallback)))

(defn- history-num [id]
  (or (try (parse-long (str id)) (catch Exception _ nil)) id))

(defn resync!
  "Full resync from messages.list when the cursor is gone. Public:
   isaac.comm.gmail.pull calls this for the same reason process-message! is
   public (isaac-u80t)."
  [root cursor history-id cfg]
  (let [listed (gmail-api/messages-list! {})
        msgs   (mapv (fn [m]
                       (message/from-api
                         (merge m
                               (try (gmail-api/messages-get! (:id m))
                                    (catch Exception _ {})))))
                     (or (:messages listed) listed []))
        newest (newest-history-id msgs history-id)]
    (log/warn :gmail/resync :from (history-num cursor) :to (history-num newest))
    (doseq [msg msgs]
      (handle-enriched! msg cfg))
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
