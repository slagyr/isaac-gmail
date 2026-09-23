(ns isaac.comm.gmail.tenant
  "Which Google organization a Gmail comm speaks for.

   One Isaac can carry several Google organizations (isaac-1zkz). A comm names
   its own with `:google <tenant>`; it sends with that organization's token and
   its mailbox is watched against that organization's topic. A host with one
   organization names none and every comm speaks for it, exactly as before.
   Which organization a comm speaks for is isaac.google.tenants' answer; this
   namespace is only the Gmail side of it."
  (:require
    [isaac.config.loader :as loader]
    [isaac.google.tenants :as tenants]))

(def KIND :gmail)

(defn- live-config []
  (or (try (loader/snapshot "gmail tenant") (catch Exception _ nil)) {}))

(defn of-comm
  "The organization a live comm's slice speaks for, against the config the
   host is running."
  [slice]
  (tenants/of-comm (live-config) slice))
