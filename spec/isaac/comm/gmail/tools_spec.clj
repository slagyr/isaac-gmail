(ns isaac.comm.gmail.tools-spec
  (:require
    [isaac.comm.gmail.api :as gmail-api]
    [isaac.comm.gmail.tools :as sut]
    [speclj.core :refer :all]))

(def a-message
  {:id       "m-1"
   :threadId "t-1"
   :payload  {:headers [{:name "From" :value "Ada Lovelace <ada@tonotop.com>"}
                        {:name "To" :value "yopp@tonotop.com"}
                        {:name "Subject" :value "Deploy window"}
                        {:name "Message-ID" :value "<abc@mail>"}]
              :body    {:data "Q2FuIHdlIHNoaXAgRnJpZGF5Pw"}}})

(describe "gmail tools (isaac-jqk2)"

  (context "gmail__search"

    (it "insists on a query, and says what one looks like"
      (let [result (sut/search {})]
        (should (:isError result))
        (should-contain "from:" (:error result))))

    (it "answers headers for each hit"
      (with-redefs [gmail-api/messages-search! (fn [_] {:messages [{:id "m-1"}]})
                    gmail-api/messages-get!    (fn [_] a-message)]
        (let [result (:result (sut/search {:q "from:ada@tonotop.com"}))]
          (should= 1 (count (:messages result)))
          (should= "Deploy window" (:subject (first (:messages result))))
          (should= "Ada Lovelace <ada@tonotop.com>" (:from (first (:messages result))))
          (should-not-contain :body (keys (first (:messages result)))))))

    (it "passes the query and limit through"
      (let [seen (atom nil)]
        (with-redefs [gmail-api/messages-search! (fn [args] (reset! seen args) {:messages []})]
          (sut/search {:q "is:unread" :limit 5})
          (should= {:q "is:unread" :limit 5} @seen))))

    (it "reports a Gmail failure as a tool error"
      (with-redefs [gmail-api/messages-search! (fn [_] (throw (ex-info "403" {})))]
        (should (:isError (sut/search {:q "is:unread"}))))))

  (context "gmail__read"

    (it "insists on an id"
      (should (:isError (sut/read-message {}))))

    (it "decodes the body"
      (with-redefs [gmail-api/messages-get! (fn [_] a-message)]
        (let [result (:result (sut/read-message {:id "m-1"}))]
          (should= "Can we ship Friday?" (:body result))
          (should= "t-1" (:threadId result))))))

  (context "gmail__labels"

    (it "lists them"
      (with-redefs [gmail-api/labels-list! (fn [] {:labels [{:id "INBOX" :name "INBOX" :type "system"}]})]
        (should= [{:id "INBOX" :name "INBOX" :type "system"}]
                 (:labels (:result (sut/labels {})))))))

  (it "describes each tool"
    (should= ["q"] (:required (:parameters (sut/search-tool-factory {})))))
  )
