(ns isaac.comm.gmail.guidance
  "The standing instruction gmail attaches to every turn it dispatches
   (isaac-3t0z). The turn's final answer text is mailed automatically as the
   reply on the originating thread, so a gmail__send to that same message
   would email the answer twice; gmail__send is for other threads and new
   messages. It rides the charge's :guidance, framed into the current user
   turn once by the prompt builder — never the system prompt, and never on a
   non-gmail turn.")

(def TEXT
  (str "Your final answer text is sent automatically as the email reply on this thread. "
       "Do not use gmail__send to reply to the message you are answering; use it only "
       "for other threads or new messages."))
