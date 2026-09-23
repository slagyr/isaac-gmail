(ns isaac.comm.gmail.message-spec
  (:require
    [isaac.comm.gmail.message :as sut]
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
