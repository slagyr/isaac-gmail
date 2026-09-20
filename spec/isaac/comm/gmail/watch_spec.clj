(ns isaac.comm.gmail.watch-spec
  (:require
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.watch :as sut]
    [isaac.config.loader :as loader]
    [isaac.fs :as fs]
    [isaac.google.registration :as registration]
    [isaac.google.tenants :as tenants]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def cfg {:google {:topic "projects/marigold/topics/isaac"}
          :comms  {:gmail {:gmail/account "yopp@tonotop.com"}}})

(def tenanted
  {:google {:tonotop {:project "marigold"  :topic "projects/marigold/topics/isaac"}
            :acme    {:project "acme-prod" :topic "projects/acme-prod/topics/isaac"}}
   :comms  {:gmail      {:gmail/account "yopp@tonotop.com" :google :tonotop}
            :gmail-acme {:type :gmail :google :acme :gmail/account "isaac@acme.example"}}})

(describe "gmail watch registration"

  (with requests (atom []))
  (with response (atom {:status 200 :body {:historyId "900" :expiration "1790424000000"}}))

  (around [it]
    (nexus/-with-nexus {:root "/root" :fs (fs/mem-fs)}
      (with-redefs [loader/snapshot        (fn [_] cfg)
                    gmail-api/access-token (constantly "at-1")
                    gmail-api/-http!       (fn [req] (swap! @requests conj req) @@response)]
        (it))))

  (it "keys the entry by the configured mailbox"
    (should= ["yopp@tonotop.com"] (sut/keys*)))

  (it "keys the entry by the mailboxes of the organization this pass acts for"
    (with-redefs [loader/snapshot (fn [_] tenanted)]
      (should= ["isaac@acme.example"] (binding [tenants/*tenant* :acme] (sut/keys*)))
      (should= ["yopp@tonotop.com"] (binding [tenants/*tenant* :tonotop] (sut/keys*)))))

  (it "watches a mailbox against its own organization's topic"
    (with-redefs [loader/snapshot (fn [_] tenanted)]
      (binding [tenants/*tenant* :acme]
        (sut/watch! "isaac@acme.example"))
      (should= "projects/acme-prod/topics/isaac" (get-in (first @@requests) [:body :topicName]))))

  (it "has no key when no gmail account is configured"
    (with-redefs [loader/snapshot (fn [_] {:comms {:gmail {}}})]
      (should= [] (sut/keys*))))

  (it "watch! POSTs users.watch on INBOX to the shared topic as the Google user"
    (let [result (sut/watch! "yopp@tonotop.com")
          req    (first @@requests)]
      (should= "https://gmail.googleapis.com/gmail/v1/users/me/watch" (:url req))
      (should= "Bearer at-1" (get-in req [:headers "Authorization"]))
      (should= {:topicName "projects/marigold/topics/isaac" :labelIds ["INBOX"] :labelFilterAction "include"} (:body req))
      (should= "yopp@tonotop.com" (:name result))
      (should= 200 (:status result))))

  (it "reads the expiry out of Gmail's epoch-millis expiration"
    (should= "2026-09-26T12:00:00Z" (str (sut/expiry {:expiration "1790424000000"}))))

  (it "seeds the history cursor from a successful watch"
    (sut/watch! "yopp@tonotop.com")
    (should= "900" (isaac.comm.gmail.cursor/load-cursor "/root")))

  (it "hands a refused watch back with its status so the timer logs and retries"
    (reset! @response {:status 403 :body {:error {:message "Insufficient Permission"}}})
    (let [result (sut/watch! "yopp@tonotop.com")]
      (should= 403 (:status result))
      (should-be-nil (isaac.comm.gmail.cursor/load-cursor "/root"))))

  (it "sees only mailbox watches in the timer's shared state"
    (registration/save-state! "/root" {"spaces/ENG"       {:name "subscriptions/s-eng" :expires-at "2026-09-25T12:00:00Z"}
                                       "yopp@tonotop.com" {:name "yopp@tonotop.com" :expires-at "2026-09-26T12:00:00Z"}})
    (should= {"yopp@tonotop.com" {:name "yopp@tonotop.com" :expires-at "2026-09-26T12:00:00Z"}} (sut/remote)))

  (it "stop! POSTs users.stop"
    (sut/stop! "yopp@tonotop.com")
    (should= "https://gmail.googleapis.com/gmail/v1/users/me/stop" (:url (first @@requests))))
  )
