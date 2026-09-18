(ns isaac.comm.gmail.history
  "Walk a Gmail users.history.list page into new messages + next cursor.")

(defn- added-messages [record]
  (or (seq (map :message (:messagesAdded record)))
      (seq (map :message (:messages-added record)))
      []))

(defn walk-page
  "Return {:messages [...] :cursor id} or {:resync? true} when the cursor is gone."
  [page]
  (if (or (= 404 (:status page)) (= :not-found (:error page)))
    {:resync? true}
    (let [msgs (->> (:history page)
                    (mapcat added-messages)
                    (remove nil?)
                    vec)]
      {:messages msgs
       :cursor   (str (or (:historyId page) (:history-id page)))})))
