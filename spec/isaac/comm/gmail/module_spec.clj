(ns isaac.comm.gmail.module-spec
  (:require
    [clojure.edn :as edn]
    [isaac.module.protocol]
    [isaac.comm.gmail.module :as sut]
    [speclj.core :refer [describe it should should=]]))

(def manifest
  (edn/read-string (slurp "resources/isaac-manifest.edn")))

(describe "isaac.comm.gmail.module"

  (it "returns a module"
    (should (satisfies? isaac.module.protocol/Module (sut/create-module))))

  (it "declares its module id"
    (should= :isaac.comm.gmail (:id manifest))))
