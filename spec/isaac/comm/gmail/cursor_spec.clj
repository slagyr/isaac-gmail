(ns isaac.comm.gmail.cursor-spec
  (:require
    [isaac.comm.gmail.cursor :as sut]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]
    [speclj.core :refer :all]))

(def root "/test/gmail")

(describe "gmail history cursor"

  (around [it]
    (nexus/-with-nexus {:root root :fs (fs/mem-fs)}
      (it)))

  (it "starts empty"
    (should-be-nil (sut/load-cursor root)))

  (it "persists and reloads the last processed history id"
    (sut/save-cursor! root "1042")
    (should= "1042" (sut/load-cursor root)))

  (it "seeds from a watch response historyId"
    (sut/seed-from-watch! root {:historyId "900"})
    (should= "900" (sut/load-cursor root)))
  )
