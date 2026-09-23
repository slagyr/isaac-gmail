(ns isaac.comm.gmail.routes-spec
  (:require
    [isaac.comm.gmail.routes :as sut]
    [speclj.core :refer :all]))

(def dmarc-pass "mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com; dmarc=pass header.from=tonotop.com")

(defn- msg [overrides]
  (merge {:id "m-1" :from "ada@tonotop.com" :from-email "ada@tonotop.com"
          :to "yopp@tonotop.com" :subject "Hi" :labelIds ["INBOX"]
          :auth-results dmarc-pass}
         overrides))

(describe "gmail routes/decide"

  (it "is unrouted — nothing converses — when the routes table is empty"
    (should= {:route "unrouted" :action :unrouted :crew nil :gate nil :blocked #{}}
             (sut/decide {} (msg {}))))

  (it "matches an exact-domain route and names its crew"
    (let [cfg {:gmail-routes {:ops {:order 10 :match {:to "yopp+ops@*" :from "*@tonotop.com"}
                                    :action :converse :crew "ops"}}}]
      (should= {:route "ops" :action :converse :crew "ops" :gate nil :blocked #{}}
               (sut/decide cfg (msg {:to "yopp+ops@tonotop.com"})))))

  (it "the first matching route (by :order, ties by name) wins"
    (let [cfg {:gmail-routes {:team {:order 90 :match {:from "*@tonotop.com"} :action :converse :crew "main"}
                              :ops  {:order 10 :match {:to "yopp+ops@*" :from "*@tonotop.com"}
                                    :action :converse :crew "ops"}}}]
      (should= "ops" (:route (sut/decide cfg (msg {:to "yopp+ops@tonotop.com"}))))))

  (it "a message matching no route is :unrouted"
    (let [cfg {:gmail-routes {:ops {:order 10 :match {:to "yopp+ops@*" :from "*@tonotop.com"}
                                    :action :converse :crew "ops"}}}]
      (should= {:route "unrouted" :action :unrouted :crew nil :gate nil :blocked #{}}
               (sut/decide cfg (msg {:subject "Random"})))))

  (it "an :ignore route needs no :from"
    (let [cfg {:gmail-routes {:newsletters {:order 30 :match {:from "*@substack.com"} :action :ignore}}}]
      (should= "newsletters" (:route (sut/decide cfg (msg {:from "d@substack.com" :from-email "d@substack.com"}))))))

  (describe "wildcard-domain :from requires authentication (isaac-dymn)"

    (def cfg {:gmail-routes {:team {:order 90 :match {:from "*@tonotop.com"} :action :converse :crew "main"}}})

    (it "matches when Gmail authenticates the domain"
      (should= "team" (:route (sut/decide cfg (msg {:auth-results dmarc-pass})))))

    (it "is unrouted, and names the route as blocked, when Gmail does not vouch"
      (let [decision (sut/decide cfg (msg {:auth-results nil}))]
        (should= :unrouted (:action decision))
        (should= #{"team"} (:blocked decision)))))

  (describe "gate signals win over a matching converse route"

    (def cfg {:gmail-routes {:team {:order 90 :match {:from "*@tonotop.com"} :action :converse :crew "main"}}})

    (it "Precedence: bulk"
      (should= {:route "ignored" :action :ignore :gate :precedence}
               (select-keys (sut/decide cfg (msg {:precedence "bulk"})) [:route :action :gate])))

    (it "Auto-Submitted other than no"
      (should= :auto-submitted (:gate (sut/decide cfg (msg {:auto-submitted "auto-replied"})))))

    (it "List-Unsubscribe present"
      (should= :list-unsubscribe (:gate (sut/decide cfg (msg {:list-unsubscribe "<mailto:x@y>"})))))

    (it "a non-personal Gmail category label"
      (should= :category (:gate (sut/decide cfg (msg {:labelIds ["INBOX" "CATEGORY_PROMOTIONS"]})))))

    (it "does not fire on Precedence: normal, or CATEGORY_PERSONAL"
      (should-be-nil (:gate (sut/decide cfg (msg {:precedence "normal"}))))
      (should-be-nil (:gate (sut/decide cfg (msg {:labelIds ["INBOX" "CATEGORY_PERSONAL"]}))))))

  (describe "match keys"

    (it ":subject is a regex string"
      (let [cfg {:gmail-routes {:inv {:order 10 :match {:from "*@tonotop.com" :subject "(?i)\\binvoice\\b"}
                                      :action :converse :crew "finance"}}}]
        (should= "inv" (:route (sut/decide cfg (msg {:subject "Your Invoice is due"}))))
        (should= "unrouted" (:route (sut/decide cfg (msg {:subject "Lunch?"}))))))

    (it ":label must be present on the message"
      (let [cfg {:gmail-routes {:starred {:order 10 :match {:from "*@tonotop.com" :label "STARRED"}
                                          :action :converse :crew "main"}}}]
        (should= "starred" (:route (sut/decide cfg (msg {:labelIds ["INBOX" "STARRED"]}))))
        (should= "unrouted" (:route (sut/decide cfg (msg {:labelIds ["INBOX"]}))))))

    (it "every given key is AND'd together"
      (let [cfg {:gmail-routes {:ops {:order 10 :match {:to "yopp+ops@*" :from "*@tonotop.com"}
                                      :action :converse :crew "ops"}}}]
        (should= "unrouted" (:route (sut/decide cfg (msg {:to "yopp@tonotop.com"})))))))

  (describe "check-config (isaac config validate)"

    (it "flags an action outside :converse/:ignore/:task, naming the route"
      (let [cfg {:gmail-routes {:broken {:order 10 :match {:from "*@tonotop.com"} :action :archive}}}]
        (should= [{:key "gmail-routes.broken.action" :value "unknown action :archive for route \"broken\""}]
                 (:errors (sut/check-config {:config cfg})))))

    (it "flags a :converse route with no :match :from"
      (let [cfg {:gmail-routes {:ops {:order 10 :match {:to "yopp+ops@*"} :action :converse :crew "ops"}}}]
        (should= [{:key   "gmail-routes.ops.match.from"
                   :value "route \"ops\" is a :converse route and must name :match :from"}]
                 (:errors (sut/check-config {:config cfg})))))

    (it "is silent on a well-formed table"
      (let [cfg {:gmail-routes {:ops {:order 10 :match {:from "*@tonotop.com"} :action :converse :crew "ops"}
                                :nl  {:order 30 :match {:from "*@substack.com"} :action :ignore}}}]
        (should= [] (:errors (sut/check-config {:config cfg})))))

    (it "flags a :task route with no :match :from (isaac-3427)"
      (let [cfg {:gmail-routes {:invoices {:order 20 :match {} :action :task :band "ops-inbox"}}}]
        (should= [{:key   "gmail-routes.invoices.match.from"
                   :value "route \"invoices\" is a :task route and must name :match :from"}]
                 (:errors (sut/check-config {:config cfg})))))

    (it "flags a :task route with no :band (isaac-3427)"
      (let [cfg {:gmail-routes {:invoices {:order 20 :match {:from "*@tonotop.com"} :action :task}}}]
        (should= [{:key   "gmail-routes.invoices.band"
                   :value "route \"invoices\" is a :task route and must name :band"}]
                 (:errors (sut/check-config {:config cfg})))))

    (it "is silent on a well-formed :task route"
      (let [cfg {:gmail-routes {:invoices {:order 20 :match {:from "*@tonotop.com"} :action :task :band "ops-inbox"}}}]
        (should= [] (:errors (sut/check-config {:config cfg})))))))

(describe "gmail routes/decide - :task routes (isaac-3427)"

  (it "known-actions includes :task"
    (should (contains? sut/known-actions :task)))

  (it "carries :band and :ack forward on a matching :task route"
    (let [cfg {:gmail-routes {:invoices {:order 20 :match {:from "*@tonotop.com"} :action :task
                                        :band "ops-inbox" :ack true}}}]
      (should= {:route "invoices" :action :task :crew nil :band "ops-inbox" :ack true :gate nil :blocked #{}}
               (sut/decide cfg (msg {})))))

  (it "carries no :ack key when the route doesn't set one"
    (let [cfg {:gmail-routes {:invoices {:order 20 :match {:from "*@tonotop.com"} :action :task :band "ops-inbox"}}}]
      (should-not (contains? (sut/decide cfg (msg {})) :ack))))

  (it "carries the route's own :params forward when given"
    (let [cfg {:gmail-routes {:invoices {:order 20 :match {:from "*@tonotop.com"} :action :task
                                        :band "ops-inbox" :params {:priority "high"}}}}]
      (should= {:priority "high"} (:params (sut/decide cfg (msg {})))))))
