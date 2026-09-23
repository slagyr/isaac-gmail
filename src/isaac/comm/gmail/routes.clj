(ns isaac.comm.gmail.routes
  "Pure Gmail triage: decide which configured route (if any) a gated INBOX
   message matches (isaac-sb6d).

   `decide` takes only the message/from-api map and the resolved config — no
   Gmail API calls, no cursor, no watch — so the push handler and a future
   pull tick both feed it the same way, and a future IMAP module would only
   have to produce the same message map.

   Gate signals (Precedence: bulk|list|junk, Auto-Submitted, List-Unsubscribe,
   a non-personal Gmail category label) are checked before routes and always
   win: a message showing any of them is :ignore regardless of whether a
   :converse route would also match.

   Routes are the whitelist (supersedes the old gmail/allow-from list): a
   :converse route must name :match :from, and a *@domain pattern is honoured
   only when `isaac.comm.gmail.gate/authenticated?` vouches for the sender's
   domain. A message no configured route claims — including every message
   when the table is empty — is :unrouted: nothing converses."
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.gate :as gate]))

(def known-actions
  "Actions this bean implements: :converse and :ignore (isaac-sb6d), and
   :task — send the message as a hail on a band instead of starting a thread
   session (isaac-3427)."
  #{:converse :ignore :task})

;; region ----- config -----

(defn- ->kw [v]
  (cond (keyword? v) v
        (and (string? v) (seq v)) (keyword v)))

(defn- route-table [cfg]
  (or (:gmail-routes cfg) (get cfg "gmail-routes") {}))

(defn- route-entries
  "Every named entry of the table, `_` (table defaults) excluded."
  [cfg]
  (->> (route-table cfg)
       (remove (fn [[k _]] (#{:_ "_"} k)))))

(defn ordered-routes
  "Every configured route as a map with a string :name, ordered by :order
   ascending (ties by name — a map carries no order of its own)."
  [cfg]
  (->> (route-entries cfg)
       (map (fn [[id route]] (assoc route :name (name id) :action (->kw (:action route)))))
       (sort-by (juxt #(or (:order %) 0) :name))))

(defn- ignore-categories [cfg]
  (let [slice (or (:comms cfg) {})
        gmail (or (get-in slice [:gmail]) (get-in slice ["gmail"]) {})]
    (set (or (:gmail/ignore-categories gmail)
             ["CATEGORY_PROMOTIONS" "CATEGORY_SOCIAL" "CATEGORY_UPDATES" "CATEGORY_FORUMS"]))))

;; endregion ^^^^^ config ^^^^^

;; region ----- gate signals (free, before any route) -----

(defn- label-set [message]
  (set (map name (or (:labelIds message) (:label-ids message) []))))

(defn- bulk-precedence? [message]
  (contains? #{"bulk" "list" "junk"} (some-> (:precedence message) str str/lower-case)))

(defn- auto-submitted? [message]
  (let [v (some-> (:auto-submitted message) str str/lower-case)]
    (and (seq v) (not= "no" v))))

(defn- list-unsubscribe? [message]
  (seq (str (:list-unsubscribe message))))

(defn- category-signal? [message ignore-categories]
  (boolean (some ignore-categories (label-set message))))

(defn gate-signal
  "Nil, or the reason a message is :ignore before any route is consulted."
  [message ignore-categories]
  (cond
    (bulk-precedence? message)               :precedence
    (auto-submitted? message)                :auto-submitted
    (list-unsubscribe? message)              :list-unsubscribe
    (category-signal? message ignore-categories) :category))

;; endregion ^^^^^ gate signals ^^^^^

;; region ----- matching -----

(defn- glob-pattern [pattern]
  (re-pattern (str "(?i)^"
                   (str/replace (java.util.regex.Pattern/quote (str pattern)) "*" "\\E.*\\Q")
                   "$")))

(defn- glob-matches? [pattern value]
  (boolean (and (seq (str pattern)) (seq (str value))
               (re-matches (glob-pattern pattern) (str value)))))

(defn- domain-pattern? [pattern]
  (str/starts-with? (str pattern) "*@"))

(defn- to-addresses [message]
  (->> (str/split (str (:to message)) #",")
       (map (comp gate/address str/trim))
       (remove str/blank?)))

(defn- to-match? [pattern message]
  (or (nil? pattern)
      (some #(glob-matches? pattern %) (to-addresses message))))

(defn- from-match
  "{:match? bool :blocked? bool} — :blocked? true means a *@domain pattern
   named this sender's domain but Gmail would not vouch for it: the route
   does not match, and the caller should treat this as the drop the old
   allow-from *@domain entries gave (isaac-dymn), not a silent skip."
  [pattern message]
  (let [addr   (or (:from-email message) (gate/address (:from message)))
        domain (when (seq addr) (second (re-find #"@(.+)$" addr)))]
    (cond
      (nil? pattern) {:match? true}
      (str/blank? addr) {:match? false}
      (domain-pattern? pattern)
      (if (and domain (= (str/lower-case domain) (str/lower-case (subs pattern 2))))
        (if (gate/authenticated? message domain)
          {:match? true}
          {:match? false :blocked? true})
        {:match? false})
      :else {:match? (glob-matches? pattern addr)})))

(defn- subject-match? [pattern message]
  (or (nil? pattern)
      (boolean (re-find (re-pattern pattern) (str (:subject message))))))

(defn- label-match? [pattern message]
  (or (nil? pattern) (contains? (label-set message) pattern)))

(defn- list-id-match? [pattern message]
  (or (nil? pattern) (= pattern (:list-id message))))

(defn- match-route
  "{:match? bool :blocked? bool} for one route against `message`."
  [route message]
  (let [m    (:match route)
        from (from-match (:from m) message)]
    (cond
      (:blocked? from) from
      (not (:match? from)) {:match? false}
      (not (to-match? (:to m) message)) {:match? false}
      (not (subject-match? (:subject m) message)) {:match? false}
      (not (label-match? (:label m) message)) {:match? false}
      (not (list-id-match? (:list-id m) message)) {:match? false}
      :else {:match? true})))

;; endregion ^^^^^ matching ^^^^^

(defn route-decision
  "The decision map for `route` as if it had just matched via `decide` —
   reused by the triage fallback (isaac-betb) to redispatch a message as if
   its verdict route had matched."
  [route]
  (cond-> {:route (:name route) :action (:action route) :crew (:crew route)
          :gate nil :blocked #{}}
    (:band route)          (assoc :band (:band route))
    (contains? route :ack)  (assoc :ack (:ack route))
    (:params route)         (assoc :params (:params route))))

(defn find-route
  "The configured route named `route-name`, or nil when route-name is
   \"ignore\", blank, or names no configured route (isaac-betb)."
  [cfg route-name]
  (when (and (seq route-name) (not= "ignore" route-name))
    (first (filter #(= route-name (:name %)) (ordered-routes cfg)))))

(defn decide
  "{:route <name-string> :action :converse|:ignore|:task|:unrouted
    :crew <string-or-nil> :gate <keyword-or-nil> :blocked <#{route-name}>
    :band <string-or-absent> :ack <bool-or-absent> :params <map-or-absent>}
   for `message` against `cfg`'s gmail-routes table. Routes are the
   whitelist: with zero routes configured, or none matching, the message is
   :unrouted — nothing converses. `:blocked` names any route a *@domain
   pattern matched but Gmail did not authenticate — the handler warn-logs
   :unauthenticated for those instead of the quieter :unrouted info log.
   :band, :ack, and :params only appear when the matched route sets them —
   a :task route always sets :band (isaac-3427)."
  [cfg message]
  (let [ignore-cats (ignore-categories cfg)
        signal      (gate-signal message ignore-cats)]
    (if signal
      {:route "ignored" :action :ignore :crew nil :gate signal :blocked #{}}
      (loop [rs (ordered-routes cfg) blocked #{}]
        (if (empty? rs)
          {:route "unrouted" :action :unrouted :crew nil :gate nil :blocked blocked}
          (let [route (first rs)
                {:keys [match? blocked?]} (match-route route message)]
            (cond
              match?   (assoc (route-decision route) :blocked blocked)
              blocked? (recur (rest rs) (conj blocked (:name route)))
              :else    (recur (rest rs) blocked))))))))

;; region ----- config validation (contributed to :isaac.config/check) -----

(defn check-config
  "Cross-field validation the schema alone can't express: an :action outside
   `known-actions`, and a :converse route with no :match :from (routes are
   the whitelist — an unnamed :from can never be intentional)."
  [{:keys [config]}]
  {:errors
   (vec
     (mapcat
       (fn [route]
         (let [name   (:name route)
               action (:action route)]
           (cond
             (nil? action)
             [{:key (str "gmail-routes." name ".action") :value "missing :action"}]

             (not (contains? known-actions action))
             [{:key   (str "gmail-routes." name ".action")
               :value (str "unknown action " (pr-str action) " for route " (pr-str name))}]

             (and (#{:converse :task} action) (not (seq (get-in route [:match :from]))))
             [{:key   (str "gmail-routes." name ".match.from")
               :value (str "route " (pr-str name) " is a :" (clojure.core/name action)
                          " route and must name :match :from")}]

             (and (= :task action) (not (seq (:band route))))
             [{:key   (str "gmail-routes." name ".band")
               :value (str "route " (pr-str name) " is a :task route and must name :band")}]

             :else [])))
       (ordered-routes config)))
   :warnings []})

;; endregion ^^^^^ config validation ^^^^^
