;; Regression for the JVM-compat scaffolding (src/nbb/jvm.cljs): System/exit
;; getenv getProperty setProperty nanoTime currentTimeMillis lineSeparator,
;; System/out System/err.
;;   node cli.js test-scripts/jvm_system_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (8ea820f): each case "THREW Unable to
;; resolve symbol: System/..." (e.g. kotoba-lang/social-publication's
;; jvm_runner.cljk `(System/exit ...)`, teian's `(System/getProperty
;; "java.io.tmpdir")` / `(System/nanoTime)`).
;; The exit case runs a child engine whose stdout is a PIPE: 20,000 lines then
;; (System/exit 3). Plain process.exit drops queued pipe writes on macOS
;; (10,880 of 20,000 lines arrived, measured 2026-09-24); the JVM's
;; System.out is synchronous, so all 20,000 lines and exit 3 are expected.
(ns jvm-system-test)

(def cp (js/require "child_process"))
(def cli (aget js/process.argv 1))

(def child
  (.spawnSync cp js/process.execPath
              #js [cli "-e" "(dotimes [i 20000] (println \"line\" i \"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\")) (System/exit 3) (println \"after exit\")"]
              #js {:encoding "utf8" :maxBuffer (* 64 1024 1024)}))

(def child-lines (count (re-seq #"(?m)^line " (.-stdout child))))

(def cases
  [["System/exit: status" (fn [] (.-status child)) 3]
   ["System/exit: every queued line reached the pipe" (fn [] child-lines) 20000]
   ["System/exit: nothing after it ran" (fn [] (boolean (re-find #"after exit" (.-stdout child)))) false]
   ["System/getenv k" (fn [] (load-string "(= (System/getenv \"HOME\") js/process.env.HOME)")) true]
   ["System/getenv of an unset name is nil" (fn [] (load-string "(System/getenv \"KBB_SURELY_UNSET_VAR_42\")")) nil]
   ["System/getenv (no args) is a map" (fn [] (load-string "(= (get (System/getenv) \"HOME\") js/process.env.HOME)")) true]
   ["user.dir is the working directory" (fn [] (load-string "(= (System/getProperty \"user.dir\") (js/process.cwd))")) true]
   ["java.io.tmpdir ends with / (as the JVM's on macOS)" (fn [] (load-string "(.endsWith (System/getProperty \"java.io.tmpdir\") \"/\")")) true]
   ["line.separator" (fn [] (load-string "[(System/getProperty \"line.separator\") (System/lineSeparator)]")) ["\n" "\n"]]
   ["unknown property: nil, default with 2 args" (fn [] (load-string "[(System/getProperty \"no.such\") (System/getProperty \"no.such\" \"d\")]")) [nil "d"]]
   ["setProperty returns the old value, getProperty sees the new" (fn [] (load-string "[(System/setProperty \"k.1\" \"v\") (System/getProperty \"k.1\")]")) [nil "v"]]
   ["nanoTime is monotonic and a number" (fn [] (load-string "(let [a (System/nanoTime) b (System/nanoTime)] (and (number? a) (<= a b)))")) true]
   ["currentTimeMillis is Date.now" (fn [] (load-string "(<= (- (js/Date.now) (System/currentTimeMillis)) 1000)")) true]
   ["java.lang.System fully qualified" (fn [] (load-string "(string? (java.lang.System/getProperty \"user.dir\"))")) true]
   ["System/out println prints nil as null" (fn [] (let [r (.spawnSync cp js/process.execPath #js [cli "-e" "(.println System/out nil)"] #js {:encoding "utf8"})] (.-stdout r))) "null\n"]])

(def results
  (for [[label f want] cases]
    (let [got (try (f) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(when (not= 3 (.-status child)) (println "child stderr:" (subs (str (.-stderr child)) 0 300)))
(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
