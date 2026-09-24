;; Regression for the JVM-compat scaffolding (src/nbb/jvm.cljs): java.io.File
;; and the file-level subset of clojure.java.io (file as-file
;; as-relative-path as-url make-parents delete-file resource).
;;   node cli.js test-scripts/jvm_io_file_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (8ea820f): "Could not find namespace:
;; clojure.java.io" / "Unable to resolve classname: java.io.File" (the first
;; failure of 7 of 76 red JVM-era suites sampled on 2026-09-24); here each
;; case "THREW Could not resolve symbol: clojure.java.io/file" or "Unable to
;; resolve classname: java.io.File".
(ns jvm-io-file-test
  (:require [nbb.classpath :as ncp]))

(def fs (js/require "fs"))
(def dir (.realpathSync fs (.mkdtempSync fs (str (.tmpdir (js/require "os")) "/jvm-io-"))))
(.writeFileSync fs (str dir "/a.txt") "abc")
(.mkdirSync fs (str dir "/res/sub") #js {:recursive true})
(.writeFileSync fs (str dir "/res/sub/r.edn") "{:r 1}")
(ncp/add-classpath (str dir "/res"))
(set! (.-dir js/globalThis) dir)
(js/process.chdir dir)

(def cases
  [;; java.io.UnixFileSystem.normalize: repeated and trailing '/' go, . and .. stay
   ["normalises // and trailing /" "(str (java.io.File. \"a//b/\"))" "a/b"]
   ["keeps . and .." "(.getPath (java.io.File. \"./a/../b\"))" "./a/../b"]
   ["root stays /" "(.getPath (java.io.File. \"/\"))" "/"]
   ["(File. parent child)" "(str (java.io.File. \"a/\" \"b\"))" "a/b"]
   ["(File. \"\" child) resolves against /" "(str (java.io.File. \"\" \"b\"))" "/b"]
   ["(File. nil child)" "(str (java.io.File. nil \"b\"))" "b"]
   ["getName / getParent" "(let [f (java.io.File. \"x/y/z.txt\")] [(.getName f) (.getParent f)])" ["z.txt" "x/y"]]
   ["getParent of a bare name is nil" "(.getParent (java.io.File. \"z.txt\"))" nil]
   ["getParent of /a is /" "(.getParent (java.io.File. \"/a\"))" "/"]
   ["getParentFile" "(str (.getParentFile (java.io.File. \"x/y\")))" "x"]
   ["getAbsolutePath (no .. resolution)" "(.getAbsolutePath (java.io.File. \"q/../a.txt\"))" (str dir "/q/../a.txt")]
   ["getCanonicalPath resolves .." "(.getCanonicalPath (java.io.File. \"q/../a.txt\"))" (str dir "/a.txt")]
   ["exists / isFile / isDirectory" "(let [f (java.io.File. \"a.txt\") d (java.io.File. \"res\")] [(.exists f) (.isFile f) (.isDirectory f) (.isDirectory d) (.exists (java.io.File. \"none\"))])" [true true false true false]]
   ["length / lastModified of a missing file are 0" "[(.length (java.io.File. \"a.txt\")) (.length (java.io.File. \"none\")) (.lastModified (java.io.File. \"none\"))]" [3 0 0]]
   ["mkdirs: true once, false when it exists" "(let [f (java.io.File. \"m/n/o\")] [(.mkdirs f) (.mkdirs f) (.isDirectory f)])" [true false true]]
   ["delete: true, then false" "(let [f (java.io.File. \"del.txt\")] (spit f 1) [(.delete f) (.delete f)])" [true false]]
   ["listFiles gives Files, list gives names" "(let [d (java.io.File. \"res\")] [(mapv str (.listFiles d)) (vec (.list d)) (.listFiles (java.io.File. \"a.txt\"))])" [["res/sub"] ["sub"] nil]]
   ["= and instance?" "[(= (java.io.File. \"a\") (java.io.File. \"a/\")) (= (java.io.File. \"a\") (java.io.File. \"b\")) (instance? java.io.File (java.io.File. \"a\"))]" [true false true]]
   ["import java.io.File" "(do (import '[java.io File]) (str (File. \"i\" \"j\")))" "i/j"]
   ["File/separator" "java.io.File/separator" "/"]
   ;; clojure.java.io
   ["io/file joins" "(str (clojure.java.io/file \"a\" \"b\" \"c.txt\"))" "a/b/c.txt"]
   ["io/file of a File is that File" "(let [f (java.io.File. \"a\")] (identical? f (clojure.java.io/file f)))" true]
   ["io/file nil is nil" "(clojure.java.io/file nil)" nil]
   ["io/file refuses an absolute child"
    "(try (clojure.java.io/file \"a\" \"/b\") (catch IllegalArgumentException e (ex-message e)))" "/b is not a relative path"]
   ["io/make-parents" "(do (clojure.java.io/make-parents \"p/q/r.txt\") (.isDirectory (java.io.File. \"p/q\")))" true]
   ["io/delete-file silently" "(clojure.java.io/delete-file \"none\" :gone)" :gone]
   ["io/delete-file loudly: IOException"
    "(try (clojure.java.io/delete-file \"none\") (catch java.io.IOException e (ex-message e)))" "Couldn't delete none"]
   ["io/resource finds a classpath entry; slurp reads it" "(slurp (clojure.java.io/resource \"sub/r.edn\"))" "{:r 1}"]
   ["io/resource of an absent name is nil" "(clojure.java.io/resource \"sub/none.edn\")" nil]
   ["io/resource with a leading / is nil (ClassLoader.getResource)" "(clojure.java.io/resource \"/sub/r.edn\")" nil]
   ["io/as-url of a File is a file: URL" "(str (clojure.java.io/as-url (java.io.File. \"/x/y\")))" "file:///x/y"]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
