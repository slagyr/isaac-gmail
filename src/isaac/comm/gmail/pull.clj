(ns isaac.comm.gmail.pull
  "gmail/mode :pull — an interval task on the shared scheduler that walks
   Gmail history from the stored cursor on hosts with no Pub/Sub push (no
   watch, no topic, no door; login alone suffices).

   A pull tick reuses the exact pipeline the watch handler uses
   (isaac.comm.gmail.handler/process-message! and resync!) so gating,
   routes, and labels are identical between :push and :pull. The only
   difference is where the *next* cursor value comes from: a push carries
   it in the notification; a pull tick reads it back off the history.list
   page it just walked (isaac.comm.gmail.history/walk-page's :cursor).

   First tick with no stored cursor seeds it from the newest history id on
   the mailbox and processes nothing — no backfill flood. A tick that fails
   (a non-2xx from history.list) logs :warn and leaves the cursor for the
   next tick to retry; it never throws, so it never fails the module."
  (:require
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.cursor :as cursor]
    [isaac.comm.gmail.handler :as handler]
    [isaac.comm.gmail.history :as history]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]))

(def task-id :gmail/pull)
(def default-interval-ms 60000)

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- runtime-root []
  (or (nexus/get :root) (root/current-root)))

(defn- gmail-slice [cfg]
  (or (get-in cfg [:comms :gmail])
      (get-in cfg [:comms "gmail"])
      {}))

(defn- live-cfg []
  (let [root (runtime-root)]
    (or (when root (:config (loader/load-config-result {:root root :fs (runtime-fs)})))
        {})))

(defn mode
  "gmail/mode for the live comm config: :push (default) or :pull."
  [cfg]
  (keyword (or (:gmail/mode (gmail-slice cfg)) :push)))

(defn pull-mode? [cfg]
  (= :pull (mode cfg)))

(defn pull-interval-ms [cfg]
  (or (:gmail/pull-interval-ms (gmail-slice cfg)) default-interval-ms))

(defn- error-page?
  "A history.list page that came back as a Gmail error body (not the special
   404 :not-found shape history/walk-page already treats as a resync)."
  [page]
  (map? (:error page)))

(defn- newest-inbox-history-id []
  (let [listed (gmail-api/messages-list! {})
        msgs   (or (:messages listed) listed [])]
    (some-> (seq msgs) last :historyId str)))

(defn- seed!
  "First tick, no cursor yet: seed it from the newest history id on the
   mailbox and process nothing."
  [root]
  (let [hid (or (newest-inbox-history-id) "0")]
    (cursor/save-cursor! root hid)
    (log/info :gmail/pull-seeded :history-id hid)
    hid))

(defn tick!
  "One pull tick."
  ([] (tick! {}))
  ([_opts]
   (let [root (runtime-root)
         cfg  (live-cfg)
         cur  (cursor/load-cursor root)]
     (if (nil? cur)
       (seed! root)
       (let [page (gmail-api/history-list! cur)]
         (cond
           (error-page? page)
           (log/warn :gmail/pull-failed :reason (get-in page [:error :message]))

           :else
           (let [walk (history/walk-page page)]
             (if (:resync? walk)
               (handler/resync! root cur cur cfg)
               (do
                 (doseq [stub (:messages walk)]
                   (handler/process-message! stub cfg))
                 (cursor/save-cursor! root (:cursor walk)))))))))))

(defn start!
  "Schedules :gmail/pull on the shared scheduler when gmail/mode is :pull.
   No-op in :push (the default), when no scheduler is installed, or when
   the task is already scheduled."
  ([] (start! (live-cfg)))
  ([cfg]
   (when (pull-mode? cfg)
     (when-let [shared (nexus/get :scheduler)]
       (when-not (some #{task-id} (map :id (scheduler/list-tasks shared)))
         (scheduler/schedule! shared
                              {:id      task-id
                               :trigger {:kind :interval :ms (pull-interval-ms cfg)}
                               :handler (fn [_] (tick!))}))
       shared))))

(defn stop!
  "Cancels :gmail/pull if it was ever scheduled. Safe to call unconditionally."
  ([] (stop! (nexus/get :scheduler)))
  ([shared]
   (when shared
     (scheduler/cancel! shared task-id))))
