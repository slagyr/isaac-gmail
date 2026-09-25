(ns isaac.comm.gmail.guidance-spec
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.guidance :as sut]
    [speclj.core :refer [describe it should]]))

(describe "gmail guidance (isaac-3t0z)"

  (it "says the answer text is the reply and gmail__send is for other threads"
    (should (str/includes? sut/TEXT "sent automatically as the email reply"))
    (should (str/includes? sut/TEXT "gmail__send"))))
