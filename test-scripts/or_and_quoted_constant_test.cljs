;; Regression for the sci analyzer overlay (src/sci/impl/analyzer.cljc):
;; `or` / `and` must not re-analyze a quoted constant.
;;   node cli.js test-scripts/or_and_quoted_constant_test.cljs
;; exit 0 and "OK 6/6" = fixed; exit 1 names each case that failed. Measured
;; before the overlay: every case failed with "Unable to resolve symbol: <sym>".
(ns or-and-quoted-constant-test)

(def cases
  [["(or nil 'x)" 'x]
   ["(let [in nil] (or in '[$]))" '[$]]
   ["(or false nil '{a b})" '{a b}]
   ["(and true 'x 'y)" 'y]
   ["(and 1 2 3 4 5 6 '(q r))" '(q r)]
   ["(or nil nil nil nil nil nil 'z)" 'z]])

(def results
  (for [[src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:src src :ok (= want got) :got got})))

(doseq [{:keys [src ok got]} results :when (not ok)]
  (println "FAIL" src "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
