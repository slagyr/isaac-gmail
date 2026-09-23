(ns isaac.comm.gmail.module
  (:require
    [isaac.comm.gmail.pull :as pull]
    [isaac.module.protocol :as module]))

(defn- on-load
  "Boot: gmail/mode :pull schedules the history-walk tick on the shared
   scheduler; :push (the default) is a no-op here (the watch is registered
   through this module's :isaac.google/registration contribution instead)."
  [_]
  (pull/start!))

(defn- on-unload
  "Cancel the pull tick if this module ever scheduled it."
  [_]
  (pull/stop!))

(defn create-module []
  (module/module {:on-load on-load :on-unload on-unload}))
