(ns isaac.comm.gmail.labels-spec
  (:require
    [isaac.comm.gmail.api :as api]
    [isaac.comm.gmail.labels :as sut]
    [speclj.core :refer :all]))

(describe "gmail labels (isaac-sb6d)"

  (before (sut/reset-cache!))

  (it "route-label prefixes with gmail/label-prefix, default isaac"
    (should= "isaac/ops" (sut/route-label {} "ops"))
    (should= "acme/ops" (sut/route-label {:gmail/label-prefix "acme"} "ops")))

  (describe "already-routed?"

    (it "true when the message carries any isaac/-prefixed label"
      (should (sut/already-routed? {} {:labelIds ["INBOX" "isaac/team"]})))

    (it "false otherwise"
      (should-not (sut/already-routed? {} {:labelIds ["INBOX" "UNREAD"]}))))

  (describe "label-id!"

    (it "creates the label once and caches it per tenant"
      (let [calls (atom 0)]
        (with-redefs [api/labels-create! (fn [name] (swap! calls inc) {:id name})]
          (should= "isaac/ops" (sut/label-id! :t1 "isaac/ops"))
          (should= "isaac/ops" (sut/label-id! :t1 "isaac/ops"))
          (should= 1 @calls))))

    (it "creates separately per tenant"
      (let [calls (atom 0)]
        (with-redefs [api/labels-create! (fn [name] (swap! calls inc) {:id name})]
          (sut/label-id! :t1 "isaac/ops")
          (sut/label-id! :t2 "isaac/ops")
          (should= 2 @calls)))))

  (describe "apply-label!"

    (it "adds the route's label id"
      (let [modify-calls (atom [])]
        (with-redefs [api/labels-create!  (fn [name] {:id name})
                      api/messages-modify! (fn [id body] (swap! modify-calls conj [id body]))]
          (sut/apply-label! nil {} {:id "m-1"} "ops")
          (should= [["m-1" {:add ["isaac/ops"] :remove nil}]] @modify-calls))))

    (it "removes UNREAD only when told to"
      (let [modify-calls (atom [])]
        (with-redefs [api/labels-create!  (fn [name] {:id name})
                      api/messages-modify! (fn [id body] (swap! modify-calls conj [id body]))]
          (sut/apply-label! nil {} {:id "m-2"} "newsletters" :remove-unread? true)
          (should= [["m-2" {:add ["isaac/newsletters"] :remove ["UNREAD"]}]] @modify-calls))))))
