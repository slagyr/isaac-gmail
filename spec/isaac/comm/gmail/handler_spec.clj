(ns isaac.comm.gmail.handler-spec
  (:require
    [isaac.api :as api]
    [isaac.comm.gmail.guidance :as guidance]
    [isaac.comm.gmail.handler :as sut]
    [speclj.core :refer :all]))

(describe "gmail handler - default crew resolution (isaac-rfmh)"

  (it "the comm's own gmail/crew wins"
    (should= "ops" (#'sut/default-crew {:comms {:gmail {:gmail/crew "ops"}} :defaults {:frequencies {:crew :yopp}}})))

  (it "falls back to the operator's default crew, keyword or string"
    (should= "yopp" (#'sut/default-crew {:comms {:gmail {}} :defaults {:frequencies {:crew :yopp}}}))
    (should= "yopp" (#'sut/default-crew {:comms {:gmail {}} :defaults {:frequencies {:crew "yopp"}}})))

  (it "is nil when nothing names a crew - never a crew called main (isaac-zule)"
    (should-be-nil (#'sut/default-crew {}))))

(describe "gmail handler - ignore-marks-read? (isaac-sb6d)"

  (it "defaults true when unset"
    (should (#'sut/ignore-marks-read? {:comms {:gmail {}}})))

  (it "honours an explicit false"
    (should-not (#'sut/ignore-marks-read? {:comms {:gmail {:gmail/ignore-marks-read false}}})))

  (it "honours an explicit true"
    (should (#'sut/ignore-marks-read? {:comms {:gmail {:gmail/ignore-marks-read true}}}))))

(describe "gmail handler - tenant-of (isaac-sb6d)"

  (it "reads the comm's own gmail/google tenant"
    (should= :tonotop (#'sut/tenant-of {:comms {:gmail {:gmail/google :tonotop}}})))

  (it "is nil on a single-tenant host"
    (should-be-nil (#'sut/tenant-of {:comms {:gmail {}}}))))

(describe "gmail handler - start-turn! (isaac-3t0z)"

  (it "carries the standing guidance: the answer text is the reply"
    (let [dispatched (atom nil)]
      (with-redefs [api/get-session     (fn [_] nil)
                    api/create-session! (fn [id _] {:name id})
                    api/dispatch!       (fn [req] (reset! dispatched req))
                    sut/live-comm       (fn [_] nil)]
        (#'sut/start-turn! {:id "m-1" :threadId "t-1" :from "ada@tonotop.com" :subject "Hi" :body "Yo"} {} "main")
        (should= "gmail-t-1" (:session-key @dispatched))
        (should= guidance/TEXT (:guidance @dispatched))))))
