;; Regression for the JVM-compat scaffolding round 3 (src/nbb/jvm/io.cljs):
;; java.io streams / readers / writers over files, clojure.java.io reader /
;; writer / input-stream / output-stream / copy, line-seq, slurp of a stream,
;; spit to a writer, edn/read over a PushbackReader.
;;   node cli.js test-scripts/jvm_io_streams_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (2b61d53): "import: Unable to resolve
;; classname: java.io.FileInputStream" (kotoba-lang/mesh's first failure,
;; through kotoba/launcher.clj's (:import [java.io ... FileInputStream ...]))
;; and "THREW Unable to resolve symbol: line-seq" / "... io/reader"; and
;; with-open itself: EVERY with-open died "Unable to resolve symbol:
;; sci.impl.namespaces/with-open" (the engine's own macro expansion).
;; Expected values are JDK 21 / Clojure 1.12 answers (oracle 2026-09-24).
;; Boundary cases: readLine on \n, \r\n, a lone \r and an empty line, read()
;; right after a \r\n line, skip past EOF on a FileInputStream, write(321)
;; keeps the low byte, an unflushed BufferedWriter / BufferedOutputStream
;; has written NOTHING to the file yet, "Stream Closed" (file stream) vs
;; "Stream closed" (reader), a directory is "(Is a directory)", a missing
;; parent directory is "(No such file or directory)", ISO-8859-1 writes '?'
;; for an unmappable char.
(ns jvm-io-streams-test
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]))

(def d (fs/mkdtempSync (path/join (os/tmpdir) "jvm-io-")))
(fs/writeFileSync (path/join d "a.txt") "one\ntwo\r\nthree\rfour\n\nlast")
(fs/writeFileSync (path/join d "u.txt") (str "h" (js/String.fromCharCode 0xe9) "llo " (js/String.fromCodePoint 0x1F600) "\n"))

(try (load-string "(require '[clojure.java.io :as io] '[clojure.edn :as edn])
                   (import '[java.io File FileInputStream FileOutputStream InputStreamReader OutputStreamWriter BufferedReader BufferedWriter StringReader StringWriter PushbackReader ByteArrayOutputStream])")
     (catch :default e (println "import:" (ex-message e))))
(load-string (str "(def d " (pr-str d) ")"))

(def cases
  [["line-seq over io/reader: \\n, \\r\\n, lone \\r, empty line, no final newline"
    "(with-open [r (io/reader (str d \"/a.txt\"))] (vec (line-seq r)))" ["one" "two" "three" "four" "" "last"]]
   ["BufferedReader/InputStreamReader/FileInputStream: readLine then read after \\r\\n"
    "(with-open [r (BufferedReader. (InputStreamReader. (FileInputStream. (str d \"/a.txt\")) \"UTF-8\"))] [(.readLine r) (.read r) (.readLine r) (.ready r)])"
    ["one" 116 "wo" true]]
   ["reader decodes UTF-8, readLine nil at EOF"
    "(with-open [r (io/reader (str d \"/u.txt\"))] [(.read r) (.read r) (= (.readLine r) (str \"llo \" (js/String.fromCodePoint 0x1F600))) (.readLine r)])"
    [104 233 true nil]]
   ["FileInputStream read / available / readAllBytes / EOF"
    "(with-open [in (FileInputStream. (str d \"/u.txt\"))] [(.read in) (.read in) (.read in) (.available in) (count (.readAllBytes in)) (.read in)])"
    [104 195 169 9 9 -1]]
   ["FileInputStream read(b) / read(b off len) / skip past EOF"
    "(with-open [in (FileInputStream. (File. d \"u.txt\"))] (let [b (byte-array 4)] [(.read in b) (vec b) (.read in b 1 2) (vec b) (.skip in 100) (.read in b)]))"
    [4 [104 -61 -87 108] 2 [104 108 111 108] 100 -1]]
   ["FileInputStream of a missing file / a directory"
    "(mapv #(try (FileInputStream. %) (catch java.io.FileNotFoundException e (ex-message e))) [(str d \"/missing.txt\") d])"
    [:missing :dir]]
   ["closed FileInputStream: IOException Stream Closed"
    "(let [in (FileInputStream. (str d \"/a.txt\"))] (.close in) (try (.read in) (catch java.io.IOException e (ex-message e))))" "Stream Closed"]
   ["FileOutputStream: write(int) keeps the low byte, write(b), write(b off len), append"
    "(let [f (str d \"/o.bin\")] (with-open [o (FileOutputStream. f)] (.write o 65) (.write o 321) (.write o (byte-array [66 67])) (.write o (byte-array [1 68 69 2]) 1 2)) (with-open [o (FileOutputStream. f true)] (.write o (.getBytes \"Z\"))) (slurp f))"
    "AABCDEZ"]
   ["FileOutputStream into a missing directory" "(try (FileOutputStream. (str d \"/nodir/x.txt\")) (catch java.io.FileNotFoundException e (ex-message e)))" :nodir]
   ["io/writer: write String / int / (s off len), newLine, append; :append true"
    "(let [f (str d \"/w.txt\")] (with-open [w (io/writer f)] (.write w \"a\") (.write w 98) (.newLine w) (.write w \"hello\" 1 3) (.append w \"!\")) (with-open [w (io/writer f :append true)] (.write w \"+\")) (slurp f))"
    "ab\nell!+"]
   ["an unflushed BufferedWriter has written nothing; flush writes"
    "(let [f (str d \"/w2.txt\") w (io/writer f)] (.write w \"unflushed\") [(slurp f) (do (.flush w) (slurp f)) (.close w)])" ["" "unflushed" nil]]
   ["an unclosed BufferedOutputStream has written nothing; close writes"
    "(let [f (str d \"/w3.txt\") o (io/output-stream f)] (.write o (.getBytes \"buffered\")) [(slurp f) (do (.close o) (slurp f))])" ["" "buffered"]]
   ["OutputStreamWriter ISO-8859-1: unmappable is '?'"
    "(let [f (str d \"/w4.txt\")] (with-open [w (OutputStreamWriter. (FileOutputStream. f) \"ISO-8859-1\")] (.write w (str (char 0xe9) (char 0x20ac)))) (vec (.readAllBytes (FileInputStream. f))))"
    [-23 63]]
   ["StringWriter / StringReader" "[(let [s (StringWriter.)] (.write s \"ab\") (.append s \"c\") (.write s 100) (str s)) (vec (line-seq (BufferedReader. (StringReader. \"x\\ny\\n\")))) (let [r (StringReader. \"ab\")] [(.read r) (.read r) (.read r)])]"
    ["abcd" ["x" "y"] [97 98 -1]]]
   ["io/copy file -> file, string -> file, stream -> ByteArrayOutputStream, bytes -> stream"
    "(let [src (str d \"/a.txt\") dst (str d \"/copy.txt\") dst2 (str d \"/copy2.txt\") out (ByteArrayOutputStream.) out2 (ByteArrayOutputStream.)] (io/copy (io/file src) (io/file dst)) (io/copy \"str!\" (io/file dst2)) (io/copy (FileInputStream. (str d \"/u.txt\")) out) (io/copy (byte-array [1 2 3]) out2) [(= (slurp src) (slurp dst)) (slurp dst2) (count (.toByteArray out)) (vec (.toByteArray out2))])"
    [true "str!" 12 [1 2 3]]]
   ["io/copy reader -> writer, file -> writer"
    "(let [w (StringWriter.) w2 (StringWriter.)] (io/copy (io/reader (str d \"/u.txt\")) w) (io/copy (io/file d \"u.txt\") w2) [(= (str w) (slurp (str d \"/u.txt\"))) (= (str w2) (str w))])" [true true]]
   ["io/copy of a missing file" "(try (io/copy (io/file d \"missing\") (io/file d \"x\")) (catch java.io.FileNotFoundException e (ex-message e)))" :copy-missing]
   ["slurp of a reader / an input stream; spit to a writer"
    "(let [f (str d \"/sp.txt\")] (with-open [w (io/writer f)] (spit w \"spat\")) [(= (slurp (io/reader (str d \"/u.txt\"))) (slurp (FileInputStream. (str d \"/u.txt\"))) (slurp (str d \"/u.txt\"))) (slurp f)])"
    [true "spat"]]
   ["io/input-stream of a file and of bytes" "[(with-open [in (io/input-stream (str d \"/u.txt\"))] (.read in)) (with-open [in (io/input-stream (byte-array [9 8]))] (.read in))]" [104 9]]
   ["edn/read over a PushbackReader, form after form, :eof"
    "(let [f (str d \"/e.edn\")] (spit f \"{:a 1} [2]\") (with-open [r (PushbackReader. (io/reader f))] [(edn/read r) (edn/read r) (edn/read {:eof :done} r)]))"
    [{:a 1} [2] :done]]
   ["a closed reader: IOException Stream closed"
    "(with-open [r (io/reader (str d \"/a.txt\"))] (.readLine r) (.close r) (try (.readLine r) (catch java.io.IOException e (ex-message e))))" "Stream closed"]
   ["with-open: two bindings close in reverse order, also when the body throws"
    "(let [log (atom []) mk (fn [n] (js-obj \"close\" (fn [] (swap! log conj n))))] [(with-open [a (mk :a) b (mk :b)] :body) (try (with-open [c (mk :c)] (throw (ex-info \"boom\" {}))) (catch :default e (ex-message e))) @log])"
    [:body "boom" [:b :a :c]]]
   ["instance? Reader / Writer / InputStream / OutputStream"
    "[(instance? java.io.Reader (StringReader. \"x\")) (instance? java.io.Writer (StringWriter.)) (instance? java.io.InputStream (FileInputStream. (str d \"/a.txt\"))) (instance? java.io.OutputStream (ByteArrayOutputStream.)) (instance? java.io.Reader \"x\")]"
    [true true true true false]]])

(def expected-messages
  {:missing (str d "/missing.txt (No such file or directory)")
   :dir (str d " (Is a directory)")
   :nodir (str d "/nodir/x.txt (No such file or directory)")
   :copy-missing (str d "/missing (No such file or directory)")})

(defn- expect [want]
  (cond (keyword? want) (get expected-messages want want)
        (vector? want) (mapv #(get expected-messages % %) want)
        :else want))

(def results
  (doall
   (for [[label src want] cases]
     (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
       {:label label :ok (= (expect want) got) :got got}))))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(fs/rmSync d #js {:recursive true :force true})

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
