;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/nio.cljs):
;; java.nio.file Path / Paths / Files, the synchronous POSIX subset.
;;   node cli.js test-scripts/jvm_nio_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): each case "THREW Unable to
;; resolve classname: java.nio.file.Files" (Path, Paths) or "java.nio.file.Path
;; is not available on this engine" (File.toPath) -- the first failure of 2 of
;; the 48 red suites in the 120-repo sample after round 1 (app-local-ai:
;; `(java.nio.file.Files/createTempDirectory ...)`, cloud-itonami-isic-5820:
;; `(Files/createTempFile "crm-file-store-test" ".edn" (make-array FileAttribute 0))`).
;; Expected values are JDK 21 / Clojure 1.12 answers (oracle 2026-09-24):
;; "a/" is "a", getParent of one name is nil, startsWith is by name element
;; ("/a/bc" does not start with "/a/b"), NoSuchFileException's message is the
;; path, deleteIfExists of nothing is false.
(ns jvm-nio-test (:require [clojure.string :as str]))

(def fs (js/require "fs"))
(def dir (.mkdtempSync fs (str (.tmpdir (js/require "os")) "/jvm-nio-")))
(set! (.-dir js/globalThis) dir)
(try (load-string "(import '[java.nio.file Files Path Paths LinkOption StandardOpenOption StandardCopyOption] '[java.nio.file.attribute FileAttribute])")
     (catch :default e (println "import:" (ex-message e))))

(def cases
  [["Path/of joins and normalizes, str is the path" "[(str (Path/of \"a\" (into-array String [\"b\" \"c\"]))) (str (Paths/get \"a/\" (make-array String 0))) (str (Path/of \"/a//b/\" (make-array String 0))) (str (Path/of \"\" (make-array String 0)))]" ["a/b/c" "a" "/a/b" ""]]
   ["= by path string" "(= (Path/of \"a\" (make-array String 0)) (Path/of \"a/\" (make-array String 0)))" true]
   ["getFileName getParent getRoot" "[(str (.getFileName (Path/of \"/a/b.txt\" (make-array String 0)))) (.getFileName (Path/of \"/\" (make-array String 0))) (.getParent (Path/of \"a\" (make-array String 0))) (str (.getParent (Path/of \"/a\" (make-array String 0)))) (str (.getRoot (Path/of \"/a\" (make-array String 0))))]" ["b.txt" nil nil "/" "/"]]
   ["normalize keeps a relative leading .." "[(str (.normalize (Path/of \"/a/../b/./c/\" (make-array String 0)))) (str (.normalize (Path/of \"../a/./b/..\" (make-array String 0))))]" ["/b/c" "../a"]]
   ["resolve / relativize" "[(str (.resolve (Path/of \"/a\" (make-array String 0)) \"b\")) (str (.resolve (Path/of \"/a\" (make-array String 0)) \"/b\")) (str (.relativize (Path/of \"/a/b\" (make-array String 0)) (Path/of \"/a/c/d\" (make-array String 0))))]" ["/a/b" "/b" "../c/d"]]
   ["startsWith is by name element" "[(.startsWith (Path/of \"/a/bc\" (make-array String 0)) \"/a/b\") (.startsWith (Path/of \"/a/b/c\" (make-array String 0)) \"/a/b\") (.endsWith (Path/of \"/a/b/c\" (make-array String 0)) \"b/c\")]" [false true true]]
   ["getNameCount getName, seq of names" "(let [p (Path/of \"/a/b\" (make-array String 0))] [(.getNameCount p) (str (.getName p 0)) (mapv str p)])" [2 "a" ["a" "b"]]]
   ["File.toPath and back" "[(str (.toPath (java.io.File. \"a/b\"))) (.getPath (.toFile (Path/of \"x/y\" (make-array String 0))))]" ["a/b" "x/y"]]
   ["toAbsolutePath of a relative path" "(= (str (.toAbsolutePath (Path/of \"q\" (make-array String 0)))) (str (js/process.cwd) \"/q\"))" true]
   ["writeString / readString / size" "(let [p (Path/of js/globalThis.dir (into-array String [\"w.txt\"]))] (Files/writeString p \"héllo\" (make-array java.nio.file.OpenOption 0)) [(Files/readString p) (Files/size p) (Files/exists p (make-array LinkOption 0)) (Files/isRegularFile p (make-array LinkOption 0))])" ["héllo" 6 true true]]
   ["write bytes / readAllBytes are signed" "(let [p (Path/of js/globalThis.dir (into-array String [\"b.bin\"]))] (Files/write p (byte-array [-119 80 78 71]) (make-array java.nio.file.OpenOption 0)) (vec (Files/readAllBytes p)))" [-119 80 78 71]]
   ["write lines / readAllLines" "(let [p (Path/of js/globalThis.dir (into-array String [\"l.txt\"]))] (Files/write p [\"a\" \"b\"] (make-array java.nio.file.OpenOption 0)) [(Files/readString p) (Files/readAllLines p)])" ["a\nb\n" ["a" "b"]]]
   ["APPEND and CREATE_NEW" "(let [p (Path/of js/globalThis.dir (into-array String [\"ap.txt\"]))] (Files/writeString p \"x\" (make-array java.nio.file.OpenOption 0)) (Files/writeString p \"y\" (into-array [StandardOpenOption/APPEND])) [(Files/readString p) (try (Files/writeString p \"z\" (into-array [StandardOpenOption/CREATE_NEW])) (catch java.nio.file.FileAlreadyExistsException e (= (ex-message e) (str p))))])" ["xy" true]]
   ["readString of a missing file: NoSuchFileException, message is the path" "(try (Files/readString (Path/of \"/nonexistent-x\" (make-array String 0))) (catch java.nio.file.NoSuchFileException e (ex-message e)))" "/nonexistent-x"]
   ["NoSuchFileException is an IOException" "(try (Files/size (Path/of \"/nonexistent-x\" (make-array String 0))) (catch java.io.IOException _ :io))" :io]
   ["delete missing throws, deleteIfExists is false" "[(try (Files/delete (Path/of \"/nonexistent-x\" (make-array String 0))) (catch java.nio.file.NoSuchFileException e (ex-message e))) (Files/deleteIfExists (Path/of \"/nonexistent-x\" (make-array String 0)))]" ["/nonexistent-x" false]]
   ["createDirectory of an existing dir: FileAlreadyExistsException" "(try (Files/createDirectory (Path/of js/globalThis.dir (make-array String 0)) (make-array FileAttribute 0)) (catch java.nio.file.FileAlreadyExistsException e (= (ex-message e) js/globalThis.dir)))" true]
   ["createDirectories is idempotent; delete of a non-empty dir refuses" "(let [p (Path/of js/globalThis.dir (into-array String [\"d1\" \"d2\"]))] (Files/createDirectories p (make-array FileAttribute 0)) (Files/createDirectories p (make-array FileAttribute 0)) [(Files/isDirectory p (make-array LinkOption 0)) (try (Files/delete (.getParent p)) (catch java.nio.file.DirectoryNotEmptyException _ :not-empty))])" [true :not-empty]]
   ["createTempFile / createTempDirectory" "(let [f (Files/createTempFile \"pre-\" \".edn\" (make-array FileAttribute 0)) d (Files/createTempDirectory \"dpre-\" (make-array FileAttribute 0))] [(str/starts-with? (str (.getFileName f)) \"pre-\") (str/ends-with? (str f) \".edn\") (Files/exists f (make-array LinkOption 0)) (Files/isDirectory d (make-array LinkOption 0)) (do (Files/delete f) (Files/delete d) (Files/exists f (make-array LinkOption 0)))])" [true true true true false]]
   ["move without / with REPLACE_EXISTING" "(let [a (Path/of js/globalThis.dir (into-array String [\"m1\"])) b (Path/of js/globalThis.dir (into-array String [\"m2\"]))] (Files/writeString a \"1\" (make-array java.nio.file.OpenOption 0)) (Files/writeString b \"2\" (make-array java.nio.file.OpenOption 0)) [(try (Files/move a b (make-array java.nio.file.CopyOption 0)) (catch java.nio.file.FileAlreadyExistsException _ :exists)) (do (Files/move a b (into-array [StandardCopyOption/REPLACE_EXISTING])) (Files/readString b)) (Files/exists a (make-array LinkOption 0))])" [:exists "1" false]]
   ["list / walk" "(let [d (Path/of js/globalThis.dir (into-array String [\"t\"]))] (Files/createDirectories (.resolve d \"s\") (make-array FileAttribute 0)) (Files/writeString (.resolve d \"s/f\") \"\" (make-array java.nio.file.OpenOption 0)) [(sort (map #(str (.getFileName %)) (iterator-seq (.iterator (Files/list d))))) (mapv #(str (.relativize d %)) (.toList (Files/walk d (make-array java.nio.file.FileVisitOption 0))))])" [["s"] ["" "s" "s/f"]]]
   ["list of a regular file: NotDirectoryException" "(let [p (Path/of js/globalThis.dir (into-array String [\"w.txt\"]))] (try (Files/list p) (catch java.nio.file.NotDirectoryException _ :not-dir)))" :not-dir]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
