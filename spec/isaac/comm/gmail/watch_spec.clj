(ns isaac.comm.gmail.watch-spec
  (:require
    [isaac.comm.gmail.watch :as sut]
    [speclj.core :refer :all]))

(describe "gmail watch registration"

  (it "contributes users.watch on INBOX to the shared topic, weekly"
    (let [entry (sut/registration-entry {:topic "projects/marigold/topics/isaac"})]
      (should= :gmail/watch (:type entry))
      (should= "INBOX" (:label entry))
      (should= "projects/marigold/topics/isaac" (:topic entry))
      (should= :weekly (:renew entry))
      (should= "https://www.googleapis.com/gmail/v1/users/me/watch" (:url entry))))

  (it "seeds the cursor from the watch response historyId"
    (let [seeded (atom nil)]
      (with-redefs [isaac.comm.gmail.cursor/seed-from-watch! (fn [_root watch]
                                                               (reset! seeded watch))]
        (sut/on-watch-response! "/root" {:historyId "900"})
        (should= "900" (:historyId @seeded)))))
  )
