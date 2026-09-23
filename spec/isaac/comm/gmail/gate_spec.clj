(ns isaac.comm.gmail.gate-spec
  (:require
    [isaac.comm.gmail.gate :as sut]
    [speclj.core :refer :all]))

(describe "gmail inbound gate"

  (describe "not-inbox?"

    (it "is false for INBOX mail"
      (should-not (sut/not-inbox? {:label-ids ["INBOX"]})))

    (it "is true for sent mail"
      (should (sut/not-inbox? {:label-ids ["SENT"]})))

    (it "is true for drafts"
      (should (sut/not-inbox? {:label-ids ["DRAFT"]})))

    (it "is true when INBOX is missing entirely"
      (should (sut/not-inbox? {:label-ids ["STARRED"]}))))

  (describe "address"

    (it "reads the address out of a display-name From header"
      (should= "ada@tonotop.com" (sut/address "Ada Lovelace <ADA@Tonotop.com>")))

    (it "lower-cases a bare address"
      (should= "ada@tonotop.com" (sut/address "Ada@Tonotop.com"))))

  (describe "authenticated? (isaac-dymn)"

    (def dmarc-pass "mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com; dmarc=pass header.from=tonotop.com")

    (it "vouches for a domain Gmail's dmarc passed"
      (should (sut/authenticated? {:auth-results dmarc-pass} "tonotop.com")))

    (it "does not vouch with no Authentication-Results at all"
      (should-not (sut/authenticated? {} "tonotop.com")))

    (it "does not vouch when dkim and spf both fail"
      (should-not (sut/authenticated?
                    {:auth-results "mx.google.com; dkim=none; spf=softfail; dmarc=fail header.from=tonotop.com"}
                    "tonotop.com")))

    (it "vouches when spf+dkim both pass and are aligned to the domain"
      (should (sut/authenticated?
                {:auth-results "mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com"}
                "tonotop.com")))

    (it "does not vouch when spf+dkim pass for somebody else's domain"
      (should-not (sut/authenticated?
                    {:auth-results "mx.google.com; dkim=pass header.d=mallory.example; spf=pass smtp.mailfrom=mallory.example"}
                    "tonotop.com")))))
