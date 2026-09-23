(ns isaac.comm.gmail.triage
  "Triage fallback for unrouted Gmail mail (isaac-betb).

   When top-level config `gmail/triage` names a model, a message
   `isaac.comm.gmail.routes/decide` reports `:unrouted` (and not
   auth-blocked) runs ONE turn in a dedicated, reset-context session
   (`gmail-triage`) instead of going straight to :unrouted. The model picks
   one of `:choices` or \"ignore\"; anything else falls back to `:default`
   (default \"ignore\"). The turn runs with a fixed system prompt (route
   names + their :desc from gmail-routes) and a capped message excerpt, no
   tools, one cycle.

   This namespace only runs the turn and resolves the verdict string.
   `isaac.comm.gmail.handler` applies the verdict label, logs it, and — when
   `:apply` is true — redispatches through the matched route so that route's
   own label lands too. Triage never overrides a route routes/decide already
   matched; the handler only calls `decide!` on an :unrouted verdict."
  (:require
    [clojure.string :as str]
    [isaac.api :as api]
    [isaac.comm.gmail.routes :as routes]
    [isaac.session.store.spi :as store]
    [isaac.session.transcript :as transcript]))

(def session-key
  "The one dedicated, reset-context session every triage turn runs in.
   Deleted and recreated before each call so its persisted transcript never
   grows (isaac-betb)."
  "gmail-triage")

(def excerpt-cap
  "Character cap on the message excerpt handed to the triage model."
  2000)

(defn- triage-cfg [cfg]
  (or (:gmail/triage cfg) {}))

(defn configured?
  "True when gmail/triage names a model — everything else (:crew, :choices,
   :default, :apply) has a sensible default."
  [cfg]
  (boolean (:model (triage-cfg cfg))))

(defn apply?
  "True when a matched verdict should be redispatched through its route."
  [cfg]
  (boolean (:apply (triage-cfg cfg))))

(defn choices
  "The configured route names (plus, conventionally, \"ignore\") the model
   may answer with."
  [cfg]
  (mapv str (or (:choices (triage-cfg cfg)) [])))

(defn default-verdict
  "The verdict used when the model's answer isn't \"ignore\" or one of
   :choices. Default \"ignore\"."
  [cfg]
  (or (some-> (:default (triage-cfg cfg)) str) "ignore"))

(defn- crew-id [cfg]
  (or (:crew (triage-cfg cfg)) "main"))

(defn- model-alias [cfg]
  (:model (triage-cfg cfg)))

(defn- route-descriptions [cfg]
  (into {} (map (juxt :name :desc)) (routes/ordered-routes cfg)))

(defn- system-prompt [cfg]
  (let [descs (route-descriptions cfg)
        lines (map (fn [c]
                     (str "- " c
                         (when-let [d (and (not= "ignore" c) (get descs c))]
                           (when (seq d) (str ": " d)))))
                   (choices cfg))]
    (str "You triage email Isaac's rules did not route. Reply with exactly "
        "one of the following words and nothing else — the route it best "
        "fits, or \"ignore\" if none do:\n"
        (str/join "\n" lines))))

(defn- message-excerpt [message]
  (let [body (str (or (:body message) ""))]
    (str "From: " (:from message) "\n"
        "To: " (:to message) "\n"
        "Subject: " (:subject message) "\n\n"
        (subs body 0 (min (count body) excerpt-cap)))))

(defn- resolve-verdict [cfg answer]
  (let [choice-set (set (choices cfg))
        norm       (some-> answer str/trim)]
    (if (and (seq norm) (or (= "ignore" norm) (contains? choice-set norm)))
      norm
      (default-verdict cfg))))

(defn- no-tools-config
  "A copy of `cfg` where the triage crew's own :tools are replaced (not
   merged) with deny-all — an empty :allow is not deny-all under the
   cascade, so this must fully replace whatever the crew's own :tools say."
  [cfg crew]
  (assoc-in cfg [:crew crew :tools] {:deny :all}))

(defn- last-assistant-text [entries]
  (->> entries
       (filter #(and (= "message" (:type %)) (= "assistant" (get-in % [:message :role]))))
       last
       :message
       :content
       transcript/content->text))

(defn decide!
  "Run one reset-context, tool-less, single-cycle turn in session
   \"gmail-triage\" for `message` and return the resolved verdict string —
   an entry from :choices, \"ignore\", or :default when the model's answer
   is neither."
  [cfg message]
  (let [crew  (crew-id cfg)
        cfg*  (no-tools-config cfg crew)
        store (store/registered-store)]
    (store/delete-session! store session-key)
    (api/create-session! session-key {:crew crew :origin {:kind :gmail-triage}})
    (api/dispatch! {:session-key           session-key
                    :input                 (message-excerpt message)
                    :crew                  crew
                    :model-override        (model-alias cfg)
                    :context-mode-override :reset
                    :cycle                 {:limit 1}
                    :soul                  (system-prompt cfg)
                    :config                cfg*})
    (resolve-verdict cfg (last-assistant-text (store/get-transcript store session-key)))))
