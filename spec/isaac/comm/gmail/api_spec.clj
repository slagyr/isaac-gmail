(ns isaac.comm.gmail.api-spec
  (:require
    [isaac.comm.gmail.api :as sut]
    [speclj.core :refer :all]))

(defn- stub-http [expected-status body]
  (fn [_req] {:status expected-status :body body}))

(describe "gmail api (isaac-sb6d)"

  (around [it]
    (with-redefs [sut/access-token (constantly "at-1")]
      (it)))

  (describe "labels-create!"

    (it "POSTs the label name and returns Gmail's body"
      (with-redefs [sut/-http! (fn [req]
                                 (should= "POST" (name (:method req)))
                                 (should= sut/LABELS-URL (:url req))
                                 (should= "isaac/ops" (get-in req [:body :name]))
                                 {:status 200 :body {:id "Label_1" :name "isaac/ops"}})]
        (should= {:id "Label_1" :name "isaac/ops"} (sut/labels-create! "isaac/ops")))))

  (describe "messages-modify!"

    (it "POSTs addLabelIds and removeLabelIds to the message's modify endpoint"
      (with-redefs [sut/-http! (fn [req]
                                 (should= "POST" (name (:method req)))
                                 (should= (str sut/MESSAGES-URL "/m-1/modify") (:url req))
                                 (should= ["Label_1"] (get-in req [:body :addLabelIds]))
                                 (should= ["UNREAD"] (get-in req [:body :removeLabelIds]))
                                 {:status 200 :body {:id "m-1"}})]
        (should= {:id "m-1"} (sut/messages-modify! "m-1" {:add ["Label_1"] :remove ["UNREAD"]}))))

    (it "omits addLabelIds/removeLabelIds when empty"
      (with-redefs [sut/-http! (fn [req]
                                 (should-not (contains? (:body req) :addLabelIds))
                                 (should-not (contains? (:body req) :removeLabelIds))
                                 {:status 200 :body {}})]
        (sut/messages-modify! "m-1" {})))))
