;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/process.cljs):
;; clojure.java.shell and the synchronous core of babashka.process.
;;   node cli.js test-scripts/jvm_shell_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): each case "THREW Could not find
;; namespace: clojure.java.shell" (babashka.process) -- the first failure of
;; cloud-itonami-assoc-6201-usa-gtia and com-etzhayyim-hirameki in the
;; 120-repo sample after round 1.
;; Expected values are Clojure 1.12 answers for clojure.java.shell (oracle
;; 2026-09-24): :env replaces the environment, :out-enc :bytes gives byte[],
;; a missing program throws IOException with the JVM's message.
(ns jvm-shell-test)

(try (load-string "(require '[clojure.java.shell :as sh] '[babashka.process :as p])") (catch :default e (println "require:" (ex-message e))))

(def cases
  [["sh: exit / out / err" "(sh/sh \"sh\" \"-c\" \"echo hi; echo err >&2; exit 3\")" {:exit 3 :out "hi\n" :err "err\n"}]
   ["sh :in" "(sh/sh \"cat\" :in \"piped\")" {:exit 0 :out "piped" :err ""}]
   ["sh :in bytes" "(:out (sh/sh \"cat\" :in (byte-array [104 105])))" "hi"]
   ["sh :dir" "(:out (sh/sh \"pwd\" :dir \"/\"))" "/\n"]
   ["sh :env replaces the environment" "(:out (sh/sh \"sh\" \"-c\" \"echo $FOO-$HOME\" :env {\"FOO\" \"bar\"}))" "bar-\n"]
   ["sh :out-enc :bytes" "(vec (:out (sh/sh \"printf\" \"\\\\351\" :out-enc :bytes)))" [-23]]
   ["sh :out-enc ISO-8859-1" "(:out (sh/sh \"printf\" \"\\\\303\\\\251\" :out-enc \"ISO-8859-1\"))" "Ã©"]
   ["with-sh-dir" "(:out (sh/with-sh-dir \"/\" (sh/sh \"pwd\")))" "/\n"]
   ["missing program: IOException with the JVM message" "(try (sh/sh \"no-such-program-xyz\") (catch java.io.IOException e (ex-message e)))" "Cannot run program \"no-such-program-xyz\": error=2, No such file or directory"]
   ["babashka.process/sh captures strings" "(select-keys (p/sh \"sh -c 'echo out; echo err >&2; exit 2'\") [:exit :out :err])" {:exit 2 :out "out\n" :err "err\n"}]
   ["babashka.process/process + deref, :out :string" "(:out @(p/process {:out :string} \"echo\" \"a b\"))" "a b\n"]
   ["babashka.process/process default :out slurps" "(slurp (:out (p/process \"echo hi\")))" "hi\n"]
   ["babashka.process/shell throws on non-zero" "(try (p/shell {:out :string :err :string} \"sh -c 'exit 4'\") :no (catch :default e (:exit (ex-data e))))" 4]
   ["babashka.process/shell :continue" "(:exit (p/shell {:continue true :out :string} \"sh -c 'exit 4'\"))" 4]
   ["babashka.process :extra-env keeps the rest" "(:out (p/sh {:extra-env {\"FOO\" \"x\"}} \"sh -c 'echo $FOO-${PATH:+set}'\"))" "x-set\n"]
   ["tokenize" "(p/tokenize \"a 'b c' \\\"d e\\\" f\\\\ g\")" ["a" "b c" "d e" "f g"]]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
