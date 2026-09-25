(ns isaac.gmail-steps
  "Gmail comm feature steps: cursor, history/message stubs, watch push,
   sent-mail decode. Pulls in isaac.comm.delivery.worker-steps the same way
   isaac.session.session-steps is pulled in (isaac-iwio) — required for its
   side effect, registering the shared \"the delivery worker ticks\" step;
   nothing here calls it directly."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [after-all defgiven defthen defwhen helper!]]
    [isaac.comm.delivery.worker-steps]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gmail :as gmail]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.cursor :as cursor]
    [isaac.comm.gmail.gate :as gate]
    [isaac.comm.gmail.handler :as handler]
    [isaac.comm.gmail.labels :as gmail-labels]
    [isaac.comm.gmail.pull :as gmail-pull]
    [isaac.comm.protocol :as comm]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.google.events]
    [isaac.google.registration :as google-registration]
    [isaac.google.tenants :as tenants]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.auth.store :as auth-store]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]
    [isaac.step-tables :as match]))

(helper! isaac.gmail-steps)

;; Tool-call scenarios run tool workers via `future` (isaac-agent tool_loop),
;; which starts the agent send-off pool. Its non-daemon threads keep the JVM
;; alive ~60s after gherclj.main returns; shut the pool down after the run.
(after-all shutdown-agents)

(defonce ^:private live-scheduler* (atom nil))

;; Captured once at namespace load, before any scenario stubs anything -
;; the pristine production fns to restore in after-scenario (isaac-iwio).
(defonce ^:private original-gmail-http! gmail-api/-http!)
(defonce ^:private original-gmail-access-token gmail-api/access-token)

(defn- shutdown-gmail-scheduler! []
  (when-let [s @live-scheduler*]
    (scheduler/shutdown! s)
    (reset! live-scheduler* nil))
  (nexus/deregister! [:scheduler]))

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))
    (alter-var-root #'gmail-api/-http! (constantly original-gmail-http!))
    (alter-var-root #'gmail-api/access-token (constantly original-gmail-access-token))
    (gmail-labels/reset-cache!)
    (g/dissoc! :gmail-history)
    (g/dissoc! :gmail-history-fail)
    (g/dissoc! :gmail-messages)
    (g/dissoc! :gmail-inbox)
    (g/dissoc! :gmail-gone)
    (g/dissoc! :gmail-comm)
    (g/dissoc! :gmail-access-token)
    (g/dissoc! :gmail-watch-pushed)
    (g/dissoc! :gmail-watch-grant)
    (g/dissoc! :hail-sent)
    ;; the hail module stub (isaac.hail.queue) is interned by "Given the hail
    ;; module is installed" and never `require`d, so removing the ns is
    ;; enough to make requiring-resolve fail again for the next scenario
    ;; (isaac-3427).
    (when (find-ns 'isaac.hail.queue) (remove-ns 'isaac.hail.queue))
    (google-registration/reset-registrations!)
    (shutdown-gmail-scheduler!)))

(defn- kv-cells->map [cells]
  (when (and (seq cells) (even? (count cells)))
    (into {} (map (fn [[k v]] [k v]) (partition 2 cells)))))

(defn- table-map [{:keys [headers rows]}]
  (or (let [header-map (kv-cells->map headers)
            row-map    (apply merge {} (keep kv-cells->map rows))]
        (when (or header-map (seq row-map))
          (merge header-map row-map)))
      (when (and (seq headers) (= 1 (count rows)))
        (zipmap headers (first rows)))
      {}))

(defn- table-rows [{:keys [headers rows]}]
  (mapv #(zipmap headers %) rows))

(defn- feature-fs []
  (or (g/get :mem-fs) (nexus/get :fs) (fs/real-fs)))

(defn- root-dir []
  (or (g/get :runtime-root-dir) (g/get :root)))

(defn- with-feature-fs [f]
  (nexus/-with-nested-nexus {:fs (feature-fs)}
    (f)))

(defn- gmail-module-coord []
  {:isaac.comm.gmail {:local/root (System/getProperty "user.dir")}})

(defn- gmail-module-index []
  (when-let [manifest (some-> (io/resource "isaac-manifest.edn") slurp edn/read-string)]
    {:isaac.comm.gmail {:coord    {:local/root (System/getProperty "user.dir")}
                        :manifest manifest
                        :path     nil}}))

(defn- inject-gmail-module! []
  (alter-var-root #'discovery/*foundation-index-override*
                  (fn [prev]
                    (merge (or prev (discovery/builtin-index))
                           (gmail-module-index))))
  (when-let [root (root-dir)]
    (with-feature-fs
      (fn []
        (let [path    (str root "/config/isaac.edn")
              fs*     (feature-fs)
              current (if (fs/exists? fs* path)
                        (edn/read-string (fs/slurp fs* path))
                        {})
              updated (update current :modules merge (gmail-module-coord))]
          (fs/mkdirs fs* (fs/parent path))
          (fs/spit fs* path (pr-str updated)))))))

(defn- ensure-session-store! []
  (when-not (session-store/registered-store)
    (when-let [root (root-dir)]
      (session-store/register-store! (memory-store/create-store root)))))

(defn- ensure-gmail-factory! []
  (when-not (get-method comm-factory/create :gmail)
    (require 'isaac.comm.gmail)))

(defn- load-comm-cfg [comm-name]
  (let [fs*  (feature-fs)
        root (root-dir)
        cfg  (:config (loader/load-config-result {:root root :fs fs*}))]
    (or (get-in cfg [:comms (keyword comm-name)])
        (get-in cfg [:comms (name comm-name)])
        {})))

(defn- record-http! [req]
  (let [recorded {:url     (:url req)
                  :method  (str/upper-case (name (:method req "GET")))
                  :headers (:headers req)
                  :body    (:body req)
                  :query   (:query req)
                  :key     "value"}]
    (g/update! :outbound-http-requests (fn [prior] (vec (conj (or prior []) recorded))))
    (g/assoc! :outbound-http-request recorded)
    recorded))

(defn- history-since [req url]
  (str (or (get-in req [:query :startHistoryId])
          (second (re-find #"startHistoryId=([^&]+)" url)))))

(defn- stub-http! [req]
  (record-http! req)
  (let [url (str (:url req))]
    (cond
      (and (g/get :gmail-gone) (str/includes? url "/history"))
      {:status 404 :body {} :url url :method "GET" :headers (:headers req)}

      (and (str/includes? url "/history")
           (contains? (g/get :gmail-history-fail) (history-since req url)))
      {:status 500 :body {:error {:message "Internal error"}} :url url :method "GET"
       :headers (:headers req)}

      (str/includes? url "/history")
      (let [since (history-since req url)
            page  (get (g/get :gmail-history) since)]
        {:status 200 :body (or page {:history [] :historyId since}) :url url :method "GET"
         :headers (:headers req)})

      (str/includes? url "/users/me/watch")
      (let [grant (or (g/get :gmail-watch-grant) {:status 200 :historyId "1" :expiration "0"})]
        (if (:error grant)
          {:status (:status grant) :body {:error {:message (:error grant)}} :url url :method "POST" :headers (:headers req)}
          {:status 200 :body (select-keys grant [:historyId :expiration]) :url url :method "POST" :headers (:headers req)}))

      (str/includes? url "/users/me/stop")
      {:status 204 :body nil :url url :method "POST" :headers (:headers req)}

      (and (str/includes? url "/messages/send") (= "POST" (str/upper-case (name (:method req "GET")))))
      {:status 200
       :body   {:id "sent-1" :threadId (get-in req [:body :threadId])}
       :url    url
       :method "POST"
       :headers (:headers req)}

      (and (re-find #"/messages/[^/?]+/modify" url) (= "POST" (str/upper-case (name (:method req "GET")))))
      (let [id          (last (re-find #"/messages/([^/?]+)/modify" url))
            add         (get-in req [:body :addLabelIds])
            remove-ids  (set (get-in req [:body :removeLabelIds]))
            existing    (get (g/get :gmail-messages) id)
            current     (vec (or (:labelIds existing) ["INBOX"]))
            updated     (vec (remove remove-ids (distinct (concat current add))))]
        (g/update! :gmail-messages (fnil assoc {}) id (merge existing {:id id :labelIds updated}))
        {:status 200 :body {:id id :labelIds updated} :url url :method "POST" :headers (:headers req)})

      (re-find #"/threads/[^/?]+" url)
      (let [tid (last (re-find #"/threads/([^/?]+)" url))
            msgs (->> (vals (g/get :gmail-messages))
                     (filter #(= tid (:threadId %))))]
        {:status 200 :body {:id tid :messages (vec msgs)} :url url :method "GET" :headers (:headers req)})

      (re-find #"/messages/[^/?]+" url)
      (let [id  (or (last (re-find #"/messages/([^/?]+)" url))
                    (get-in req [:query :id]))
            msg (get (g/get :gmail-messages) id)]
        {:status 200 :body (or msg {}) :url url :method "GET" :headers (:headers req)})

      (str/includes? url "/messages")
      {:status 200 :body {:messages (or (g/get :gmail-inbox) [])} :url url :method "GET"
       :headers (:headers req)}

      :else
      {:status 200 :body {} :url url :method (str (:method req)) :headers (:headers req)})))

(defn- parse-labels [labs]
  (cond
    (nil? labs) ["INBOX"]
    (string? labs) (vec (remove str/blank? (str/split labs #"[,\s]+")))
    :else labs))

(defn- row->history-record [row]
  (let [kind (or (get row "kind") "messageAdded")
        msg  {:id       (get row "id")
              :threadId (get row "threadId")
              :labelIds (parse-labels (get row "labelIds"))}]
    (case kind
      "labelAdded" {:labelsAdded [{:message msg}]}
      {:messagesAdded [{:message msg}]})))

(defn- remember-thread! [row]
  (when-let [id (get row "id")]
    (g/update! :gmail-messages
               (fnil update {})
               id
               (fn [existing]
                 (merge {:id id :threadId (get row "threadId") :labelIds (parse-labels (get row "labelIds"))}
                        existing)))))

(defn- next-history-id
  "Gmail's real history.list response carries a top-level :historyId ahead of
   `since` — the mailbox's current state, independent of what the caller
   asked about. Push scenarios never read it (the pushed notification
   supplies the next cursor directly); pull scenarios do
   (isaac.comm.gmail.history/walk-page's :cursor), so a stub batch advances
   history by a fixed, suite-wide amount rather than echoing `since` back."
  [since]
  (if-let [n (try (parse-long (str since)) (catch Exception _ nil))]
    (str (+ n 42))
    (str since)))

(defn history-adds-messages [since table]
  (let [rows (table-rows table)
        page {:historyId (next-history-id since)
              :history   (mapv row->history-record rows)}]
    (doseq [row rows] (remember-thread! row))
    (g/update! :gmail-history-fail (fnil disj #{}) (str since))
    (g/update! :gmail-history (fnil assoc {}) (str since) page)))

(defn history-contains [since table]
  (history-adds-messages since table))

(defn history-gone [since]
  (g/assoc! :gmail-gone true)
  (g/update! :gmail-history (fnil assoc {}) (str since) {:status 404 :error :not-found}))

(defn history-fails-with-500 [since]
  (g/update! :gmail-history-fail (fnil conj #{}) (str since)))

(defn- synthesized-auth-results
  "Test messages don't carry a real Authentication-Results header. Routes
   scenarios care about matching, not authentication (that's gmail.feature's
   isaac-dymn job), so a stub message authenticates for its own From domain
   by default — a scenario that wants to test the :unauthenticated path (or
   the old *@domain allow-from tests) overrides :auth-results explicitly, and
   that override always wins."
  [from]
  (let [addr   (gate/address from)
        domain (second (re-find #"@(.+)$" (str addr)))]
    (when (seq domain)
      (str "mx.google.com; dkim=pass header.d=" domain "; spf=pass smtp.mailfrom=" domain
          "; dmarc=pass header.from=" domain))))

(defn returns-message [id table]
  (let [m        (table-map table)
        existing (get (g/get :gmail-messages) id)
        from     (or (get m "from") (get m :from))
        msg      {:id         id
                  :from       from
                  :to         (or (get m "to") (get m :to))
                  :subject    (or (get m "subject") (get m :subject))
                  :message-id (or (get m "message-id") (get m :message-id))
                  :body       (or (get m "body") (get m :body))
                  :auth-results (or (get m "auth-results") (get m :auth-results)
                                    (synthesized-auth-results from))
                  :precedence (or (get m "precedence") (get m :precedence))
                  :labelIds   (or (:labelIds existing) ["INBOX"])
                  :threadId   (or (get m "threadId") (get m :threadId) (:threadId existing))
                  :historyId  (or (get m "historyId") (:historyId existing))}]
    (g/update! :gmail-messages (fnil assoc {}) id (merge existing msg))))

(defn message-already-carries-label [id label]
  (g/update! :gmail-messages
             (fnil update {})
             id
             (fn [existing]
               (let [current (vec (or (:labelIds existing) ["INBOX"]))]
                 (merge {:id id} existing {:labelIds (vec (distinct (conj current label)))})))))

(defn message-carries-label [id label]
  (let [labs (:labelIds (get (g/get :gmail-messages) id))]
    (g/should (contains? (set labs) label))))

(defn message-does-not-carry-label [id label]
  (let [labs (:labelIds (get (g/get :gmail-messages) id))]
    (g/should-not (contains? (set labs) label))))

(defn label-created-times [name n]
  (let [n       (if (string? n) (parse-long n) n)
        reqs    (or (g/get :outbound-http-requests) [])
        matches (filter (fn [r] (and (str/includes? (str (:url r)) "/labels")
                                     (= "POST" (:method r))
                                     (= name (get-in r [:body :name]))))
                        reqs)]
    (g/should= n (count matches))))

(defn config-validate-reports-unknown-action-for-route [route-name]
  (ensure-gmail-factory!)
  (inject-gmail-module!)
  (let [fs*    (feature-fs)
        root   (root-dir)
        result (nexus/-with-nested-nexus {:fs fs* :root root}
                 (loader/load-config-result {:root root :fs fs*}))
        errors (:errors result)]
    (g/should (some (fn [e]
                      (and (str/includes? (str (:key e)) (str "gmail-routes." route-name))
                           (re-find #"(?i)unknown action" (str (:value e)))))
                    errors))))

(defn inbox-lists [table]
  (let [rows (mapv (fn [row]
                     {:id        (get row "id")
                      :threadId  (get row "threadId")
                      :historyId (get row "historyId")
                      :labelIds  ["INBOX"]})
                   (table-rows table))]
    (g/assoc! :gmail-inbox rows)
    (doseq [row rows]
      (g/update! :gmail-messages
                 (fnil update {})
                 (:id row)
                 (fn [existing] (merge row existing))))))

(defn cursor-is [id]
  (let [root (or (root-dir) "/test/gmail")]
    (if (g/get :gmail-watch-pushed)
      (let [actual (with-feature-fs (fn [] (cursor/load-cursor root)))]
        (g/should= (str id) (str actual)))
      (with-feature-fs
        (fn []
          (cursor/save-cursor! root (str id)))))))

(defn gmail-comm-registered
  "Register one configured Gmail comm by name as the comm under test."
  [comm-name]
  (ensure-gmail-factory!)
  (inject-gmail-module!)
  (ensure-session-store!)
  (let [fs*  (feature-fs)
        root (root-dir)
        comm (gmail/make {:name (keyword comm-name) :root root})
        cfg  (nexus/-with-nested-nexus {:fs fs* :root root}
               (load-comm-cfg comm-name))]
    (reset! (.-cfg comm) cfg)
    (comm-registry/register-instance! (name comm-name) comm)
    (g/assoc! :gmail-comm comm)))

(defn- gmail-outbound-comm-registered []
  (gmail-comm-registered "gmail"))

(defn google-auth-store-for-organization
  "Seed one organization's tokens in the auth store."
  [organization at rt]
  (auth-store/save-tokens! (root-dir)
                           (tenants/auth-provider (keyword organization))
                           {:access_token at :refresh_token rt :expires_in 3600}
                           (feature-fs)))

(defn- stored-access-token
  "The access token one organization has in the auth store, or the token every
   gmail scenario that never signed in has been using. Every login belongs to
   an organization, so without one there is no store to read (isaac-okfj)."
  [id]
  (or (some-> (when id
                (auth-store/load-tokens (or (root-dir) "target/test-state")
                                        (tenants/auth-provider id)
                                        (feature-fs)))
              (#(or (:access %) (:access_token %))))
      (g/get :gmail-access-token)
      "at-1"))

(defn- with-gmail-stubs
  "Installs the Gmail HTTP/token stubs for the rest of the scenario, not just
   the dynamic extent of `f`. `the delivery worker ticks` (isaac-agent,
   isaac-iwio) runs the real registered gmail comm in a later, separate step
   call after `f` has already returned — a `with-redefs` here would have
   unwound by then, so the comm's real send!* would hit the real Gmail API
   and 401. alter-var-root persists the stub across step calls; the
   after-scenario hook below restores the originals."
  [f]
  (alter-var-root #'gmail-api/-http! (constantly stub-http!))
  (alter-var-root #'gmail-api/access-token (constantly #(stored-access-token tenants/*tenant*)))
  (f))

(defn- record-from-table
  "A | path | value | table as a send record."
  [{:keys [rows]}]
  (into {} (map (fn [[path value]] [(keyword path) value])) rows))

(defn gmail-comm-send!
  "Invoke send! on the comm under test with the record the table describes."
  [table]
  (let [record (record-from-table table)
        comm   (g/get :gmail-comm)
        fs*    (feature-fs)
        root   (root-dir)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (with-gmail-stubs
        (fn []
          (comm/send! comm record))))))

(defn push-watch [history-id]
  (ensure-gmail-factory!)
  (inject-gmail-module!)
  (ensure-session-store!)
  (when-not (g/get :gmail-comm)
    (gmail-outbound-comm-registered))
  (let [fs*  (feature-fs)
        root (root-dir)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (let [cfg (:config (loader/load-config-result {:root root :fs fs*}))]
        (config/dangerously-install-config! cfg "gmail feature")
        (grover/clear-provider-requests!)
        ;; Scope outbound-HTTP assertions to this push, same as the grover
        ;; clear above — a scenario with two pushes (e.g. the ack toggle in
        ;; tasks.feature) asserts each push's own outbound requests, not the
        ;; cumulative scenario history (isaac-3427).
        (g/assoc! :outbound-http-requests [])
        (when-let [comm (g/get :gmail-comm)]
          (reset! (.-cfg comm) (or (get-in cfg [:comms :gmail])
                                   (get-in cfg [:comms "gmail"])
                                   {})))
        (with-gmail-stubs
          (fn []
            (handler/handle-watch! {:data {:emailAddress "yopp@tonotop.com"
                                           :historyId    (str history-id)}})
            (g/assoc! :gmail-watch-pushed true)
            (session-steps/await-turn!)))))))

(defn gmail-pull-timer-ticks []
  (ensure-gmail-factory!)
  (inject-gmail-module!)
  (ensure-session-store!)
  (let [fs*  (feature-fs)
        root (root-dir)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (let [cfg (:config (loader/load-config-result {:root root :fs fs*}))]
        (config/dangerously-install-config! cfg "gmail pull feature")
        (grover/clear-provider-requests!)
        (with-gmail-stubs
          (fn []
            (gmail-pull/tick!)
            (g/assoc! :gmail-watch-pushed true)
            (session-steps/await-turn!)))))))

(defn- ensure-gmail-scheduler! []
  (or @live-scheduler*
      (let [s (scheduler/create {})]
        (reset! live-scheduler* s)
        (nexus/register! [:scheduler] s)
        s)))

(defn- boot-gmail-schedule!
  "Loads live config and runs isaac.comm.gmail.pull/start! against a
   scheduler installed in nexus for this scenario — the feature-level
   equivalent of a real module boot registering :gmail/pull (isaac-u80t).
   Neither `the Google runtime component is started` (isaac-google's own
   step; door-only, read-only here) nor the generic `config:` step reload
   config or run module lifecycle, so this is what actually exercises it."
  []
  (ensure-gmail-factory!)
  (inject-gmail-module!)
  (let [fs*   (feature-fs)
        root  (root-dir)
        sched (ensure-gmail-scheduler!)]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (let [cfg (:config (loader/load-config-result {:root root :fs fs*}))]
        (config/dangerously-install-config! cfg "gmail pull-schedule feature")
        (gmail-pull/start! cfg)))
    sched))

(defn gmail-scheduled-tasks-include [table]
  (let [sched  (boot-gmail-schedule!)
        tasks  (mapv (fn [t] {:id (:id t) :interval-ms (get-in t [:trigger :ms])})
                     (scheduler/list-tasks sched))
        result (match/match-entries table tasks)]
    (g/should= [] (:failures result))))

(defn gmail-scheduled-tasks-are-empty []
  (let [sched (boot-gmail-schedule!)]
    (g/should= [] (scheduler/list-tasks sched))))

(defn gmail-grants-watch [history-id expires-at]
  (g/assoc! :gmail-watch-grant {:historyId  (str history-id)
                                :expiration (str (.toEpochMilli (java.time.Instant/parse expires-at)))}))

(defn gmail-refuses-watch [status message]
  (g/assoc! :gmail-watch-grant {:error message :status (if (string? status) (parse-long status) status)}))

(defn timer-remembers-watch [account expires-at]
  (with-feature-fs
    (fn []
      (google-registration/save-state!
        (root-dir)
        (assoc (google-registration/load-state (root-dir)) account {:name account :expires-at expires-at})))))

(defn gmail-watch-timer-ticks []
  (ensure-gmail-factory!)
  (inject-gmail-module!)
  (let [fs*  (feature-fs)
        root (root-dir)
        now  (or (g/get :current-time) (java.time.Instant/now))]
    (nexus/-with-nested-nexus {:fs fs* :root root}
      (let [cfg (:config (loader/load-config-result {:root root :fs fs*}))]
        (config/dangerously-install-config! cfg "gmail watch feature")
        (with-redefs [isaac.google.events/list-subscriptions! (constantly {:subscriptions []})]
          (with-gmail-stubs
            (fn []
              (google-registration/tick! {:now now :root root :door-up? true}))))))))

(defn hail-module-installed
  "Stub isaac.hail.queue/send! by interning it directly (not `require`,
   which would mark the lib loaded and defeat the after-scenario cleanup) so
   isaac.comm.gmail.tasks' requiring-resolve finds it, same as a real
   isaac-hail install would. Every record it's called with is captured under
   :hail-sent for the Then steps (isaac-3427)."
  []
  (create-ns 'isaac.hail.queue)
  (intern 'isaac.hail.queue 'send!
          (fn [record]
            (g/update! :hail-sent (fnil conj []) record)
            record)))

(defn- hails-sent [] (or (g/get :hail-sent) []))

(defn- hails-for-band [band]
  (filter #(= band (get-in % [:frequencies :band])) (hails-sent)))

(defn one-hail-sent-to-band [band table]
  (let [matches (hails-for-band band)]
    (g/should= 1 (count matches))
    (let [record (first matches)]
      (doseq [{path "path" value "value"} (table-rows table)]
        (g/should= value (str (get-in record [:params (keyword path)])))))))

(defn no-hail-was-sent []
  (g/should (empty? (hails-sent))))

(defn- regex-cell
  "The pattern string out of a `#\"...\"` table cell, or nil for a literal
   cell (TABLES.md's shared Cell Syntax: full-string regex match, DOTALL)."
  [v]
  (when (string? v)
    (second (re-matches #"(?s)#\"(.*)\"" v))))

(defn- cell-matches? [expected actual]
  (if-let [pattern (regex-cell expected)]
    (boolean (re-find (java.util.regex.Pattern/compile pattern java.util.regex.Pattern/DOTALL) (str actual)))
    (= expected actual)))

(defn- header-from-raw [raw name]
  (when (seq raw)
    (let [decoded (try (gmail-api/decode-raw raw) (catch Exception _ raw))
          lines   (str/split-lines decoded)
          prefix  (str name ":")]
      (some (fn [line]
              (when (str/starts-with? (str/lower-case line) (str/lower-case prefix))
                (str/trim (subs line (count prefix)))))
            lines))))

(defn- body-from-raw [raw]
  (when (seq raw)
    (let [decoded (try (gmail-api/decode-raw raw) (catch Exception _ raw))
          idx     (str/index-of decoded "\r\n\r\n")]
      (if idx
        (str/trim (subs decoded (+ idx 4)))
        decoded))))

(defn gmail-api-sent-count [n]
  (let [sends (filter #(str/includes? (str (:url %)) "/messages/send")
                      (or (g/get :outbound-http-requests) []))]
    (g/should= (parse-long (str n)) (count sends))))

(defn sent-mail-decodes [table]
  (let [expected (table-map table)
        req      (or (last (filter #(str/includes? (str (:url %)) "/messages/send")
                                   (or (g/get :outbound-http-requests) [])))
                     (g/get :outbound-http-request))
        raw      (get-in req [:body :raw])
        decoded  (try (gmail-api/decode-raw raw) (catch Exception _ ""))]
    (g/should (seq raw))
    (doseq [[k v] expected]
      (let [key (str k)]
        (case key
          "text" (g/should (cell-matches? v (body-from-raw raw)))
          (g/should (cell-matches? v (header-from-raw raw key))))))
    (g/should (seq decoded))))

(defn sent-mail-to-decodes
  "Recipient-scoped variant of `the sent mail decodes to:` (isaac-iwio): a
   scenario that sends more than one email in a turn (the origin reply plus
   a comm__send) picks out the one addressed to `address` by its To header,
   then reuses the same decode/match logic."
  [address table]
  (let [expected (table-map table)
        sends    (filter #(str/includes? (str (:url %)) "/messages/send")
                         (or (g/get :outbound-http-requests) []))
        req      (some (fn [r]
                         (let [raw (get-in r [:body :raw])]
                           (when (and (seq raw) (= address (header-from-raw raw "To")))
                             r)))
                       sends)
        raw      (get-in req [:body :raw])
        decoded  (try (gmail-api/decode-raw raw) (catch Exception _ ""))]
    (g/should (seq raw))
    (doseq [[k v] expected]
      (let [key (str k)]
        (case key
          "text" (g/should (cell-matches? v (body-from-raw raw)))
          (g/should (cell-matches? v (header-from-raw raw key))))))
    (g/should (seq decoded))))

(defgiven "the gmail history cursor is {id:string}"
  isaac.gmail-steps/cursor-is)

(defgiven #"the Gmail API history since \"([^\"]+)\" adds messages:"
  isaac.gmail-steps/history-adds-messages)

(defgiven #"the Gmail API history since \"([^\"]+)\" contains:"
  isaac.gmail-steps/history-contains)

(defgiven #"the Gmail API history since \"([^\"]+)\" is gone"
  isaac.gmail-steps/history-gone)

(defgiven #"the Gmail API history since \"([^\"]+)\" fails with 500"
  isaac.gmail-steps/history-fails-with-500)

(defgiven #"the Gmail API returns message \"([^\"]+)\":"
  isaac.gmail-steps/returns-message)

(defgiven "the Gmail API inbox lists messages:"
  isaac.gmail-steps/inbox-lists)

(defwhen #"Gmail pushes a watch notification with history id \"([^\"]+)\""
  isaac.gmail-steps/push-watch)

(defgiven #"the Gmail API grants a watch with history id \"([^\"]+)\" expiring at \"([^\"]+)\""
  isaac.gmail-steps/gmail-grants-watch)

(defgiven #"the Gmail API refuses the watch with (\d+) \"([^\"]+)\""
  isaac.gmail-steps/gmail-refuses-watch)

(defgiven #"the registration timer remembers a watch for \"([^\"]+)\" expiring at \"([^\"]+)\""
  isaac.gmail-steps/timer-remembers-watch)

(defgiven #"gmail comm \"([^\"]+)\" is registered"
  isaac.gmail-steps/gmail-comm-registered)

(defgiven #"the google auth store for organization \"([^\"]+)\" has access \"([^\"]+)\" and refresh \"([^\"]+)\""
  isaac.gmail-steps/google-auth-store-for-organization)

(defwhen "gmail comm send! is invoked with:"
  isaac.gmail-steps/gmail-comm-send!)

(defwhen "the Gmail watch timer ticks"
  isaac.gmail-steps/gmail-watch-timer-ticks
  "One reconcile pass of isaac-google's registration timer with the Gmail API stubbed.")

(defwhen "the Gmail pull timer ticks"
  isaac.gmail-steps/gmail-pull-timer-ticks
  "One isaac.comm.gmail.pull/tick! with the Gmail API stubbed — the pull-mode
   counterpart of 'the Gmail watch timer ticks'.")

(defthen "the gmail scheduled tasks include:"
  isaac.gmail-steps/gmail-scheduled-tasks-include
  "Boots isaac.comm.gmail.pull/start! against live config and a scenario
   scheduler, then matches its :id/:interval-ms against the table.")

(defthen "the gmail scheduled tasks are empty"
  isaac.gmail-steps/gmail-scheduled-tasks-are-empty)

(defthen "the sent mail decodes to:"
  isaac.gmail-steps/sent-mail-decodes)

(defthen #"the sent mail to \"([^\"]+)\" decodes to:"
  isaac.gmail-steps/sent-mail-to-decodes)

(defthen #"the Gmail API sent (\d+) messages?"
  isaac.gmail-steps/gmail-api-sent-count)

(defgiven #"message \"([^\"]+)\" already carries label \"([^\"]+)\""
  isaac.gmail-steps/message-already-carries-label)

(defthen #"message \"([^\"]+)\" carries label \"([^\"]+)\""
  isaac.gmail-steps/message-carries-label)

(defthen #"message \"([^\"]+)\" does not carry label \"([^\"]+)\""
  isaac.gmail-steps/message-does-not-carry-label)

(defthen #"the Gmail API created label \"([^\"]+)\" (\d+) times"
  isaac.gmail-steps/label-created-times)

(defthen #"isaac config validate reports an unknown action for route \"([^\"]+)\""
  isaac.gmail-steps/config-validate-reports-unknown-action-for-route)

(defgiven "the hail module is installed"
  isaac.gmail-steps/hail-module-installed)

(defthen #"one hail was sent to band \"([^\"]+)\" with:"
  isaac.gmail-steps/one-hail-sent-to-band)

(defthen "no hail was sent"
  isaac.gmail-steps/no-hail-was-sent)

