(ns isaac.comm.gmail.watch
  "Watch registration entry for :isaac.google/registration."
  (:require
    [isaac.comm.gmail.cursor :as cursor]))

(def WATCH-URL "https://www.googleapis.com/gmail/v1/users/me/watch")

(defn registration-entry [{:keys [topic]}]
  {:type  :gmail/watch
   :label "INBOX"
   :topic topic
   :renew :weekly
   :url   WATCH-URL
   :body  {:topicName    topic
           :labelIds     ["INBOX"]
           :labelFilterAction "include"}})

(defn on-watch-response! [root watch]
  (cursor/seed-from-watch! root watch))
