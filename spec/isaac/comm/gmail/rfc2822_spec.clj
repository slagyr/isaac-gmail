(ns isaac.comm.gmail.rfc2822-spec
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.rfc2822 :as sut]
    [speclj.core :refer :all]))

(describe "RFC 2822 reply building"

  (it "threads a reply with To, Re: subject, In-Reply-To and References"
    (let [raw (sut/reply-raw {:from    "ada@tonotop.com"
                              :to      "yopp@tonotop.com"
                              :subject "Deploy window"
                              :message-id "<abc@tonotop.com>"
                              :body    "Friday works."})]
      (should (str/includes? raw "To: ada@tonotop.com"))
      (should (str/includes? raw "Subject: Re: Deploy window"))
      (should (str/includes? raw "In-Reply-To: <abc@tonotop.com>"))
      (should (str/includes? raw "References: <abc@tonotop.com>"))
      (should (str/includes? raw "Friday works."))
      (should-not (str/includes? raw "Subject: Re: Re:"))))

  (it "does not double Re: when the original already has it"
    (let [raw (sut/reply-raw {:from "ada@tonotop.com" :subject "Re: Lunch" :body "Yes."})]
      (should (str/includes? raw "Subject: Re: Lunch"))
      (should-not (str/includes? raw "Subject: Re: Re:"))))
  )
