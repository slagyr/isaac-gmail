(ns isaac.comm.gmail.rfc2822-spec
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.rfc2822 :as sut]
    [isaac.fs :as fs]
    [speclj.core :refer :all])
  (:import
    (java.util Base64)))

(defn- fs-with [& path-content-pairs]
  (let [fs* (fs/mem-fs)]
    (doseq [[path content] (partition 2 path-content-pairs)]
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path content))
    fs*))

(defn- b64 [s]
  (.encodeToString (Base64/getEncoder) (.getBytes (str s) "UTF-8")))

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

(describe "RFC 2822 multipart building (isaac-8hi7)"

  (it "builds a multipart/mixed new message with a text part and one attachment part"
    (let [fs*  (fs-with "/cwd/report.pdf" "%PDF-1.4 stub")
          raw  (sut/multipart-message-raw {:to          "grace@tonotop.com"
                                           :subject     "Report"
                                           :body        "Attached."
                                           :attachments ["/cwd/report.pdf"]
                                           :fs          fs*})]
      (should (str/includes? raw "To: grace@tonotop.com"))
      (should (str/includes? raw "Subject: Report"))
      (should (str/includes? raw "MIME-Version: 1.0"))
      (should (re-find #"(?i)Content-Type: multipart/mixed; boundary=\"[^\"]+\"" raw))
      (should (str/includes? raw "Content-Type: text/plain; charset=\"UTF-8\""))
      (should (str/includes? raw "Attached."))
      (should (str/includes? raw "Content-Disposition: attachment; filename=\"report.pdf\""))
      (should (str/includes? raw "Content-Type: application/pdf"))
      (should (str/includes? raw "Content-Transfer-Encoding: base64"))
      (should (str/includes? raw (b64 "%PDF-1.4 stub")))))

  (it "falls back to application/octet-stream for an unrecognized extension"
    (let [fs* (fs-with "/cwd/data.xyz" "abc")
          raw (sut/multipart-message-raw {:to          "grace@tonotop.com"
                                          :subject     "Data"
                                          :body        "Here."
                                          :attachments ["/cwd/data.xyz"]
                                          :fs          fs*})]
      (should (str/includes? raw "Content-Type: application/octet-stream"))))

  (it "builds a multipart reply carrying the threading headers and an attachment"
    (let [fs* (fs-with "/cwd/report.pdf" "%PDF-1.4 stub")
          raw (sut/multipart-reply-raw {:from        "ada@tonotop.com"
                                        :subject     "Deploy window"
                                        :message-id  "<abc@tonotop.com>"
                                        :body        "See attached."
                                        :attachments ["/cwd/report.pdf"]
                                        :fs          fs*})]
      (should (str/includes? raw "To: ada@tonotop.com"))
      (should (str/includes? raw "Subject: Re: Deploy window"))
      (should (str/includes? raw "In-Reply-To: <abc@tonotop.com>"))
      (should (str/includes? raw "References: <abc@tonotop.com>"))
      (should (str/includes? raw "See attached."))
      (should (str/includes? raw "Content-Disposition: attachment; filename=\"report.pdf\""))))

  (it "carries more than one attachment as separate parts"
    (let [fs* (fs-with "/cwd/a.txt" "AAA" "/cwd/b.png" "BBB")
          raw (sut/multipart-message-raw {:to          "grace@tonotop.com"
                                          :subject     "Files"
                                          :body        "Two files."
                                          :attachments ["/cwd/a.txt" "/cwd/b.png"]
                                          :fs          fs*})]
      (should (str/includes? raw "filename=\"a.txt\""))
      (should (str/includes? raw "filename=\"b.png\""))
      (should (str/includes? raw "Content-Type: image/png"))))
  )
