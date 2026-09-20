(ns isaac.comm.gmail.gate
  "Inbound Gmail gate: allow-from (fail closed), skip drafts/sent, INBOX only.

   An allow-from entry is an address or a *@domain pattern. The two are not
   equally safe: From: is written by the sender, so a pattern that opens a
   whole domain is only honoured when Gmail says the message really came from
   it — dmarc=pass, or spf and dkim both passing and aligned to the From
   domain, as recorded in the Authentication-Results header Gmail adds on
   delivery. An exact address keeps the behaviour it has always had: the
   header is taken at its word (isaac-dymn)."
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

(defn- domain-of [address]
  (let [at (str/last-index-of (str address) "@")]
    (when (and at (< (inc at) (count address)))
      (subs address (inc at)))))

(defn- pattern? [entry]
  (str/starts-with? (str entry) "*@"))

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

(defn drop-reason
  "Nil when the message should start a turn; otherwise :not-inbox, :sender
   (no allow-from entry names this sender) or :unauthenticated (a *@domain
   entry names the sender's domain, but Gmail does not vouch for it)."
  [message allow-from]
  (let [labs    (labels message)
        from    (address (or (:from-email message) (:from message)))
        domain  (domain-of from)
        entries (map str allow-from)
        exact?  (some (fn [e] (and (not (pattern? e)) (= (str/lower-case e) from))) entries)
        match?  (some (fn [e] (and (pattern? e) (seq domain) (= (str/lower-case (subs e 2)) domain))) entries)]
    (cond
      (or (contains? labs "SENT") (contains? labs "DRAFT") (not (contains? labs "INBOX")))
      :not-inbox

      (empty? entries) :sender
      exact?           nil

      (and match? (authenticated? message domain)) nil
      match?                                       :unauthenticated
      :else                                        :sender)))
