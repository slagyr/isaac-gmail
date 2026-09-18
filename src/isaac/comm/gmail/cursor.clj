(ns isaac.comm.gmail.cursor
  "Durable Gmail history cursor at <root>/google/gmail-cursor.edn."
  (:require
    [clojure.edn :as edn]
    [isaac.fs :as fs]
    [isaac.nexus :as nexus]))

(defn- runtime-fs []
  (or (fs/instance) (nexus/get :fs) (fs/real-fs)))

(defn- cursor-path [root]
  (str root "/google/gmail-cursor.edn"))

(defn load-cursor [root]
  (let [fs*  (runtime-fs)
        path (cursor-path root)]
    (when (fs/exists? fs* path)
      (let [data (edn/read-string (fs/slurp fs* path))]
        (or (:history-id data) (:historyId data))))))

(defn save-cursor! [root history-id]
  (when (and root history-id)
    (let [fs*  (runtime-fs)
          path (cursor-path root)]
      (fs/mkdirs fs* (fs/parent path))
      (fs/spit fs* path (pr-str {:history-id (str history-id)}))))
  history-id)

(defn seed-from-watch! [root watch]
  (when-let [hid (or (:historyId watch) (:history-id watch))]
    (when-not (load-cursor root)
      (save-cursor! root hid))))
