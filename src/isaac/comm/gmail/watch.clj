(ns isaac.comm.gmail.watch
  "The Gmail INBOX watch as an :isaac.google/registration entry.

   users.watch tells Gmail to publish INBOX changes to the shared Pub/Sub
   topic for up to seven days; it answers with a historyId and an
   expiration and offers no way to list what is watched. So the entry
   brings its own :remote view (the timer's persisted state) and stops
   through users.stop. Renewal is another users.watch."
  (:require
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.cursor :as cursor]
    [isaac.config.loader :as loader]
    [isaac.config.root :as root]
    [isaac.fs :as fs]
    [isaac.google.registration :as registration]
    [isaac.nexus :as nexus])
  (:import
    (java.time Instant)))

(def WATCH-URL "https://gmail.googleapis.com/gmail/v1/users/me/watch")
(def STOP-URL "https://gmail.googleapis.com/gmail/v1/users/me/stop")

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- runtime-root []
  (or (nexus/get :root) (root/current-root)))

(defn- gmail-slice [cfg]
  (or (get-in cfg [:comms :gmail])
      (get-in cfg [:comms "gmail"])
      {}))

(defn- load-cfg []
  (let [root (runtime-root)
        snap (try (loader/snapshot "gmail watch") (catch Exception _ {}))]
    (or (when (seq (gmail-slice snap)) snap)
        (when root
          (:config (loader/load-config-result {:root root :fs (runtime-fs)})))
        snap
        {})))

(defn- account [cfg]
  (let [slice (gmail-slice cfg)]
    (or (:gmail/account slice) (:account slice))))

(defn- topic [cfg]
  (get-in cfg [:google :topic]))

(defn- auth-headers []
  {"Authorization" (str "Bearer " (gmail-api/access-token))
   "Content-Type"  "application/json"})

(defn watch-body [topic]
  {:topicName         topic
   :labelIds          ["INBOX"]
   :labelFilterAction "include"})

(defn keys*
  "One watch per mailbox: the configured account, or nothing."
  []
  (if-let [a (account (load-cfg))] [a] []))

(defn on-watch-response! [root watch]
  (cursor/seed-from-watch! root watch))

(defn watch!
  "users.watch for `account` (create and renew are the same call). Returns
   the response body with :name and :status so the timer can judge it."
  [account]
  (let [cfg  (load-cfg)
        resp (gmail-api/-http! {:method  "POST"
                                :url     WATCH-URL
                                :headers (auth-headers)
                                :body    (watch-body (topic cfg))})
        body (if (map? (:body resp)) (:body resp) {})
        ok?  (<= 200 (:status resp 0) 299)]
    (when ok?
      (on-watch-response! (runtime-root) body))
    (cond-> (assoc body :name account :status (:status resp))
      (not ok?) (assoc :message (or (get-in body [:error :message]) (str "users.watch " (:status resp)))))))

(defn stop! [_account]
  (gmail-api/-http! {:method "POST" :url STOP-URL :headers (auth-headers)}))

(defn expiry
  "Gmail answers with :expiration in epoch milliseconds."
  [result]
  (or (some-> (:expiration result) str parse-long Instant/ofEpochMilli)
      (:expires-at result)))

(defn remote
  "What the timer itself remembered: Gmail cannot list watches. The state
   file is shared with Chat; a watch is keyed by its mailbox, so an @ in
   the key marks it as ours (Chat keys are spaces/…)."
  []
  (into {}
        (filter (fn [[k _]] (clojure.string/includes? (str k) "@")))
        (registration/load-state (runtime-root))))
