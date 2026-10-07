;; Regression for the JVM-compat scaffolding (src/nbb/jvm.cljs): clojure.core
;; slurp / spit, synchronous, relative to the working directory.
;;   node cli.js test-scripts/jvm_slurp_spit_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (8ea820f): every case "THREW Unable to
;; resolve symbol: slurp" (or spit) -- the first failure of 16 of 76 red
;; JVM-era suites sampled on 2026-09-24 (e.g. `(edn/read-string (slurp
;; "data/culture-tx.edn"))` in the cloud-itonami iso3166/municipality repos).
(ns jvm-slurp-spit-test)

(def fs (js/require "fs"))
(def dir (.mkdtempSync fs (str (.tmpdir (js/require "os")) "/jvm-slurp-")))
(.writeFileSync fs (str dir "/in.edn") "{:a 1}\n")
(.mkdirSync fs (str dir "/sub"))
(set! (.-dir js/globalThis) dir)

(def cases
  [["read a file by path" "(slurp (str js/globalThis.dir \"/in.edn\"))" "{:a 1}\n"]
   ["read relative to the working directory"
    "(do (js/process.chdir js/globalThis.dir) (slurp \"in.edn\"))" "{:a 1}\n"]
   ["read a java.io.File" "(slurp (java.io.File. js/globalThis.dir \"in.edn\"))" "{:a 1}\n"]
   ["read a file: URL string" "(slurp (str \"file://\" js/globalThis.dir \"/in.edn\"))" "{:a 1}\n"]
   ["spit then slurp round-trips (str content)" "(do (spit \"out.txt\" {:b 2}) (slurp \"out.txt\"))" "{:b 2}"]
   ["spit :append appends" "(do (spit \"out.txt\" \"x\") (spit \"out.txt\" \"y\" :append true) (slurp \"out.txt\"))" "xy"]
   ["spit returns nil" "(spit \"out2.txt\" \"z\")" nil]
   ["slurp :encoding ISO-8859-1" "(do (.writeFileSync (js/require \"fs\") \"l1.txt\" (js/Buffer.from #js [0xe9])) (slurp \"l1.txt\" :encoding \"ISO-8859-1\"))" "é"]
   ;; the JVM's FileNotFoundException, with its message
   ["missing file: FileNotFoundException + JVM message"
    "(try (slurp \"nope.txt\") (catch java.io.FileNotFoundException e (ex-message e)))"
    "nope.txt (No such file or directory)"]
   ["missing file is an IOException too" "(try (slurp \"nope.txt\") (catch java.io.IOException _ :io))" :io]
   ["directory: FileNotFoundException (Is a directory)"
    "(try (slurp \"sub\") (catch java.io.FileNotFoundException e (ex-message e)))" "sub (Is a directory)"]
   ["spit into a missing directory: FileNotFoundException"
    "(try (spit \"no/such/dir.txt\" 1) (catch java.io.FileNotFoundException e (ex-message e)))"
    "no/such/dir.txt (No such file or directory)"]
   ["a non-file URL is refused by name, not fetched"
    "(try (slurp \"https://example.invalid/x\") (catch UnsupportedOperationException e (boolean (re-find #\"non-file URL\" (ex-message e)))))" true]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
