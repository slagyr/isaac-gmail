(ns isaac.comm.gmail.history-spec
  (:require
    [isaac.comm.gmail.history :as sut]
    [speclj.core :refer :all]))

(describe "gmail history walk"

  (it "collects messageAdded INBOX ids and advances the cursor"
    (let [page {:historyId "1042"
                :history   [{:messagesAdded [{:message {:id "m-1" :threadId "t-1" :labelIds ["INBOX"]}}]
                             :messages      [{:id "m-1"}]}
                            {:messagesAdded [{:message {:id "m-2" :threadId "t-2" :labelIds ["INBOX"]}}]}]}
          {:keys [messages cursor]} (sut/walk-page page)]
      (should= ["m-1" "m-2"] (mapv :id messages))
      (should= "1042" cursor)))

  (it "skips label-only changes"
    (let [page {:historyId "1099"
                :history   [{:labelsAdded [{:message {:id "m-3" :labelIds ["STARRED"]}}]}]}
          {:keys [messages cursor]} (sut/walk-page page)]
      (should= [] messages)
      (should= "1099" cursor)))

  (it "treats 404 as resync"
    (should= {:resync? true} (sut/walk-page {:error :not-found :status 404})))
  )
