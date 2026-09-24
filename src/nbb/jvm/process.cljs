(ns nbb.jvm.process
  "JVM-compatibility SCAFFOLDING, round 2: clojure.java.shell and the
  synchronous core of babashka.process over node:child_process spawnSync
  (see nbb.jvm for why this exists; it is not a target for new code).

  clojure.java.shell/sh: args then :in (string or bytes) :in-enc :out-enc
  (:bytes -> byte[]) :env (replaces the environment, as on the JVM) :dir,
  *sh-dir* / *sh-env* and with-sh-dir / with-sh-env; answers {:exit :out
  :err}; a program that cannot start throws IOException \"Cannot run program
  \\\"x\\\": error=2, No such file or directory\" as on the JVM.

  babashka.process: process (a single string command is tokenized), shell
  (inherits stdio and throws on non-zero exit unless :continue), sh
  (captures :out/:err as strings), check, tokenize, with :dir :env
  :extra-env :in :out (:string :inherit :bytes, default a stream you can
  slurp) :err (:string :inherit :out) :continue. Deliberately synchronous:
  the child runs to completion before process returns, so @proc / (:exit
  proc) / (check proc) all see it finished. What a live process allows
  (streaming stdin while it runs, destroy, pipelines through :in of another
  process's stream) is NOT here."
  (:require ["node:child_process" :as cp]
            ["node:os" :as os]
            [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]))

(defn- env-obj [m inherit?]
  (let [o (if inherit? (js/Object.assign #js {} js/process.env) #js {})]
    (doseq [[k v] m] (gobj/set o (if (keyword? k) (name k) (str k)) (str v)))
    o))

(defn- spawn [cmd args ^js opts]
  (let [r (cp/spawnSync cmd (into-array args) opts)]
    (when-let [^js e (.-error r)]
      (if (= "ENOENT" (.-code e))
        (throw (jvm/IOException. (str "Cannot run program \"" cmd "\""
                                      (when-let [d (.-cwd opts)] (str " (in directory \"" d "\")"))
                                      ": error=2, No such file or directory")))
        (throw (jvm/IOException. (str "Cannot run program \"" cmd "\": " (.-message e))))))
    r))

(defn- in-bytes [in enc]
  (cond (nil? in) nil
        (string? in) (js/Buffer.from (jb/u8 (jb/encode in (if enc (jb/charset-for-name enc) jb/UTF_8))))
        :else (js/Buffer.from (jb/u8 in))))

(defn- exit-code
  "The JVM's Process.exitValue: the status, or 128 + signal number."
  [^js r]
  (if (some? (.-status r)) (.-status r)
      (+ 128 (or (some->> (.-signal r) (gobj/get (.. os -constants -signals))) 0))))

;; ---------------------------------------------------------------------------
;; clojure.java.shell
;; ---------------------------------------------------------------------------

(defn sh*
  "clojure.java.shell/sh, given the current *sh-dir* / *sh-env*."
  [sh-dir sh-env & args]
  (let [[cmd opts] (split-with string? args)
        opts (apply hash-map opts)
        dir (or (:dir opts) sh-dir)
        env (or (:env opts) sh-env)
        o #js {"input" (in-bytes (:in opts) (:in-enc opts))
               "maxBuffer" (* 1024 1024 1024)}]
    (when dir (gobj/set o "cwd" (str dir)))
    (when env (gobj/set o "env" (env-obj env false)))
    (let [r (spawn (first cmd) (rest cmd) o)
          out-enc (:out-enc opts)
          out (if (= :bytes out-enc)
                (jb/->bytes (.-stdout r))
                (jb/decode (.-stdout r) (if out-enc (jb/charset-for-name out-enc) jb/UTF_8)))]
      {:exit (exit-code r)
       :out out
       :err (jb/decode (.-stderr r) jb/UTF_8)})))

;; ---------------------------------------------------------------------------
;; babashka.process
;; ---------------------------------------------------------------------------

(defn tokenize
  "babashka.process/tokenize: split on whitespace, honouring '...' and \"...\"
  and backslash escapes outside single quotes."
  [s]
  (loop [cs (seq s) cur nil quote nil out []]
    (if-let [c (first cs)]
      (cond
        (and (= c "\\") (not= quote "'") (second cs)) (recur (nnext cs) (str cur (second cs)) quote out)
        (and quote (= c quote)) (recur (next cs) (or cur "") nil out)
        (and (not quote) (contains? #{"'" "\""} c)) (recur (next cs) (or cur "") c out)
        (and (not quote) (re-matches #"\s" c)) (recur (next cs) nil nil (if cur (conj out cur) out))
        :else (recur (next cs) (str cur c) quote out))
      (if cur (conj out cur) out))))

(defn- parse-args [args]
  (let [[opts args] (if (map? (first args)) [(first args) (rest args)] [nil args])
        [args opts] (if (and (nil? opts) (map? (last args))) [(butlast args) (last args)] [args opts])
        args (mapcat #(if (sequential? %) (map str %) [%]) args)
        args (if (= 1 (count args)) (tokenize (first args)) (map str args))]
    [(vec args) (or opts {})]))

(defn- run [cmd opts defaults]
  (let [opts (merge defaults opts)
        out (:out opts) err (:err opts) in (:in opts)
        o #js {"maxBuffer" (* 1024 1024 1024)}
        stdio #js [(if (= :inherit in) "inherit" "pipe")
                   (if (= :inherit out) "inherit" "pipe")
                   (cond (= :inherit err) "inherit" :else "pipe")]]
    (when (and in (not= :inherit in))
      (gobj/set o "input" (if (string? in) in (js/Buffer.from (jb/u8 (if (instance? jb/ByteArrayInputStream in) (.readAllBytes ^js in) in))))))
    (gobj/set o "stdio" stdio)
    (when-let [d (:dir opts)] (gobj/set o "cwd" (str d)))
    (cond (:env opts) (gobj/set o "env" (env-obj (merge (:env opts) (:extra-env opts)) false))
          (:extra-env opts) (gobj/set o "env" (env-obj (:extra-env opts) true)))
    (let [^js r (spawn (first cmd) (rest cmd) o)
          stdout (.-stdout r) stderr (.-stderr r)
          err-as-out? (= :out err)
          stdout (if (and err-as-out? stdout stderr) (js/Buffer.concat #js [stdout stderr]) stdout)
          as (fn [kind ^js buf]
               (cond (= :inherit kind) nil
                     (= :string kind) (if buf (.toString buf "utf8") "")
                     (= :bytes kind) (jb/->bytes (or buf (js/Uint8Array. 0)))
                     :else (jb/new-bais (or buf (js/Uint8Array. 0)))))
          exit (exit-code r)]
      {:cmd cmd
       :exit exit
       :out (as out stdout)
       :err (if err-as-out? nil (as err stderr))
       :proc nil
       :prev nil})))

(deftype Process [m]
  IDeref
  (-deref [_] m)
  ILookup
  (-lookup [_ k] (get m k))
  (-lookup [_ k nf] (get m k nf))
  IMap
  (-dissoc [_ k] (dissoc m k))
  IAssociative
  (-assoc [_ k v] (assoc m k v))
  (-contains-key? [_ k] (contains? m k))
  ISeqable
  (-seq [_] (seq m))
  IPrintWithWriter
  (-pr-writer [_ w opts] (-pr-writer m w opts)))

(defn check
  "babashka.process/check: throw when the (finished) process exited non-zero."
  [proc]
  (let [m (if (instance? Process proc) (.-m ^js proc) proc)]
    (if (and (not (zero? (:exit m))) (not (:continue m)))
      (throw (ex-info (let [e (:err m)] (if (string? e) e (str "failed with exit code " (:exit m))))
                      (assoc m :type :babashka.process/error)))
      proc)))

(defn process [& args]
  (let [[cmd opts] (parse-args args)]
    (Process. (assoc (run cmd opts {}) :continue (:continue opts)))))

(defn shell [& args]
  (let [[cmd opts] (parse-args args)
        p (Process. (assoc (run cmd opts {:in :inherit :out :inherit :err :inherit}) :continue (:continue opts)))]
    (check p)))

(defn sh-bb [& args]
  (let [[cmd opts] (parse-args args)]
    (run cmd opts {:out :string :err :string})))
