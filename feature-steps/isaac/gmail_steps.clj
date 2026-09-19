(ns isaac.gmail-steps
  "Gmail comm feature steps: cursor, history/message stubs, watch push, sent-mail decode."
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [gherclj.core :as g :refer [defgiven defthen defwhen helper!]]
    [isaac.comm.factory :as comm-factory]
    [isaac.comm.gmail :as gmail]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.cursor :as cursor]
    [isaac.comm.gmail.handler :as handler]
    [isaac.comm.registry :as comm-registry]
    [isaac.config.api :as config]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.llm.api.grover :as grover]
    [isaac.llm.auth.store :as auth-store]
    [isaac.module.discovery :as discovery]
    [isaac.nexus :as nexus]
    [isaac.session.session-steps :as session-steps]
    [isaac.session.store.memory :as memory-store]
    [isaac.session.store.spi :as session-store]))

(helper! isaac.gmail-steps)

(g/after-scenario
  (fn []
    (alter-var-root #'discovery/*foundation-index-override* (constantly nil))
    (g/dissoc! :gmail-history)
    (g/dissoc! :gmail-messages)
    (g/dissoc! :gmail-inbox)
    (g/dissoc! :gmail-gone)
    (g/dissoc! :gmail-comm)
    (g/dissoc! :gmail-access-token)
    (g/dissoc! :gmail-watch-pushed)))

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

(defn- load-gmail-cfg []
  (let [fs*  (feature-fs)
        root (root-dir)
        cfg  (:config (loader/load-config-result {:root root :fs fs*}))]
    (or (get-in cfg [:comms :gmail])
        (get-in cfg [:comms "gmail"])
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

(defn- stub-http! [req]
  (record-http! req)
  (let [url (str (:url req))]
    (cond
      (and (g/get :gmail-gone) (str/includes? url "/history"))
      {:status 404 :body {} :url url :method "GET" :headers (:headers req)}

      (str/includes? url "/history")
      (let [since (str (or (get-in req [:query :startHistoryId])
                           (second (re-find #"startHistoryId=([^&]+)" url))))
            page  (get (g/get :gmail-history) since)]
        {:status 200 :body (or page {:history [] :historyId since}) :url url :method "GET"
         :headers (:headers req)})

      (and (str/includes? url "/messages/send") (= "POST" (str/upper-case (name (:method req "GET")))))
      {:status 200
       :body   {:id "sent-1" :threadId (get-in req [:body :threadId])}
       :url    url
       :method "POST"
       :headers (:headers req)}

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

(defn history-adds-messages [since table]
  (let [rows (table-rows table)
        page {:historyId (str since)
              :history   (mapv row->history-record rows)}]
    (doseq [row rows] (remember-thread! row))
    (g/update! :gmail-history (fnil assoc {}) (str since) page)))

(defn history-contains [since table]
  (history-adds-messages since table))

(defn history-gone [since]
  (g/assoc! :gmail-gone true)
  (g/update! :gmail-history (fnil assoc {}) (str since) {:status 404 :error :not-found}))

(defn returns-message [id table]
  (let [m        (table-map table)
        existing (get (g/get :gmail-messages) id)
        msg      {:id         id
                  :from       (or (get m "from") (get m :from))
                  :to         (or (get m "to") (get m :to))
                  :subject    (or (get m "subject") (get m :subject))
                  :message-id (or (get m "message-id") (get m :message-id))
                  :body       (or (get m "body") (get m :body))
                  :labelIds   (or (:labelIds existing) ["INBOX"])
                  :threadId   (or (get m "threadId") (get m :threadId) (:threadId existing))
                  :historyId  (or (get m "historyId") (:historyId existing))}]
    (g/update! :gmail-messages (fnil assoc {}) id (merge existing msg))))

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

(defn- gmail-outbound-comm-registered []
  (ensure-gmail-factory!)
  (inject-gmail-module!)
  (ensure-session-store!)
  (let [fs*  (feature-fs)
        root (root-dir)
        comm (gmail/make {:name :gmail :root root})
        cfg  (nexus/-with-nested-nexus {:fs fs* :root root}
               (load-gmail-cfg))]
    (reset! (.-cfg comm) cfg)
    (comm-registry/register-instance! "gmail" comm)
    (g/assoc! :gmail-comm comm)))

(defn- with-gmail-stubs [f]
  (let [token (or (g/get :gmail-access-token) "at-1")]
    (with-redefs [gmail-api/-http!       stub-http!
                  gmail-api/access-token (constantly token)]
      (f))))

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
          "text" (g/should= v (body-from-raw raw))
          (g/should= v (header-from-raw raw key)))))
    (g/should (seq decoded))))

(defgiven "the gmail history cursor is {id:string}"
  isaac.gmail-steps/cursor-is)

(defgiven #"the Gmail API history since \"([^\"]+)\" adds messages:"
  isaac.gmail-steps/history-adds-messages)

(defgiven #"the Gmail API history since \"([^\"]+)\" contains:"
  isaac.gmail-steps/history-contains)

(defgiven #"the Gmail API history since \"([^\"]+)\" is gone"
  isaac.gmail-steps/history-gone)

(defgiven #"the Gmail API returns message \"([^\"]+)\":"
  isaac.gmail-steps/returns-message)

(defgiven "the Gmail API inbox lists messages:"
  isaac.gmail-steps/inbox-lists)

(defwhen #"Gmail pushes a watch notification with history id \"([^\"]+)\""
  isaac.gmail-steps/push-watch)

(defthen "the sent mail decodes to:"
  isaac.gmail-steps/sent-mail-decodes)

