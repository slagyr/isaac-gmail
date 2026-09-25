(ns isaac.comm.gmail.guidance-spec
  (:require
    [isaac.comm.gmail.guidance :as sut]
    [speclj.core :refer [describe it should=]]))

(describe "gmail guidance (isaac-iwio)"

  (it "is exactly the one-send-tool guidance text, verbatim"
    (should= (str "Your response is the text you end this turn with. It is delivered back over the channel "
                  "this message came from, so never send it with comm__send. That tool is for additional "
                  "messages of your own during the turn: several messages in a row, a message to another "
                  "thread, space or person, or something you were asked to send. Those never replace your "
                  "response, so still end the turn with it, even if it is short.")
             sut/TEXT)))
