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

  (it "reads the address out of a display-name From header"
    (should-be-nil (sut/drop-reason {:from "Ada Lovelace <ada@tonotop.com>" :label-ids ["INBOX"]} allow))
    (should= "ada@tonotop.com" (sut/address "Ada Lovelace <ADA@Tonotop.com>")))

  (context "*@domain patterns (isaac-dymn)"

    (def pattern ["*@tonotop.com"])
    (def dmarc-pass "mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com; dmarc=pass header.from=tonotop.com")

    (it "admits a domain sender Gmail authenticates"
      (should-be-nil (sut/drop-reason {:from         "Grace <grace@tonotop.com>"
                                       :label-ids    ["INBOX"]
                                       :auth-results dmarc-pass}
                                      pattern)))

    (it "drops a forged From in the pattern's domain as :unauthenticated"
      (should= :unauthenticated
               (sut/drop-reason {:from         "grace@tonotop.com"
                                 :label-ids    ["INBOX"]
                                 :auth-results "mx.google.com; dkim=none; spf=softfail; dmarc=fail header.from=tonotop.com"}
                                pattern)))

    (it "drops a domain sender with no Authentication-Results at all"
      (should= :unauthenticated
               (sut/drop-reason {:from "grace@tonotop.com" :label-ids ["INBOX"]} pattern)))

    (it "accepts spf+dkim when both pass and are aligned to the From domain"
      (should-be-nil (sut/drop-reason {:from         "grace@tonotop.com"
                                       :label-ids    ["INBOX"]
                                       :auth-results "mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com"}
                                      pattern)))

    (it "refuses spf+dkim that pass for somebody else's domain"
      (should= :unauthenticated
               (sut/drop-reason {:from         "grace@tonotop.com"
                                 :label-ids    ["INBOX"]
                                 :auth-results "mx.google.com; dkim=pass header.d=mallory.example; spf=pass smtp.mailfrom=mallory.example"}
                                pattern)))

    (it "drops an address outside the pattern's domain as :sender, authenticated or not"
      (should= :sender
               (sut/drop-reason {:from         "mallory@example.com"
                                 :label-ids    ["INBOX"]
                                 :auth-results dmarc-pass}
                                pattern)))

    (it "does not match a domain that merely ends with the pattern's"
      (should= :sender
               (sut/drop-reason {:from "eve@nottonotop.com" :label-ids ["INBOX"] :auth-results dmarc-pass}
                                pattern)))

    (it "takes an exact entry at its word, as it always has"
      (should-be-nil (sut/drop-reason {:from "ada@tonotop.com" :label-ids ["INBOX"]} allow)))

    (it "still fails closed with no entries"
      (should= :sender (sut/drop-reason {:from         "grace@tonotop.com"
                                         :label-ids    ["INBOX"]
                                         :auth-results dmarc-pass}
                                        []))))
  )
