(ns isaac.comm.gmail.handler-spec
  (:require
    [isaac.agent.api :as api]
    [isaac.comm.gmail.guidance :as guidance]
    [isaac.comm.gmail.handler :as sut]
    [speclj.core :refer :all]))

(describe "gmail handler - default crew resolution (isaac-rfmh)"

  (it "the comm's own gmail/crew wins"
    (should= "ops" (#'sut/default-crew {:comms {:gmail {:gmail/crew "ops"}} :defaults {:frequencies {:crew :isaac}}})))

  (it "falls back to the operator's default crew, keyword or string"
    (should= "isaac" (#'sut/default-crew {:comms {:gmail {}} :defaults {:frequencies {:crew :isaac}}}))
    (should= "isaac" (#'sut/default-crew {:comms {:gmail {}} :defaults {:frequencies {:crew "isaac"}}})))

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
    (should= :marigold (#'sut/tenant-of {:comms {:gmail {:gmail/google :marigold}}})))

  (it "is nil on a single-tenant host"
    (should-be-nil (#'sut/tenant-of {:comms {:gmail {}}}))))

(describe "gmail handler - start-turn! (isaac-3t0z)"

  (it "carries the standing guidance: the answer text is the reply"
    (let [dispatched (atom nil)]
      (with-redefs [api/get-session     (fn [_] nil)
                    api/create-session! (fn [id _] {:name id})
                    api/dispatch!       (fn [req] (reset! dispatched req))
                    sut/live-comm       (fn [_] nil)]
        (#'sut/start-turn! {:id "m-1" :threadId "t-1" :from "ada@marigold.example" :subject "Hi" :body "Yo"} {} "main")
        (should= "gmail-t-1" (:session-key @dispatched))
        (should= guidance/TEXT (:guidance @dispatched))))))

(describe "gmail handler - sender handle (isaac-m8vk)"
  (it "sends the bare lower-case sender address as an unauthenticated handle"
    (let [dispatched (atom nil)]
      (with-redefs [api/get-session     (fn [_] nil)
                    api/create-session! (fn [id _] {:name id})
                    api/dispatch!       (fn [req] (reset! dispatched req))
                    sut/live-comm       (fn [_] nil)]
        (#'sut/start-turn! {:id "m-1" :threadId "t-1" :from "ADA@Marigold.Example"} {} "main")
        (should= {:kind :handle :comm :gmail :id "ada@marigold.example"
                  :email "ada@marigold.example" :authenticated false}
                 (:from @dispatched)))))

  (it "also sends the display name when the From header has one"
    (let [dispatched (atom nil)]
      (with-redefs [api/get-session     (fn [_] nil)
                    api/create-session! (fn [id _] {:name id})
                    api/dispatch!       (fn [req] (reset! dispatched req))
                    sut/live-comm       (fn [_] nil)]
        (#'sut/start-turn! {:id "m-2" :threadId "t-2" :from "Grace Hopper <GRACE@Marigold.Example>"} {} "main")
        (should= {:kind :handle :comm :gmail :id "grace@marigold.example"
                  :email "grace@marigold.example" :name "Grace Hopper" :authenticated false}
                 (:from @dispatched))))))
