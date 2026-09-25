(ns isaac.comm.gmail.message-spec
  (:require
    [isaac.comm.gmail.api :as api]
    [isaac.comm.gmail.inbound-attachment :as inbound-attachment]
    [isaac.comm.gmail.message :as sut]
    [isaac.fs :as fs]
    [isaac.logger :as log]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(describe "gmail message/from-api (isaac-sb6d)"

  (it "passes a stub :precedence straight through"
    (should= "bulk" (:precedence (sut/from-api {:id "m-1" :precedence "bulk"}))))

  (it "reads Precedence off a real message's headers"
    (should= "bulk"
             (:precedence (sut/from-api {:id      "m-1"
                                         :payload {:headers [{:name "Precedence" :value "bulk"}]}}))))

  (it "is nil when neither is present"
    (should-be-nil (:precedence (sut/from-api {:id "m-1"}))))

  (it "passes stub :auto-submitted and :list-unsubscribe through"
    (should= "auto-replied" (:auto-submitted (sut/from-api {:id "m-1" :auto-submitted "auto-replied"})))
    (should= "<mailto:x@y>" (:list-unsubscribe (sut/from-api {:id "m-1" :list-unsubscribe "<mailto:x@y>"})))))

(describe "gmail inbound attachments (isaac-e2zb)"
  (it "finds a named attachment MIME part"
    (should= [{:filename "report.pdf" :mime-type "application/pdf" :attachment-id "att-1"}]
             (:attachments (sut/from-api {:id "m-1" :payload {:parts [{:filename "report.pdf" :mimeType "application/pdf" :body {:attachmentId "att-1"}}]}}))))

  (it "sanitizes a filename, saves the attachment, and frames it"
    (let [fs* (fs/mem-fs)]
      (nexus/-with-nexus {:fs fs*}
        (with-redefs [api/attachment-get! (constantly "hello")]
          (should= ["[attachment: note.txt (text/plain, 5) at attachments/m-1/note.txt]"]
                   (inbound-attachment/save-all! "/work" {:id "m-1"
                                                           :attachments [{:filename "../note.txt"
                                                                          :mime-type "text/plain"
                                                                          :attachment-id "a"}]}))
          (should= "hello" (fs/slurp fs* "/work/attachments/m-1/note.txt"))))))

  (it "does not save an attachment above the cap"
    (let [fs* (fs/mem-fs)]
      (nexus/-with-nexus {:fs fs*}
        (with-redefs [inbound-attachment/MAX-BYTES 4
                      api/attachment-get! (constantly "hello")]
          (should= ["[attachment: report.pdf (too large, not saved)]"]
                   (inbound-attachment/save-all! "/work" {:id "m-1"
                                                           :attachments [{:filename "report.pdf"
                                                                          :attachment-id "a"}]}))
          (should-not (fs/exists? fs* "/work/attachments/m-1/report.pdf"))))))

  (it "logs once and frames a failed download while continuing"
    (let [fs* (fs/mem-fs)]
      (nexus/-with-nexus {:fs fs*}
        (with-redefs [api/attachment-get! (fn [_ _] (throw (ex-info "gone" {})))]
          (log/capture-logs
            (should= ["[attachment: report.pdf (download failed)]"]
                     (inbound-attachment/save-all! "/work" {:id "m-1"
                                                             :attachments [{:filename "report.pdf"
                                                                            :attachment-id "a"}]}))
            (should= 1 (count (filter #(= :gmail.attachment/download-failed (:event %))
                                       @log/captured-logs)))))))))
