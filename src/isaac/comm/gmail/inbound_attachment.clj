(ns isaac.comm.gmail.inbound-attachment
  "Persists inbound Gmail attachment data into the receiving session workspace."
  (:require
    [clojure.string :as str]
    [isaac.comm.gmail.api :as api]
    [isaac.foundation.fs :as fs]
    [isaac.foundation.logger :as log]))

(def MAX-BYTES (* 25 1024 1024))

(defn sanitize-filename [filename]
  (let [name (last (str/split (str filename) #"[/\\\\]+"))]
    (if (or (str/blank? name) (= name ".") (= name "..")) "attachment" name)))

(defn- target [cwd id filename] (str cwd "/attachments/" id "/" filename))
(defn- line [filename mime size id]
  (str "[attachment: " filename " (" (or mime "application/octet-stream") ", " size ") at attachments/" id "/" filename "]"))

(defn save-all! [cwd message]
  (mapv (fn [{:keys [filename mime-type attachment-id]}]
          (let [filename (sanitize-filename filename)]
            (try
              (let [bytes (api/attachment-get! (:id message) attachment-id)]
                (if (> (alength bytes) MAX-BYTES)
                  (str "[attachment: " filename " (too large, not saved)]")
                  (let [path (target cwd (:id message) filename)]
                    (fs/mkdirs (fs/instance) (fs/parent path))
                    (fs/write-bytes (fs/instance) path bytes)
                    (line filename mime-type (alength bytes) (:id message)))))
              (catch Exception e
                (log/warn :gmail.attachment/download-failed :attachment attachment-id :error (.getMessage e))
                (str "[attachment: " filename " (download failed)]")))))
        (:attachments message)))
