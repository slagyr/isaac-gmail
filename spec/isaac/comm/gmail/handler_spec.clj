(ns isaac.comm.gmail.handler-spec
  (:require
    [isaac.comm.gmail.handler :as sut]
    [speclj.core :refer :all]))

(describe "gmail handler - crew resolution (isaac-rfmh)"

  (it "the comm's own gmail/crew wins"
    (should= "ops" (#'sut/crew {:comms {:gmail {:gmail/crew "ops"}} :defaults {:crew :yopp}})))

  (it "falls back to the operator's defaults.crew, keyword or string"
    (should= "yopp" (#'sut/crew {:comms {:gmail {}} :defaults {:crew :yopp}}))
    (should= "yopp" (#'sut/crew {:comms {:gmail {}} :defaults {:crew "yopp"}})))

  (it "is nil when nothing names a crew - never a crew called main (isaac-zule)"
    (should-be-nil (#'sut/crew {}))))
