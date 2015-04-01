(ns porklock.commands
  (:use [porklock.pathing]
        [porklock.system]
        [porklock.config]
        [porklock.shell-interop]
        [porklock.fileops :only [absify]]
        [clojure.pprint :only [pprint]]
        [slingshot.slingshot :only [try+]])
  (:require [clj-jargon.init :as jg]
            [clj-jargon.item-info :as info]
            [clj-jargon.item-ops :as ops]
            [clj-jargon.metadata :as meta]
            [clj-jargon.permissions :as perms]
            [clojure.string :as string]
            [clojure.java.io :as io]
            [clojure-commons.file-utils :as ft]))

(defn init-jargon
  [cfg-path]
  (load-config-from-file cfg-path)
  (jg/init (irods-host)
           (irods-port)
           (irods-user)
           (irods-pass)
           (irods-home)
           (irods-zone)
           (irods-resc)))

(defn fix-meta
  [m]
  (cond
    (= (count m) 3) m
    (= (count m) 2) (conj m "default-unit")
    (= (count m) 1) (concat m ["default-value" "default-unit"])
    :else           []))

(defn avu?
  [cm path attr value]
  (filter #(= value (:value %)) (meta/get-attribute cm path attr)))

(def porkprint (partial println "[porklock] "))

(defn apply-metadata
  [cm destination meta]
  (let [tuples (map fix-meta meta)
        dest   (ft/rm-last-slash destination)]
    (porkprint "Metadata tuples for " destination " are  " tuples)
    (when (pos? (count tuples))
      (doseq [tuple tuples]
        (porkprint "Size of tuple " tuple " is " (count tuple))
        (when (= (count tuple) 3)
          (porkprint "Might be adding metadata to " dest " " tuple)
          (porkprint "AVU? " dest (avu? cm dest (first tuple) (second tuple)))
          (when (empty? (avu? cm dest (first tuple) (second tuple)))
            (porkprint "Adding metadata " (first tuple) " " (second tuple) " " dest)
            (apply (partial meta/add-metadata cm dest) tuple)))))))

(defn irods-env-contents
  [options]
  (str
    "irodsHost "     (irods-host) "\n"
    "irodsPort "     (irods-port) "\n"
    "irodsUserName " (irods-user) "\n"
    "irodsZone "     (irods-zone) "\n"
    "irodsHome "     (irods-home) "\n"))

(defn make-irods-env
  [env]
  (shell-out [(iinit-path) :in (irods-pass) :env env] :skip-err true))

(defn icommands-env
  "Constructs an environment variable map for the icommands."
  [options]
  (let [env {"irodsAuthFileName" (irods-auth-filepath)
             "irodsEnvFile"      (irods-env-filepath)}]
    (spit (irods-env-filepath) (irods-env-contents options))
    (make-irods-env env)
    (merge env {"clientUserName" (:user options)})))

(defn user-home-dir
  [cm username]
  (ft/path-join "/" (:zone cm) "home" username))

(defn home-folder?
  [zone full-path]
  (let [parent (ft/dirname full-path)]
    (= parent (ft/path-join "/" zone "home"))))

(defn halting-folders
  [cm username]
  (set (user-home-dir cm username)
    (ft/path-join "/" (:zone cm) "home" "shared")))

(defn halt?
  [cm username path-to-test]
  (or (contains? (halting-folders cm username) path-to-test)
      (home-folder? (:zone cm) path-to-test)))

(defn set-parent-owner
  [cm username dir-dest]
  (loop [p (ft/dirname dir-dest)]
    (when-not (halt? cm username p)
      (if-not (perms/owns? cm username p )
        (perms/set-owner cm p username))
      (recur (ft/dirname p)))))

(defn iput-status
  "Callback function for the overallStatus function for a TransferCallbackListener."
  [transfer-status]
  (let [exc (.getTransferException transfer-status)]
    (if-not (nil? exc)
      (throw (Exception. (str exc)))))
  nil)

(defn iput-status-cb
  "Callback function for the statusCallback function of a TransferCallbackListener."
  [transfer-status]
  (porkprint "-------")
  (porkprint "iput status update:")
  (porkprint "\ttransfer state: " (.getTransferState transfer-status))
  (porkprint "\ttransfer type: " (.getTransferType transfer-status))
  (porkprint "\tsource path: " (.getSourceFileAbsolutePath transfer-status))
  (porkprint "\tdest path: " (.getTargetFileAbsolutePath transfer-status))
  (porkprint "\tfile size: " (.getTotalSize transfer-status))
  (porkprint "\tbytes transferred: " (.getBytesTransfered transfer-status))
  (porkprint "\tfiles to transfer: " (.getTotalFilesToTransfer transfer-status))
  (porkprint "\tfiles skipped: " (.getTotalFilesSkippedSoFar transfer-status))
  (porkprint "\tfiles transferred: " (.getTotalFilesTransferredSoFar transfer-status))
  (porkprint "\ttransfer host: " (.getTransferHost transfer-status))
  (porkprint "\ttransfer zone: " (.getTransferZone transfer-status))
  (porkprint "\ttransfer resource: " (.getTargetResource transfer-status))
  (porkprint "-------")
  (let [exc (.getTransferException transfer-status)]
    (if-not (nil? exc)
      (do (porkprint "got an exception in iput: " exc)
        ops/skip
      ops/continue))))

(defn iput-force-cb
  "Callback function for the transferAsksWhetherToForceOperation function of a
   TransferCallbackListener."
   [abs-path collection?]
   (porkprint "force iput of " abs-path ". collection?: " collection?)
   ops/yes-for-all)

(def tcl (ops/transfer-callback-listener iput-status iput-status-cb iput-force-cb))

(defn iput-command
  "Runs the iput icommand, tranferring files from the --source
   to the remote --destination."
  [options]
  (let [source-dir      (ft/abs-path (:source options))
        dest-dir        (:destination options)
        irods-cfg       (init-jargon (:config options))
        ic-env          (icommands-env options)
        transfer-files  (files-to-transfer options)
        metadata        (:meta options)
        skip-parent?    (:skip-parent-meta options)
        dest-files      (relative-dest-paths transfer-files source-dir dest-dir)
        error?          (atom false)]
    (jg/with-jargon irods-cfg [cm]
      (when-not (info/exists? cm (ft/dirname dest-dir))
        (porkprint (ft/dirname dest-dir) "does not exist.")
        (System/exit 1))

      (when (and (not (perms/is-writeable? cm (:user options) (ft/dirname dest-dir)))
                 (not= (user-home-dir cm (:user options))
                       (ft/rm-last-slash dest-dir)))
        (porkprint (ft/dirname dest-dir) "is not writeable.")
        (System/exit 1))

      (when-not (info/exists? cm dest-dir)
        (porkprint "Path " dest-dir " does not exist. Creating it.")
        (ops/mkdirs cm dest-dir))

      (when-not (perms/owns? cm (:user options) dest-dir)
        (porkprint "Setting the owner of " dest-dir " to " (:user options))
        (perms/set-owner cm dest-dir (:user options)))

      (doseq [[src dest]  (seq dest-files)]
        (let [dir-dest (ft/dirname dest)]
          (if-not (or (.isFile (io/file src))
                      (.isDirectory (io/file src)))
            (porkprint "Path " src " is neither a file nor a directory.")
            (do
              ;;; It's possible that the destination directory doesn't
              ;;; exist yet in iRODS, so create it if it's not there.
              (porkprint "Creating all directories in iRODS down to " dir-dest)
              (when-not (info/exists? cm dir-dest)
                (ops/mkdirs cm dir-dest))

              ;;; The destination directory needs to be tagged with AVUs
              ;;; for the App and Execution.
              (porkprint "Applying metadata to" dir-dest)
              (apply-metadata cm dir-dest metadata)

              ;;; Since we run as a proxy account, the destination directory
              ;;; needs to have the owner set to the user that ran the app.
              (when-not (perms/owns? cm (:user options) dir-dest)
                (porkprint "Setting owner of " dir-dest " to " (:user options))
                (perms/set-owner cm dir-dest (:user options)))

              (try
                (ops/iput cm src dest tcl)
               (catch Exception err
                 (porkprint "iput failed: " err)
                 (reset! error? true)))

              ;;; Apply the App and Execution metadata to the newly uploaded
              ;;; file/directory.
              (porkprint "Applying metadata to " dest)
              (apply-metadata cm dest metadata)))))

      (when-not skip-parent?
        (porkprint "Applying metadata to " dest-dir)
        (apply-metadata cm dest-dir metadata)
        (doseq [fileobj (file-seq (info/file cm dest-dir))]
          (let [filepath (.getAbsolutePath fileobj)
                dir?     (.isDirectory fileobj)]
            (perms/set-owner cm filepath (:user options))
            (apply-metadata cm filepath metadata))))

      ;;; Transfer files from the NFS mount point into the logs
      ;;; directory of the destination
      (if (and (System/getenv "SCRIPT_LOCATION") (not skip-parent?))
        (let [script-loc  (ft/dirname (ft/abs-path (System/getenv "SCRIPT_LOCATION")))
              dest        (ft/path-join dest-dir "logs")
              exclude-map (merge options {:source script-loc})
              exclusions  (set (exclude-files-from-dir exclude-map))]
          (porkprint "Exclusions:\n" exclusions)
          (doseq [fileobj (file-seq (clojure.java.io/file script-loc))]
            (let [src (.getAbsolutePath fileobj)
                  dest-path (ft/path-join dest (ft/basename src))]
              (try+
               (when-not (or (.isDirectory fileobj) (contains? exclusions src))
                 (shell-out [(iput-path) "-f" "-P" src dest :env ic-env])
                 (perms/set-owner cm dest-path (:user options))
                 (apply-metadata cm dest-path metadata))
               (catch [:error_code "ERR_BAD_EXIT_CODE"] err
                 (porkprint "Command exited with a non-zero status: " err)
                 (reset! error? true)))))))

      (if @error?
        (throw (Exception. "An error occurred tranferring files into iRODS. Please check the above logs for more information."))))))

(defn- iget-args
  [source destination env]
  (filter #(not (nil? %))
          [(iget-path)
           "--retries"
           "3"
           "-X"
           "irods.retries"
           "--lfrestart"
           "irods.lfretries"
           "-f"
           "-P"
           (if (.endsWith source "/")
             "-r")
           (ft/rm-last-slash source)
           (ft/add-trailing-slash destination)
           :env env]))

(defn apply-input-metadata
  [cm user fpath meta]
  (if-not (info/is-dir? cm fpath)
    (if (perms/owns? cm user fpath)
      (apply-metadata cm fpath meta))
    (doseq [f (file-seq (info/file cm fpath))]
      (let [abs-path (.getAbsolutePath f)]
        (if (perms/owns? cm user abs-path)
          (apply-metadata cm abs-path meta))))))

(defn iget-command
  "Runs the iget icommand, retrieving files from --source
   to the local --destination."
  [options]
  (let [source    (:source options)
        dest      (:destination options)
        irods-cfg (init-jargon (:config options))
        ic-env    (icommands-env options)
        srcdir    (ft/rm-last-slash source)
        args      (iget-args source dest ic-env)
        metadata  (:meta options)]
    (jg/with-jargon irods-cfg [cm]
      (apply-input-metadata cm (:user options) srcdir metadata)
      (ops/iget cm source dest tcl))))
