(ns isaac.comm.gmail.pull-spec
  (:require
    [isaac.comm.gmail.cursor :as cursor]
    [isaac.comm.gmail.handler :as handler]
    [isaac.comm.gmail.pull :as sut]
    [isaac.config.loader :as loader]
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [isaac.scheduler.runtime :as scheduler]
    [speclj.core :refer :all]))

(def push-cfg {:comms {:gmail {:gmail/account "yopp@tonotop.com"}}})
(def pull-cfg {:comms {:gmail {:gmail/account "yopp@tonotop.com" :gmail/mode :pull}}})
(def pull-cfg-custom-interval
  {:comms {:gmail {:gmail/account "yopp@tonotop.com" :gmail/mode :pull :gmail/pull-interval-ms 5000}}})

(describe "gmail pull mode config (isaac-u80t)"

  (it "mode defaults to :push"
    (should= :push (sut/mode push-cfg))
    (should= :push (sut/mode {})))

  (it "mode reads gmail/mode :pull"
    (should= :pull (sut/mode pull-cfg)))

  (it "pull-mode? is true only for :pull"
    (should-not (sut/pull-mode? push-cfg))
    (should (sut/pull-mode? pull-cfg)))

  (it "pull-interval-ms defaults to 60000"
    (should= 60000 (sut/pull-interval-ms pull-cfg)))

  (it "pull-interval-ms honours gmail/pull-interval-ms"
    (should= 5000 (sut/pull-interval-ms pull-cfg-custom-interval))))

(describe "gmail pull tick (isaac-u80t)"

  (around [it]
    (nexus/-with-nexus {:root "/root" :fs (fs/mem-fs)}
      (with-redefs [loader/load-config-result (constantly {:config pull-cfg})]
        (it))))

  (it "seeds the cursor from the newest history id and processes nothing when there is no cursor yet"
    (with-redefs [gmail-api/messages-list! (constantly {:messages [{:id "m-9" :historyId "3000"}]})
                  handler/process-message! (fn [& _] (throw (ex-info "should not process on seed" {})))]
      (sut/tick!)
      (should= "3000" (cursor/load-cursor "/root"))))

  (it "seeds the cursor to 0 when the mailbox is empty"
    (with-redefs [gmail-api/messages-list! (constantly {:messages []})]
      (sut/tick!)
      (should= "0" (cursor/load-cursor "/root"))))

  (it "processes new messages and advances the cursor to the walked page's own historyId"
    (cursor/save-cursor! "/root" "1000")
    (let [processed (atom [])]
      (with-redefs [gmail-api/history-list! (constantly {:historyId "1042"
                                                          :history   [{:messagesAdded [{:message {:id "m-1" :threadId "t-1"}}]}]})
                    handler/process-message! (fn [stub _cfg] (swap! processed conj stub))]
        (sut/tick!)
        (should= "1042" (cursor/load-cursor "/root"))
        (should= [{:id "m-1" :threadId "t-1"}] @processed))))

  (it "a failed history.list logs warn and leaves the cursor for the next tick"
    (cursor/save-cursor! "/root" "1000")
    (with-redefs [gmail-api/history-list! (constantly {:error {:message "Internal error"}})
                  handler/process-message! (fn [& _] (throw (ex-info "should not process on failure" {})))]
      (sut/tick!)
      (should= "1000" (cursor/load-cursor "/root"))))

  (it "a gone cursor (404) resyncs through the handler"
    (cursor/save-cursor! "/root" "1000")
    (let [resynced (atom nil)]
      (with-redefs [gmail-api/history-list! (constantly {:status 404 :error :not-found})
                    handler/resync!         (fn [root cur hid cfg] (reset! resynced [root cur hid cfg]))]
        (sut/tick!)
        (should= ["/root" "1000" "1000" pull-cfg] @resynced)))))

(describe "gmail pull scheduling (isaac-u80t)"

  (around [it]
    (let [s (scheduler/create {})]
      (nexus/-with-nested-nexus {:scheduler s}
        (it))))

  (it "schedules :gmail/pull on the shared scheduler in :pull mode"
    (sut/start! pull-cfg)
    (let [tasks (scheduler/list-tasks (nexus/get :scheduler))]
      (should= 1 (count tasks))
      (should= :gmail/pull (:id (first tasks)))
      (should= {:kind :interval :ms 60000} (:trigger (first tasks)))))

  (it "does not schedule in :push mode (the default)"
    (sut/start! push-cfg)
    (should= [] (scheduler/list-tasks (nexus/get :scheduler))))

  (it "is idempotent — a second start! does not throw or duplicate the task"
    (sut/start! pull-cfg)
    (sut/start! pull-cfg)
    (should= 1 (count (scheduler/list-tasks (nexus/get :scheduler)))))

  (it "no-ops when no scheduler is installed"
    (nexus/-with-nested-nexus {:scheduler nil}
      (should-be-nil (sut/start! pull-cfg))))

  (it "stop! cancels the task; safe to call when nothing was ever scheduled"
    (sut/start! pull-cfg)
    (sut/stop! (nexus/get :scheduler))
    (should= [] (scheduler/list-tasks (nexus/get :scheduler)))
    (sut/stop! (nexus/get :scheduler))))
