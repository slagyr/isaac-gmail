(ns isaac.comm.gmail.labels
  "Verdict labels: the audit trail and, via already-routed?, the idempotency
   check that lets two hosts (push + pull) share one inbox safely (isaac-sb6d).

   `cfg` throughout is the gmail comm's own config slice (the map under
   `comms.gmail`), not the whole resolved config."
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.api :as api]))

(defonce ^:private label-ids* (atom {}))

(defn reset-cache!
  "Forget cached label ids. With no tenant, clears every tenant."
  ([] (reset! label-ids* {}))
  ([tenant] (swap! label-ids* dissoc (or tenant :default))))

(defn prefix
  "gmail/label-prefix, default \"isaac\"."
  [cfg]
  (or (:gmail/label-prefix cfg) "isaac"))

(defn route-label
  "The Gmail label name a route's verdict is recorded under."
  [cfg route-name]
  (str (prefix cfg) "/" route-name))

(defn- message-labels [message]
  (set (map name (or (:labelIds message) (:label-ids message) []))))

(defn already-routed?
  "True when `message` already carries any isaac/<prefix>-owned label — the
   signal that an earlier pass (this host or the other) already routed it."
  [cfg message]
  (let [p (str (prefix cfg) "/")]
    (boolean (some #(str/starts-with? % p) (message-labels message)))))

(defn- create-and-cache! [tenant name]
  (let [created (api/labels-create! name)
        id      (or (:id created) (get created "id") name)]
    (swap! label-ids* assoc-in [(or tenant :default) name] id)
    id))

(defn label-id!
  "The Gmail label id for `name`, creating it via labels-create! at most once
   per tenant and reusing the id for every message after (isaac-sb6d)."
  [tenant name]
  (or (get-in @label-ids* [(or tenant :default) name])
      (create-and-cache! tenant name)))

(defn apply-label!
  "Apply the verdict label for `route-name` to `message`, creating the label
   on first use and removing UNREAD only when `remove-unread?`. Called BEFORE
   any turn starts, so the label is applied whether the message converses,
   is ignored, or is unrouted."
  [tenant cfg message route-name & {:keys [remove-unread?]}]
  (let [id (label-id! tenant (route-label cfg route-name))]
    (api/messages-modify! (:id message)
                          {:add    [id]
                           :remove (when remove-unread? ["UNREAD"])})))
