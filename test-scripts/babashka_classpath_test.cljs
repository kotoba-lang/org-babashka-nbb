;; Regression for `babashka.classpath` on the engine (src/nbb/core.cljs).
;;   node cli.js test-scripts/babashka_classpath_test.cljs
;; exit 0 and "OK 4/4" = present; exit 1 names each case that failed.
;; Measured on the build before it (ddaadab): the first require throws
;; "Could not find namespace: babashka.classpath" and nothing below runs --
;; the shape of every bb-era `run_tests.cljk` that does
;;   (cp/add-classpath (str root "/src"))
;; (e.g. cloud-itonami/igata).
(ns babashka-classpath-test
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [nbb.classpath :as ncp]))

(def dir (str (fs/create-temp-dir)))
(fs/create-dirs (str dir "/src/bcp_probe"))
(.writeFileSync (js/require "fs") (str dir "/src/bcp_probe/core.cljk") "(ns bcp-probe.core)\n(def v :added-through-babashka-classpath)\n")

;; the run_tests.cljk idiom: root from *file*, then add-classpath
(let [root (fs/parent (fs/absolutize (str dir "/run_tests.cljk")))]
  (cp/add-classpath (str root "/src")))

(require 'bcp-probe.core)

(def absent
  (nbb.core/await
   (-> (nbb.core/load-string "(require 'bcp-probe.absent)")
       (.then (fn [_] "loaded?!"))
       (.catch (fn [e] (ex-message e))))))

(def results
  [["require of a ns under the added entry"
    (= :added-through-babashka-classpath @(resolve 'bcp-probe.core/v))]
   ["get-classpath names the added entry"
    (boolean (some #{(str dir "/src")} (cp/split-classpath (cp/get-classpath))))]
   ["babashka.classpath and nbb.classpath are one classpath"
    (= (cp/get-classpath) (ncp/get-classpath))]
   ["boundary: a ns under a dir NOT added still fails by name"
    (boolean (re-find #"Could not find namespace: bcp-probe.absent" (str absent)))]])

(doseq [[label ok] results :when (not (true? ok))]
  (println "FAIL" label "=>" (pr-str ok)))

(fs/delete-tree dir)

(let [n (count (filter (comp true? second) results))]
  (println (str (if (= n (count results)) "OK " "FAILED ") n "/" (count results)))
  (when (< n (count results)) (js/process.exit 1)))
