;; Regression for KBB_CLASSPATH_TAIL (src/nbb/api.cljs initialize).
;;   node cli.js test-scripts/classpath_tail_test.cljs
;; exit 0 and "OK 6/6" = present; exit 1 names each case that failed.
;; Runs the engine as a child so each case sees a fresh classpath. Measured on
;; the build before it (ddaadab): the tail is ignored, so "tail-only ns
;; resolves" fails with "Could not find namespace: tail-only.core".
(ns classpath-tail-test)

(def fs (js/require "fs"))
(def path (js/require "path"))
(def os (js/require "os"))
(def cp (js/require "child_process"))

(def cli (.resolve path "cli.js")) ;; run from the repository root

(def tmp (.mkdtempSync fs (.join path (.tmpdir os) "nbb-tail-")))
(defn- write! [rel content]
  (let [f (.join path tmp rel)]
    (.mkdirSync fs (.dirname path f) #js {:recursive true})
    (.writeFileSync fs f content)))

(write! "head/tail_probe/core.cljk" "(ns tail-probe.core)\n(def v :head)\n")
(write! "tail/tail_probe/core.cljk" "(ns tail-probe.core)\n(def v :tail)\n")
(write! "tail/tail_only/core.cljk" "(ns tail-only.core)\n(def v :tail-only)\n")
(write! "cwd/tail_probe/core.cljk" "(ns tail-probe.core)\n(def v :cwd)\n")

(defn- run [env & args]
  (let [e (js/Object.assign #js {} (.-env js/process) (clj->js env))
        r (.spawnSync cp "node" (clj->js (cons cli args))
                      #js {:cwd (.join path tmp "cwd") :encoding "utf8" :env e})]
    {:exit (.-status r) :out (str (.-stdout r)) :err (str (.-stderr r))}))

(def tail (.join path tmp "tail"))
(def head (.join path tmp "head"))

(def r-only (run {"KBB_CLASSPATH_TAIL" tail} "-e" "(require 'tail-only.core) (prn tail-only.core/v)"))
(def r-none (run {"KBB_CLASSPATH_TAIL" ""} "-e" "(require 'tail-only.core) (prn tail-only.core/v)"))
(def r-head (run {"KBB_CLASSPATH_TAIL" tail} "--classpath" head "-e" "(require 'tail-probe.core) (prn tail-probe.core/v)"))
(def r-cwd (run {"KBB_CLASSPATH_TAIL" tail} "-e" "(require 'tail-probe.core) (prn tail-probe.core/v)"))
(def r-last (run {"KBB_CLASSPATH_TAIL" tail} "--classpath" head "-e" "(println (nbb.classpath/get-classpath))"))
(def r-env (run {"KBB_CLASSPATH_TAIL" tail} "-e" "(prn (.-KBB_CLASSPATH_TAIL (.-env js/process)))"))

(def results
  [["tail-only ns resolves" (= ":tail-only" (.trim (:out r-only)))]
   ["boundary: empty tail adds nothing (Could not find namespace)"
    (and (= 1 (:exit r-none)) (boolean (re-find #"Could not find namespace: tail-only.core" (:err r-none))))]
   ["--classpath wins over the tail" (= ":head" (.trim (:out r-head)))]
   ["the cwd default wins over the tail" (= ":cwd" (.trim (:out r-cwd)))]
   ["the tail is the LAST entry" (.endsWith (.trim (:out r-last)) (str ":" tail))]
   ["consumed: the script (and what it spawns) does not inherit it" (= "nil" (.trim (:out r-env)))]])

(doseq [[label ok] results :when (not (true? ok))]
  (println "FAIL" label))
(when-not (every? (comp true? second) results)
  (prn {:only r-only :none r-none :head r-head :cwd r-cwd :last r-last :env r-env}))

(.rmSync fs tmp #js {:recursive true :force true})

(let [n (count (filter (comp true? second) results))]
  (println (str (if (= n (count results)) "OK " "FAILED ") n "/" (count results)))
  (when (< n (count results)) (js/process.exit 1)))
