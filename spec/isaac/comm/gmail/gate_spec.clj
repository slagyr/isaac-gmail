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
      (should= "ada@marigold.example" (sut/address "Ada Lovelace <ADA@Marigold.example>")))

    (it "lower-cases a bare address"
      (should= "ada@marigold.example" (sut/address "Ada@Marigold.example"))))

  (describe "authenticated? (isaac-dymn)"

    (def dmarc-pass "mx.google.com; dkim=pass header.d=marigold.example; spf=pass smtp.mailfrom=marigold.example; dmarc=pass header.from=marigold.example")

    (it "vouches for a domain Gmail's dmarc passed"
      (should (sut/authenticated? {:auth-results dmarc-pass} "marigold.example")))

    (it "does not vouch with no Authentication-Results at all"
      (should-not (sut/authenticated? {} "marigold.example")))

    (it "does not vouch when dkim and spf both fail"
      (should-not (sut/authenticated?
                    {:auth-results "mx.google.com; dkim=none; spf=softfail; dmarc=fail header.from=marigold.example"}
                    "marigold.example")))

    (it "vouches when spf+dkim both pass and are aligned to the domain"
      (should (sut/authenticated?
                {:auth-results "mx.google.com; dkim=pass header.d=marigold.example; spf=pass smtp.mailfrom=marigold.example"}
                "marigold.example")))

    (it "does not vouch when spf+dkim pass for somebody else's domain"
      (should-not (sut/authenticated?
                    {:auth-results "mx.google.com; dkim=pass header.d=mallory.example; spf=pass smtp.mailfrom=mallory.example"}
                    "marigold.example")))))
