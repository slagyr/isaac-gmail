(ns isaac.comm.gmail-spec
  (:require
    [isaac.comm.gmail :as sut]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.protocol :as comm]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(defn- comm-with [slice]
  (let [c (sut/make {:name :gmail :root "/tmp"})]
    (reset! (.-cfg c) slice)
    c))

(def slice {:gmail/account "yopp@tonotop.com"})

(def a-thread-message
  {:id       "m-1"
   :threadId "t-1"
   :payload  {:headers [{:name "From" :value "ada@tonotop.com"}
                        {:name "Subject" :value "Deploy window"}
                        {:name "Message-ID" :value "<abc@tonotop.com>"}]
              :body    {:data ""}}})

(describe "gmail comm send! (isaac-iwio)"

  (context ":gmail/thread — reply on that thread"

    (it "fetches the thread's last message and replies on it"
      (let [sent (atom nil)
            c    (comm-with slice)]
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/threads-get!   (fn [id] (should= "t-1" id) {:messages [a-thread-message]})
                      gmail-api/messages-send! (fn [args] (reset! sent args) {:id "s-1" :threadId "t-1"})]
          (should= {:ok true}
                   (comm/send! c {:gmail/thread "t-1" :content "Looking now."}))
          (should= "t-1" (:thread-id @sent))
          (let [raw (gmail-api/decode-raw (:raw @sent))]
            (should-contain "To: ada@tonotop.com" raw)
            (should-contain "Subject: Re: Deploy window" raw)
            (should-contain "In-Reply-To: <abc@tonotop.com>" raw)
            (should-contain "References: <abc@tonotop.com>" raw)
            (should-contain "Looking now." raw)))))

    (it "fails transiently when the thread cannot be found"
      (let [c (comm-with slice)]
        (with-redefs [gmail-api/access-token (constantly "at-1")
                      gmail-api/threads-get! (fn [_] {:messages []})]
          (let [result (comm/send! c {:gmail/thread "t-9" :content "Hi"})]
            (should-not (:ok result))
            (should (:transient? result)))))))

  (context ":gmail/to (+ :gmail/subject), no thread — a new message"

    (it "writes a new message to the address with the given subject"
      (let [sent (atom nil)
            c    (comm-with slice)]
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/messages-send! (fn [args] (reset! sent args) {:id "s-2"})]
          (should= {:ok true}
                   (comm/send! c {:gmail/to "grace@tonotop.com" :gmail/subject "Deploy window"
                                  :content  "Ada asks: can we ship Friday?"}))
          (should-be-nil (:thread-id @sent))
          (let [raw (gmail-api/decode-raw (:raw @sent))]
            (should-contain "To: grace@tonotop.com" raw)
            (should-contain "Subject: Deploy window" raw)
            (should-contain "Ada asks: can we ship Friday?" raw)))))

    (it "is an error without a subject, and nothing is sent"
      (let [sent? (atom false)
            c     (comm-with slice)]
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/messages-send! (fn [_] (reset! sent? true) {})]
          (let [result (comm/send! c {:gmail/to "grace@tonotop.com" :content "Hi"})]
            (should-not (:ok result))
            (should-not (:transient? result))
            (should-not @sent?))))))

  (context "neither — reply on the session's own origin (unchanged default)"

    (it "replies using the record's own from/subject/message-id/thread-id"
      (let [sent (atom nil)
            c    (comm-with slice)]
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/messages-send! (fn [args] (reset! sent args) {:id "s-3"})]
          (should= {:ok true}
                   (comm/send! c {:from "ada@tonotop.com" :subject "Standup" :message-id "<m1>"
                                  :thread-id "t-7" :content "On my way."}))
          (should= "t-7" (:thread-id @sent))
          (let [raw (gmail-api/decode-raw (:raw @sent))]
            (should-contain "To: ada@tonotop.com" raw)
            (should-contain "Subject: Re: Standup" raw)))))

    (it "does nothing on blank content"
      (let [c (comm-with slice)]
        (should= {:ok false :transient? false} (comm/send! c {:content "   "})))))

  (context ":attachments (isaac-8hi7) — a multipart raw message"

    (it "builds a multipart new message carrying the file"
      (let [sent (atom nil)
            c    (comm-with slice)
            fs*  (fs/mem-fs)]
        (fs/mkdirs fs* "/cwd")
        (fs/spit fs* "/cwd/report.pdf" "%PDF-1.4 stub")
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/messages-send! (fn [args] (reset! sent args) {:id "s-4"})]
          (nexus/-with-nested-nexus {:fs fs*}
            (should= {:ok true}
                     (comm/send! c {:gmail/to      "grace@tonotop.com"
                                    :gmail/subject "Report"
                                    :content       "Attached."
                                    :attachments   ["/cwd/report.pdf"]}))))
        (let [raw (gmail-api/decode-raw (:raw @sent))]
          (should-contain "Content-Type: multipart/mixed" raw)
          (should-contain "Content-Disposition: attachment; filename=\"report.pdf\"" raw))))

    (it "builds a multipart thread reply carrying the file"
      (let [sent (atom nil)
            c    (comm-with slice)
            fs*  (fs/mem-fs)]
        (fs/mkdirs fs* "/cwd")
        (fs/spit fs* "/cwd/report.pdf" "%PDF-1.4 stub")
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/threads-get!   (fn [_] {:messages [a-thread-message]})
                      gmail-api/messages-send! (fn [args] (reset! sent args) {:id "s-5"})]
          (nexus/-with-nested-nexus {:fs fs*}
            (should= {:ok true}
                     (comm/send! c {:gmail/thread "t-1" :content "Looking now."
                                    :attachments  ["/cwd/report.pdf"]}))))
        (let [raw (gmail-api/decode-raw (:raw @sent))]
          (should-contain "Content-Type: multipart/mixed" raw)
          (should-contain "Content-Disposition: attachment; filename=\"report.pdf\"" raw))))

    (it "builds a multipart origin reply carrying the file"
      (let [sent (atom nil)
            c    (comm-with slice)
            fs*  (fs/mem-fs)]
        (fs/mkdirs fs* "/cwd")
        (fs/spit fs* "/cwd/report.pdf" "%PDF-1.4 stub")
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/messages-send! (fn [args] (reset! sent args) {:id "s-6"})]
          (nexus/-with-nested-nexus {:fs fs*}
            (should= {:ok true}
                     (comm/send! c {:from "ada@tonotop.com" :subject "Standup" :message-id "<m1>"
                                    :thread-id "t-7" :content "On my way." :attachments ["/cwd/report.pdf"]}))))
        (let [raw (gmail-api/decode-raw (:raw @sent))]
          (should-contain "Content-Type: multipart/mixed" raw)
          (should-contain "Content-Disposition: attachment; filename=\"report.pdf\"" raw))))

    (it "refuses a delivery whose attachments total more than 25 MB, before any API call"
      (let [sent? (atom false)
            c     (comm-with slice)
            fs*   (fs/mem-fs)]
        (fs/mkdirs fs* "/cwd")
        (fs/spit fs* "/cwd/big.bin" (apply str (repeat (inc (* 25 1024 1024)) "x")))
        (with-redefs [gmail-api/access-token   (constantly "at-1")
                      gmail-api/messages-send! (fn [_] (reset! sent? true) {})]
          (nexus/-with-nested-nexus {:fs fs*}
            (let [result (comm/send! c {:gmail/to      "grace@tonotop.com"
                                        :gmail/subject "Big"
                                        :content       "Here."
                                        :attachments   ["/cwd/big.bin"]})]
              (should-not (:ok result))
              (should-not (:transient? result))
              (should-not @sent?))))))))
