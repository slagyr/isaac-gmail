(ns isaac.comm.gmail.gate
  "Inbound Gmail gate: allow-from (fail closed), skip drafts/sent, INBOX only.")

(defn- labels [message]
  (set (map name (or (:label-ids message) (:labelIds message) []))))

(defn drop-reason
  "Nil when the message should start a turn; otherwise :not-inbox or :sender."
  [message allow-from]
  (let [labs   (labels message)
        from   (or (:from message) "")
        allow  (set (map str allow-from))]
    (cond
      (or (contains? labs "SENT") (contains? labs "DRAFT") (not (contains? labs "INBOX")))
      :not-inbox

      (or (empty? allow) (not (contains? allow from)))
      :sender)))
