(ns isaac.comm.gmail.handler-spec
  (:require
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
