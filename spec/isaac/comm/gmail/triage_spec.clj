(ns isaac.comm.gmail.triage-spec
  (:require
    [isaac.api :as api]
    [isaac.comm.gmail.triage :as sut]
    [isaac.session.store.spi :as store]
    [speclj.core :refer :all]))

(describe "gmail triage fallback (isaac-betb)"

  (describe "configured?"

    (it "is true when gmail/triage names a model"
      (should (sut/configured? {:gmail/triage {:model "echo"}})))

    (it "is false with no gmail/triage config at all"
      (should-not (sut/configured? {})))

    (it "is false when gmail/triage is set but names no model"
      (should-not (sut/configured? {:gmail/triage {:crew "main"}}))))

  (describe "crew-id"

    (it "is gmail/triage's own crew when it names one"
      (should= "triage" (#'sut/crew-id {:gmail/triage {:crew "triage"} :defaults {:frequencies {:crew :yopp}}})))

    (it "falls back to the operator's default crew, never main (isaac-zule)"
      (should= "yopp" (#'sut/crew-id {:gmail/triage {:model "echo"} :defaults {:frequencies {:crew :yopp}}}))
      (should= "yopp" (#'sut/crew-id {:gmail/triage {:model "echo"} :defaults {:frequencies {:crew "yopp"}}}))))

  (describe "apply?"

    (it "is true when gmail/triage.apply is true"
      (should (sut/apply? {:gmail/triage {:apply true}})))

    (it "defaults to false"
      (should-not (sut/apply? {:gmail/triage {}}))
      (should-not (sut/apply? {}))))

  (describe "choices"

    (it "returns the configured choices as strings"
      (should= ["team" "ignore"] (sut/choices {:gmail/triage {:choices ["team" "ignore"]}})))

    (it "defaults to empty"
      (should= [] (sut/choices {}))))

  (describe "default-verdict"

    (it "returns the configured default"
      (should= "vacation" (sut/default-verdict {:gmail/triage {:default "vacation"}})))

    (it "defaults to \"ignore\""
      (should= "ignore" (sut/default-verdict {}))))

  (describe "decide!"

    (it "deletes and recreates session gmail-triage, dispatches a reset, single-cycle, tool-less turn, and resolves the verdict from the last assistant message"
      (let [deleted    (atom nil)
            created    (atom nil)
            dispatched (atom nil)
            cfg        {:gmail/triage {:model "echo" :crew "main" :choices ["team" "newsletters" "ignore"]
                                       :default "ignore"}}
            message    {:id "m-1" :from "pat@example.com" :to "yopp@tonotop.com"
                        :subject "Quick question" :body "Are you free?"}]
        (with-redefs [store/registered-store (fn [] :fake-store)
                      store/delete-session!   (fn [_store name] (reset! deleted name))
                      api/create-session!     (fn [name opts] (reset! created [name opts]))
                      api/dispatch!           (fn [req] (reset! dispatched req))
                      store/get-transcript    (fn [_store _name]
                                                [{:type "session"}
                                                 {:type "message" :message {:role "user" :content "..."}}
                                                 {:type "message" :message {:role "assistant" :content "team"}}])]
          (let [verdict (sut/decide! cfg message)]
            (should= "team" verdict)
            (should= sut/session-key @deleted)
            (should= sut/session-key (first @created))
            (should= "main" (:crew (second @created)))
            (should= sut/session-key (:session-key @dispatched))
            (should= "main" (:crew @dispatched))
            (should= "echo" (:model-override @dispatched))
            (should= :reset (:context-mode-override @dispatched))
            (should= {:limit 1} (:cycle @dispatched))
            (should (re-find #"Quick question" (:input @dispatched)))
            (should (re-find #"Are you free\?" (:input @dispatched)))
            (should= {:deny :all} (get-in @dispatched [:config :crew "main" :tools]))))))

    (it "falls back to :default when the model's answer names no choice"
      (with-redefs [store/registered-store (fn [] :fake-store)
                    store/delete-session!   (fn [& _] nil)
                    api/create-session!     (fn [& _] nil)
                    api/dispatch!           (fn [& _] nil)
                    store/get-transcript    (fn [& _]
                                              [{:type "message" :message {:role "assistant" :content "vacation"}}])]
        (should= "ignore"
                (sut/decide! {:gmail/triage {:model "echo" :crew "main" :choices ["team"] :default "ignore"}}
                             {:id "m-1" :from "a@b.com" :subject "s" :body "b"}))))

    (it "resolves \"ignore\" as a valid verdict even though it is not in :choices"
      (with-redefs [store/registered-store (fn [] :fake-store)
                    store/delete-session!   (fn [& _] nil)
                    api/create-session!     (fn [& _] nil)
                    api/dispatch!           (fn [& _] nil)
                    store/get-transcript    (fn [& _]
                                              [{:type "message" :message {:role "assistant" :content "ignore"}}])]
        (should= "ignore"
                (sut/decide! {:gmail/triage {:model "echo" :crew "main" :choices ["team"] :default "newsletters"}}
                             {:id "m-1" :from "a@b.com" :subject "s" :body "b"}))))))
