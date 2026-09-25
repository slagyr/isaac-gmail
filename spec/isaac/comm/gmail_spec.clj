(ns isaac.comm.gmail-spec
  (:require
    [isaac.comm.gmail :as sut]
    [speclj.core :refer [describe it should should-not]]))

(def origin {:kind :gmail :thread-id "t-1" :message-id "m-1"})

(describe "gmail comm - reply-to-origin? (isaac-3t0z)"

  (it "a gmail__send replying to the origin message is the reply"
    (should (sut/reply-to-origin? origin {:name "gmail__send" :arguments {"reply-to-id" "m-1"}})))

  (it "reads keyword and JSON-string arguments too"
    (should (sut/reply-to-origin? origin {:name "gmail__send" :arguments {:reply-to-id " m-1 "}}))
    (should (sut/reply-to-origin? origin {:name "gmail__send" :arguments "{\"reply-to-id\": \"m-1\"}"})))

  (it "a reply to another message is not the reply"
    (should-not (sut/reply-to-origin? origin {:name "gmail__send" :arguments {"reply-to-id" "m-9"}})))

  (it "a new message (no reply-to-id) is not the reply"
    (should-not (sut/reply-to-origin? origin {:name "gmail__send" :arguments {"to" "ada@tonotop.com"}})))

  (it "another tool is never the reply"
    (should-not (sut/reply-to-origin? origin {:name "gmail__read" :arguments {"reply-to-id" "m-1"}})))

  (it "no origin, no reply"
    (should-not (sut/reply-to-origin? nil {:name "gmail__send" :arguments {"reply-to-id" "m-1"}}))))
