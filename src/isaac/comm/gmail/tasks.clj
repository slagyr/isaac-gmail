(ns isaac.comm.gmail.tasks
  "Task routes: mail becomes a hail on a band instead of a thread session
   (isaac-3427).

   A :task route already ran through routes/decide and had its verdict label
   applied the same as any other route (labels.clj, before this namespace
   ever runs). This namespace turns the gated message into a hail record —
   {:frequencies {:band ...} :params {...}} — for isaac.hail.queue/send!,
   resolved at runtime since isaac-hail is not a dependency of isaac-gmail.
   When the hail module isn't installed, warn-log and mark the message
   /unsent instead of raising — the message stays fully readable through the
   gmail__read tool by id either way."
  (:require
    [isaac.comm.gmail.api :as api]
    [isaac.comm.gmail.labels :as labels]
    [isaac.comm.gmail.rfc2822 :as rfc2822]
    [isaac.logger :as log]))

(def default-body-cap
  "gmail/task-body-cap default, in characters."
  4000)

(defn body-excerpt
  "`body`, capped at gmail/task-body-cap characters (default 4000). The full
   message stays readable through the gmail__read tool by id."
  [gmail-cfg body]
  (let [cap  (or (:gmail/task-body-cap gmail-cfg) default-body-cap)
        text (str (or body ""))]
    (subs text 0 (min (count text) cap))))

(defn resolve-hail-send!
  "isaac.hail.queue/send!, resolved at runtime since isaac-hail is not a
   dependency of isaac-gmail. Nil when the hail module isn't installed."
  []
  (try (requiring-resolve 'isaac.hail.queue/send!)
       (catch Exception _ nil)))

(defn- hail-record
  "The mail payload ({:gmail/id :gmail/thread-id :from :subject
   :body-excerpt}) merged over the route's own :params — the mail fields
   always win on a key collision."
  [gmail-cfg message route]
  {:frequencies {:band (:band route)}
   :params      (merge (:params route)
                       {:gmail/id        (:id message)
                        :gmail/thread-id (:threadId message)
                        :from            (:from message)
                        :subject         (:subject message)
                        :body-excerpt    (body-excerpt gmail-cfg (:body message))})})

(defn- reply-ack! [message route-name]
  (let [raw (api/encode-raw
              (rfc2822/reply-raw {:from       (:from message)
                                  :subject    (:subject message)
                                  :message-id (:message-id message)
                                  :body       (str "Got it — filed as " route-name ": "
                                                   (:subject message) ".")}))]
    (api/messages-send! {:raw raw :thread-id (:threadId message)})))

(defn dispatch!
  "Send the task hail, or degrade to a warn log + isaac/<route>/unsent label
   when the hail module isn't installed — never an exception. When the route
   says :ack true, also reply once on the thread; the default sends no
   reply."
  [tenant gmail-cfg message route]
  (if-let [send! (resolve-hail-send!)]
    (do
      (send! (hail-record gmail-cfg message route))
      (when (:ack route)
        (reply-ack! message (:route route))))
    (do
      (log/warn :gmail.route/hail-unavailable :route (:route route) :id (:id message))
      (labels/apply-label! tenant gmail-cfg message (str (:route route) "/unsent")))))
