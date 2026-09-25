(ns isaac.comm.gmail.guidance
  "The standing instruction gmail attaches to every turn it dispatches
   (isaac-3t0z, reworded for one send tool by isaac-iwio). It rides the
   charge's :guidance, framed into the current user turn once by the prompt
   builder — never the system prompt, and never on a non-gmail turn.")

(def TEXT
  (str "Your response is the text you end this turn with. It is delivered back over the channel "
       "this message came from, so never send it with comm__send. That tool is for additional "
       "messages of your own during the turn: several messages in a row, a message to another "
       "thread, space or person, or something you were asked to send. Those never replace your "
       "response, so still end the turn with it, even if it is short."))
