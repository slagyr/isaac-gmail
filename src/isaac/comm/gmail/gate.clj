(ns isaac.comm.gmail.gate
  "Inbound Gmail gate: INBOX only, and does Gmail vouch for a sender's domain.

   Routes are the whitelist now (isaac-sb6d): the old gmail/allow-from list is
   retired and isaac.comm.gmail.routes decides what to do with a message.
   This namespace keeps the two pieces of that decision that stay structural
   rather than config-driven: whether a message is even INBOX mail, and
   whether Gmail's Authentication-Results vouch for a *@domain route's
   sender — a *@domain pattern is only honoured when dmarc=pass, or spf and
   dkim both pass and are aligned to the From domain, exactly as the old
   *@domain allow-from entries required (isaac-dymn)."
  (:require
    [clojure.string :as str]))

(defn- labels [message]
  (set (map name (or (:label-ids message) (:labelIds message) []))))

(defn address
  "The bare address out of a From header: \"Ada <ada@tonotop.com>\" → ada@tonotop.com."
  [from]
  (let [from (str/trim (str (or from "")))]
    (str/lower-case
      (or (second (re-find #"<([^>]+)>" from))
          from))))

(defn not-inbox?
  "True when a message is not live INBOX mail — sent by us, still a draft, or
   never labelled INBOX at all."
  [message]
  (let [labs (labels message)]
    (or (contains? labs "SENT") (contains? labs "DRAFT") (not (contains? labs "INBOX")))))

(defn- verdicts
  "Authentication-Results → {\"dmarc\" \"pass\", \"spf\" \"pass\", …}. Gmail writes
   one header per delivery; several are possible, so read them all."
  [message]
  (let [raw (or (:auth-results message) (:authentication-results message) "")
        raw (if (sequential? raw) (str/join "; " raw) (str raw))]
    (into {}
          (map (fn [[_ method verdict]] [(str/lower-case method) (str/lower-case verdict)]))
          (re-seq #"(?i)\b(dmarc|spf|dkim)\s*=\s*([a-z]+)" raw))))

(defn- aligned? [message domain]
  (let [raw (str (or (:auth-results message) (:authentication-results message) ""))]
    (boolean
      (or (re-find (re-pattern (str "(?i)header\\.from\\s*=\\s*" (java.util.regex.Pattern/quote (str domain)))) raw)
          (re-find (re-pattern (str "(?i)header\\.d\\s*=\\s*" (java.util.regex.Pattern/quote (str domain)))) raw)))))

(defn authenticated?
  "Does Gmail vouch that this message really came from `domain`? dmarc=pass is
   the whole answer; failing that, spf and dkim must both pass and name the
   domain the From header claims."
  [message domain]
  (let [{:strs [dmarc spf dkim]} (verdicts message)]
    (boolean
      (and (seq (str (or domain "")))
           (or (= "pass" dmarc)
               (and (= "pass" spf) (= "pass" dkim) (aligned? message domain)))))))
