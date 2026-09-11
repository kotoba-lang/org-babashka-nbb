(ns cljk-loader-test
  (:require [cljs.test :refer [deftest is run-tests]]
            ["node:fs" :as fs] ["node:os" :as os]
            ["node:path" :as path] ["node:child_process" :as cp]))
(def host (path/resolve "cli.js"))
(defn fixture [files origins]
  (let [dir (fs/mkdtempSync (path/join (os/tmpdir) "nbb-cljk-test-"))]
    (doseq [[name content] files]
      (let [file (path/join dir name)]
        (fs/mkdirSync (path/dirname file) #js {:recursive true})
        (fs/writeFileSync file content)))
    (fs/writeFileSync (path/join dir "cljk-origin.edn")
                      (pr-str {:format :kotoba.cljk-origin/v1 :origins origins}))
    dir))
(defn invoke [roots dirs expr]
  (let [env (js/Object.assign #js {} js/process.env
                             #js {:NBB_CLJK_ROOTS (js/JSON.stringify (clj->js roots))})
        r (cp/spawnSync js/process.execPath
                        (clj->js (concat [host "--classpath" (.join (clj->js dirs) ":") "-e" expr]))
                        #js {:encoding "utf8" :env env :timeout 20000})]
    {:exit (.-status r) :text (str (.-stdout r) (.-stderr r))}))
(deftest transitive-and-reload
  (let [d (fixture {"a.cljk" "(ns a (:require [b :as b])) (def value b/value)"
                    "b.cljk" "(ns b (:require [c :as c])) (def value c/value)"
                    "c.cljk" "(ns c) (def value 42)"}
                   {"a.cljk" ".cljs" "b.cljk" ".cljc" "c.cljk" ".cljs"})
        r (invoke [d] [d] "(require '[a :as a]) (assert (= 42 a/value)) (println :transitive)")
        expr (str "(require '[c :as c] '[\"node:fs\" :as fs]) (fs/writeFileSync "
                  (pr-str (path/join d "c.cljk")) " \"(ns c) (def value 43)\")"
                  " (require 'c :reload) (assert (= 43 c/value)) (println :reloaded)")]
    (is (= 0 (:exit r)) (:text r))
    (is (= 0 (:exit (invoke [d] [d] expr))))))
(deftest platform-and-root-order
  (let [d (fixture {"p.cljs.cljk" "(ns p) (def value :cljs)"
                    "p.clj.cljk" "(ns p) (def value :clj)"
                    "p.cljk" "(ns p) (def value #?(:cljs :portable :clj :wrong))"}
                   {"p.cljs.cljk" ".cljs" "p.clj.cljk" ".clj" "p.cljk" ".cljc"})
        e (fixture {"p.cljk" "(ns p) (def value :second)"} {"p.cljk" ".cljs"})]
    (is (= 0 (:exit (invoke [d e] [d e] "(require '[p :as p]) (assert (= :cljs p/value))"))))
    (fs/unlinkSync (path/join d "p.cljs.cljk"))
    (fs/writeFileSync (path/join d "cljk-origin.edn") (pr-str {:format :kotoba.cljk-origin/v1 :origins {"p.cljk" ".cljc" "p.clj.cljk" ".clj"}}))
    (is (= 0 (:exit (invoke [d] [d] "(require '[p :as p]) (assert (= :portable p/value))"))))))
(deftest reject-invalid
  (doseq [[files origins reason]
          [[{"p.cljk" "(ns p)"} {} "invalid-origin"]
           [{"p.cljk" "(ns p)"} {"p.cljk" ".unknown"} "invalid-origin"]
           [{"p.cljk" "(ns p)" "p.cljs.cljk" "(ns p)"} {"p.cljk" ".cljs" "p.cljs.cljk" ".cljs"} "ambiguous-source"]
           [{"p.cljk" "(ns p)"} {"p.cljk" ".clj"} "target-incompatible"]
           [{"p.cljk" "(ns p)"} {"../outside.cljk" ".cljs"} "invalid-origin"]]]
    (let [d (fixture files origins) r (invoke [d] [d] "(require 'p)")]
      (is (not= 0 (:exit r)))
      (is (.includes (:text r) reason) (:text r)))))
(deftest duplicate-manifest-and-legacy
  (let [d (fixture {"p.cljk" "(ns p)"} {"p.cljk" ".cljs"})]
    (fs/writeFileSync (path/join d "cljk-origin.edn") "{:format :kotoba.cljk-origin/v1 :origins {\"p.cljk\" \".cljs\" \"p.cljk\" \".cljc\"}}")
    (is (.includes (:text (invoke [d] [d] "(require 'p)")) "invalid-manifest")))
  (let [d (fixture {"p.cljs" "(ns p) (def value 9)"} {})]
    (is (= 0 (:exit (invoke [] [d] "(require '[p :as p]) (assert (= 9 p/value))"))))))
(deftest manifest-reload-and-escaping-source
  (let [d (fixture {"p.cljk" "(ns p) (def value 1)"} {"p.cljk" ".cljs"})
        expr (str "(require 'p '[\"node:fs\" :as fs]) (fs/writeFileSync "
                  (pr-str (path/join d "cljk-origin.edn")) " "
                  (pr-str "{:format :kotoba.cljk-origin/v1 :origins {}}")
                  ") (require 'p :reload)")]
    (is (.includes (:text (invoke [d] [d] expr)) "invalid-origin")))
  (let [d (fixture {} {}) e (fixture {"p.cljk" "(ns p)"} {"p.cljk" ".cljs"})]
    (fs/symlinkSync (path/join e "p.cljk") (path/join d "p.cljk"))
    (fs/writeFileSync (path/join d "cljk-origin.edn") (pr-str {:format :kotoba.cljk-origin/v1 :origins {"p.cljk" ".cljs"}}))
    (is (.includes (:text (invoke [d] [d] "(require 'p)")) "invalid-manifest"))))
(deftest invalid-first-root-and-source-diagnostics
  (let [d (fixture {"p.cljk" "(ns p)"} {}) e (fixture {"p.cljk" "(ns p)"} {"p.cljk" ".cljs"})]
    (is (.includes (:text (invoke [d e] [d e] "(require 'p)")) "invalid-origin")))
  (let [d (fixture {"p.cljk" "(ns p) (throw (js/Error. \"canonical-source-error\"))"} {"p.cljk" ".cljs"})
        r (invoke [d] [d] "(require 'p)")]
    (is (not= 0 (:exit r)))
    (is (.includes (:text r) "p.cljk"))))
(deftest explicit-config-entrypoint
  (let [d (fixture {"p.cljk" "(ns p) (println :config-ok)"} {"p.cljk" ".cljs"})
        config (path/join d "nbb.edn")
        _ (fs/writeFileSync config (pr-str {:cljk-roots [d]}))
        r (cp/spawnSync js/process.execPath
                        #js [host "--config" config (path/join d "p.cljk")]
                        #js {:encoding "utf8" :timeout 20000})]
    (is (= 0 (.-status r)) (.-stderr r))
    (is (.includes (.-stdout r) ":config-ok"))))
(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (when (pos? (+ (:fail m) (:error m))) (set! (.-exitCode js/process) 1)))
(run-tests 'cljk-loader-test)
