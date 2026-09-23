(ns isaac.comm.gmail.tasks-spec
  (:require
    [isaac.comm.gmail.api :as api]
    [isaac.comm.gmail.labels :as labels]
    [isaac.comm.gmail.tasks :as sut]
    [isaac.logger :as log]
    [speclj.core :refer :all]))

(describe "gmail tasks (isaac-3427)"

  (describe "body-excerpt"

    (it "returns the body as-is under the cap"
      (should= "hello" (sut/body-excerpt {} "hello")))

    (it "caps at gmail/task-body-cap"
      (should= "hello" (sut/body-excerpt {:gmail/task-body-cap 5} "hello world")))

    (it "defaults the cap to 4000"
      (should= (apply str (repeat 4000 "x")) (sut/body-excerpt {} (apply str (repeat 5000 "x")))))

    (it "treats a nil body as empty"
      (should= "" (sut/body-excerpt {} nil))))

  (describe "dispatch! - hail module installed"

    (it "sends a hail with the mail payload"
      (let [sent (atom nil)]
        (with-redefs [sut/resolve-hail-send! (fn [] (fn [record] (reset! sent record) record))]
          (sut/dispatch! nil {} {:id "m-1" :threadId "t-1" :from "ada@tonotop.com" :subject "Invoice"
                                 :body "Please file this."}
                        {:route "invoices" :action :task :band "ops-inbox"})
          (should= {:frequencies {:band "ops-inbox"}
                    :params      {:gmail/id "m-1" :gmail/thread-id "t-1" :from "ada@tonotop.com"
                                  :subject "Invoice" :body-excerpt "Please file this."}}
                   @sent))))

    (it "merges the route's own :params under the mail payload, but the mail fields win a collision"
      (let [sent (atom nil)]
        (with-redefs [sut/resolve-hail-send! (fn [] (fn [record] (reset! sent record) record))]
          (sut/dispatch! nil {} {:id "m-1" :threadId "t-1" :from "a@b.com" :subject "s" :body "b"}
                        {:route "invoices" :action :task :band "ops-inbox"
                         :params {:priority "high" :subject "route-override"}})
          (should= "high" (get-in @sent [:params :priority]))
          (should= "s" (get-in @sent [:params :subject])))))

    (it "does not reply when the route sets no :ack"
      (let [send-calls (atom [])]
        (with-redefs [sut/resolve-hail-send! (fn [] (fn [record] record))
                      api/messages-send!      (fn [req] (swap! send-calls conj req))]
          (sut/dispatch! nil {} {:id "m-1" :threadId "t-1" :from "a@b.com" :subject "s" :body "b"}
                        {:route "invoices" :action :task :band "ops-inbox"})
          (should= [] @send-calls))))

    (it "replies once on the thread when :ack is true"
      (let [send-calls (atom [])]
        (with-redefs [sut/resolve-hail-send! (fn [] (fn [record] record))
                      api/messages-send!      (fn [req] (swap! send-calls conj req) {:id "sent-1"})]
          (sut/dispatch! nil {} {:id "m-1" :threadId "t-1" :from "ada@tonotop.com" :subject "Invoice #4821"
                                 :message-id "<inv@tonotop.com>" :body "b"}
                        {:route "invoices" :action :task :band "ops-inbox" :ack true})
          (should= 1 (count @send-calls))
          (should= "t-1" (:thread-id (first @send-calls)))))))

  (describe "dispatch! - hail module not installed (isaac.hail.queue truly absent here)"

    (it "warns, applies the isaac/<route>/unsent label, sends no hail, and never raises"
      (let [labelled     (atom [])
            prior-output (log/output)]
        (log/set-output! :memory)
        (log/clear-entries!)
        (try
          (with-redefs [labels/apply-label! (fn [_tenant _cfg _message route-name & _] (swap! labelled conj route-name))]
            (sut/dispatch! nil {} {:id "m-1" :threadId "t-1" :from "a@b.com" :subject "s" :body "b"}
                          {:route "invoices" :action :task :band "ops-inbox"}))
          (should (some #(and (= :warn (:level %)) (= :gmail.route/hail-unavailable (:event %)))
                       (log/get-entries)))
          (should= ["invoices/unsent"] @labelled)
          (finally (log/set-output! prior-output)))))))
