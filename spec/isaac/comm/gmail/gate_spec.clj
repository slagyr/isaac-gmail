(ns isaac.comm.gmail.gate-spec
  (:require
    [isaac.comm.gmail.gate :as sut]
    [speclj.core :refer :all]))

(def allow ["ada@tonotop.com"])

(describe "gmail inbound gate"

  (it "accepts an INBOX message from an allowed sender"
    (should-be-nil (sut/drop-reason {:from "ada@tonotop.com" :label-ids ["INBOX"]} allow)))

  (it "drops sent mail as :not-inbox"
    (should= :not-inbox (sut/drop-reason {:from "ada@tonotop.com" :label-ids ["SENT"]} allow)))

  (it "drops unknown senders as :sender"
    (should= :sender (sut/drop-reason {:from "mallory@example.com" :label-ids ["INBOX"]} allow)))

  (it "fails closed when allow-from is empty"
    (should= :sender (sut/drop-reason {:from "ada@tonotop.com" :label-ids ["INBOX"]} [])))

  (it "drops drafts"
    (should= :not-inbox (sut/drop-reason {:from "ada@tonotop.com" :label-ids ["DRAFT"]} allow)))
  )
