(ns nbb.jvm.nio
  "JVM-compatibility SCAFFOLDING, round 2: java.nio.file Path / Paths / Files
  (the synchronous, POSIX subset) over node:fs (see nbb.jvm for why this
  exists; it is not a target for new code, which uses kotoba.io).

  Path is sun.nio.fs.UnixPath: `(Path/of \"a/\")` is \"a\", getParent of a
  single name is nil, normalize / resolve / relativize / startsWith work on
  name elements, (seq path) is its name elements, = / hash / compare by the
  path string. File.toPath answers one. Files: exists notExists isDirectory
  isRegularFile isReadable isWritable isExecutable isSymbolicLink isHidden
  size readAllBytes readString readAllLines lines write writeString
  createFile createDirectory createDirectories createTempFile
  createTempDirectory delete deleteIfExists copy move list walk
  getLastModifiedTime isSameFile, with StandardOpenOption /
  StandardCopyOption / LinkOption and the JVM's exception classes and
  messages (NoSuchFileException: the path, FileAlreadyExistsException, ...).
  readAllLines answers a vector (the JVM an ArrayList, which = a vector).

  Deliberately NOT here: channels, readers/writers, newInputStream /
  newOutputStream, file attributes and permissions, watch services,
  probeContentType (platform-dependent on the JVM)."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]
            [nbb.jvm.net :as net]))

(def FileSystemException (jvm/defclass "java.nio.file.FileSystemException" jvm/IOException))
(def NoSuchFileException (jvm/defclass "java.nio.file.NoSuchFileException" FileSystemException))
(def FileAlreadyExistsException (jvm/defclass "java.nio.file.FileAlreadyExistsException" FileSystemException))
(def DirectoryNotEmptyException (jvm/defclass "java.nio.file.DirectoryNotEmptyException" FileSystemException))
(def NotDirectoryException (jvm/defclass "java.nio.file.NotDirectoryException" FileSystemException))
(def AccessDeniedException (jvm/defclass "java.nio.file.AccessDeniedException" FileSystemException))
(def InvalidPathException (jvm/defclass "java.nio.file.InvalidPathException" jvm/IllegalArgumentException))

;; ---------------------------------------------------------------------------
;; Path
;; ---------------------------------------------------------------------------

(defn- normalize-string
  "UnixPath.normalizeAndCheck: collapse '//' and drop a trailing '/'."
  [s]
  (when (str/includes? s "\u0000")
    (throw (InvalidPathException. (str "Nul character not allowed: " s))))
  (let [s (str/replace s #"/{2,}" "/")]
    (if (and (> (count s) 1) (str/ends-with? s "/")) (subs s 0 (dec (count s))) s)))

(declare make-path path?)

(defn- names [p] (if (or (= "" p) (= "/" p)) (if (= "" p) [""] []) (vec (remove #(= "" %) (str/split p #"/")))))

(defn- ->pstr [x]
  (cond (string? x) (normalize-string x)
        (path? x) (.-p ^js x)
        :else (throw (js/TypeError. (str "Not a path: " (pr-str x))))))

(defn- fs-error
  "A node:fs error as the JVM's FileSystemException subclass for `p`."
  [^js e p]
  (case (.-code e)
    "ENOENT" (NoSuchFileException. p)
    "EEXIST" (FileAlreadyExistsException. p)
    "ENOTEMPTY" (DirectoryNotEmptyException. p)
    "ENOTDIR" (NotDirectoryException. p)
    ("EACCES" "EPERM") (AccessDeniedException. p)
    (if (.-code e) (FileSystemException. (str p ": " (.-message e))) e)))

(defn- normalize-names [absolute? ns]
  (reduce (fn [acc n]
            (cond (= "." n) acc
                  (= ".." n) (cond (and (seq acc) (not= ".." (peek acc))) (pop acc)
                                   absolute? acc
                                   :else (conj acc n))
                  :else (conj acc n)))
          [] ns))

(deftype Path [p]
  Object
  (toString [_] p)
  (isAbsolute [_] (str/starts-with? p "/"))
  (getRoot [_] (when (str/starts-with? p "/") (make-path "/")))
  (getFileName [_] (cond (= "/" p) nil
                         (= "" p) (make-path "")
                         :else (make-path (peek (names p)))))
  (getParent [_] (let [i (str/last-index-of p "/")]
                   (cond (nil? i) nil
                         (zero? i) (when (> (count p) 1) (make-path "/"))
                         :else (make-path (subs p 0 i)))))
  (getNameCount [_] (count (names p)))
  (getName [_ i] (let [ns (names p)]
                   (if (and (<= 0 i) (< i (count ns))) (make-path (nth ns i)) (throw (jvm/IllegalArgumentException.)))))
  (subpath [_ b e] (let [ns (names p)]
                     (if (and (<= 0 b) (< b e) (<= e (count ns))) (make-path (str/join "/" (subvec ns b e)))
                         (throw (jvm/IllegalArgumentException.)))))
  (resolve [this o] (let [o (->pstr o)]
                      (cond (str/starts-with? o "/") (make-path o)
                            (= "" o) this
                            (= "" p) (make-path o)
                            (= "/" p) (make-path (str "/" o))
                            :else (make-path (str p "/" o)))))
  (resolveSibling [this o] (if-let [^js parent (.getParent this)] (.resolve parent o) (make-path (->pstr o))))
  (normalize [_] (let [abs? (str/starts-with? p "/")
                       ns (normalize-names abs? (names p))]
                   (make-path (str (when abs? "/") (str/join "/" ns)))))
  (relativize [this o]
    (let [o (->pstr o)]
      (when (not= (str/starts-with? p "/") (str/starts-with? o "/"))
        (throw (jvm/IllegalArgumentException. "'other' is different type of Path")))
      (let [a (if (= "" p) [] (names p)) b (if (= "" o) [] (names o))
            k (count (take-while true? (map = a b)))]
        (make-path (str/join "/" (concat (repeat (- (count a) k) "..") (drop k b)))))))
  (toAbsolutePath [this] (if (str/starts-with? p "/") this
                             (make-path (if (= "" p) (js/process.cwd) (str (js/process.cwd) "/" p)))))
  (toRealPath [this & _] (try (make-path (fs/realpathSync (.-p ^js (.toAbsolutePath this))))
                              (catch :default e (throw (fs-error e p)))))
  (toFile [_] (jvm/new-file p))
  (toUri [this] (let [a (.-p ^js (.toAbsolutePath this))
                      dir? (some-> (jvm/stat a) .isDirectory)]
                  (net/make-uri (str "file://" (js/encodeURI (str a (when (and dir? (not (str/ends-with? a "/"))) "/")))) false)))
  (startsWith [_ o] (let [o (->pstr o)]
                      (and (= (str/starts-with? p "/") (str/starts-with? o "/"))
                           (let [a (names p) b (names o)]
                             (and (<= (count b) (count a)) (= b (subvec a 0 (count b))))))))
  (endsWith [_ o] (let [o (->pstr o)]
                    (if (str/starts-with? o "/") (= o p)
                        (let [a (names p) b (names o)]
                          (and (<= (count b) (count a)) (= b (subvec a (- (count a) (count b)))))))))
  (iterator [_] (let [s (atom (seq (map make-path (if (= "" p) [""] (names p)))))]
                  #js {"hasNext" (fn [] (boolean (seq @s)))
                       "next" (fn [] (let [x (first @s)] (swap! s next) x))}))
  (compareTo [_ o] (compare p (.-p ^js o)))
  (equals [_ o] (and (path? o) (= p (.-p ^js o))))
  (hashCode [_] (hash p))
  ISeqable
  (-seq [_] (seq (map make-path (if (= "" p) [""] (names p)))))
  IEquiv
  (-equiv [_ o] (and (path? o) (= p (.-p ^js o))))
  IHash
  (-hash [_] (hash p))
  IComparable
  (-compare [_ o] (compare p (.-p ^js o)))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (str "#object[sun.nio.fs.UnixPath \"" p "\"]"))))

(defn path? [x] (instance? Path x))

(defn make-path [s] (Path. (normalize-string s)))

(defn- path-of [first* & more]
  (let [more (if (and (= 1 (count more)) (array? (first more))) (seq (first more)) more)
        parts (remove #(= "" %) (cons (str first*) (map str more)))]
    (make-path (str/join "/" parts))))

(defn- ps ^string [x] (if (path? x) (.-p ^js x) (->pstr (str x))))

;; ---------------------------------------------------------------------------
;; options
;; ---------------------------------------------------------------------------

(deftype OptionValue [cls nm]
  Object
  (toString [_] nm)
  (name [_] nm)
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (str "#object[" cls " \"" nm "\"]"))))

(defn- enum [cls names*]
  (let [o (js-obj)]
    (doseq [n names*] (gobj/set o n (OptionValue. cls n)))
    (gobj/set o "values" (fn [] (into-array (map #(gobj/get o %) names*))))
    (gobj/set o "valueOf" (fn [n] (or (gobj/get o n)
                                      (throw (jvm/IllegalArgumentException. (str "No enum constant " cls "." n))))))
    o))

(def StandardOpenOption (enum "java.nio.file.StandardOpenOption"
                              ["READ" "WRITE" "APPEND" "TRUNCATE_EXISTING" "CREATE" "CREATE_NEW"
                               "DELETE_ON_CLOSE" "SPARSE" "SYNC" "DSYNC"]))
(def StandardCopyOption (enum "java.nio.file.StandardCopyOption" ["REPLACE_EXISTING" "COPY_ATTRIBUTES" "ATOMIC_MOVE"]))
(def LinkOption (enum "java.nio.file.LinkOption" ["NOFOLLOW_LINKS"]))

(defn- flatten-opts [opts]
  (set (map str (mapcat #(if (array? %) (seq %) [%]) opts))))

(defn- marker [nm]
  (let [c (js* "(function(n){ var C = function(){}; Object.defineProperty(C, 'name', {value: n}); return C; })(~{})" nm)]
    c))

;; ---------------------------------------------------------------------------
;; Stream (the part a Clojure caller reaches: iterator, toList, close, ...)
;; ---------------------------------------------------------------------------

(defn- call-fn [f & args]
  (if (fn? f) (apply f args)
      (let [m (some #(when (fn? (gobj/get f %)) %) ["apply" "test" "accept"])]
        (.apply (gobj/get f m) f (into-array args)))))

(deftype Stream [xs]
  Object
  (iterator [_] (let [s (atom (seq xs))]
                  #js {"hasNext" (fn [] (boolean (seq @s)))
                       "next" (fn [] (if-let [q (seq @s)] (let [x (first q)] (reset! s (next q)) x)
                                         (throw (jvm/NoSuchElementException.))))}))
  (toList [_] (vec xs))
  (toArray [_] (into-array xs))
  (count [_] (count xs))
  (forEach [_ f] (doseq [x xs] (call-fn f x)) nil)
  (filter [_ f] (Stream. (filter #(call-fn f %) xs)))
  (map [_ f] (Stream. (map #(call-fn f %) xs)))
  (limit [_ n] (Stream. (take n xs)))
  (skip [_ n] (Stream. (drop n xs)))
  (sorted [_] (Stream. (sort xs)))
  (close [_] nil))

;; ---------------------------------------------------------------------------
;; Files
;; ---------------------------------------------------------------------------

(defn- ^js lstat [p] (try (fs/lstatSync p) (catch :default _ nil)))

(defn- follow? [opts] (not (contains? (flatten-opts opts) "NOFOLLOW_LINKS")))

(defn- stat-of [p opts] (if (follow? opts) (jvm/stat p) (lstat p)))

(defn- with-fs [p f]
  (try (f) (catch :default e (throw (fs-error e p)))))

(defn- charset-and-opts
  "(p cs opts...) or (p opts...): the JVM overloads by type."
  [args]
  (if (and (seq args) (instance? jb/Charset (first args)))
    [(first args) (rest args)]
    [jb/UTF_8 args]))

(defn- write-bytes! [p ^js data opts]
  (let [o (flatten-opts opts)
        o (if (empty? o) #{"CREATE" "TRUNCATE_EXISTING" "WRITE"} o)
        exists? (some? (jvm/stat p))]
    (cond (and (contains? o "CREATE_NEW") exists?) (throw (FileAlreadyExistsException. p))
          (and (not exists?) (not (or (contains? o "CREATE") (contains? o "CREATE_NEW")))) (throw (NoSuchFileException. p)))
    (with-fs p #(if (contains? o "APPEND")
                  (fs/appendFileSync p data)
                  (fs/writeFileSync p data)))))

(defn- temp-name [prefix suffix]
  (str (or prefix "") (.toString (js/BigInt.asUintN 63 (js/BigInt (js/Math.floor (* (js/Math.random) js/Number.MAX_SAFE_INTEGER))))) suffix))

(defn- create-temp-file [& args]
  (let [[dir prefix suffix] (if (or (path? (first args)) (and (>= (count args) 3) (not (array? (nth args 2)))))
                              [(ps (first args)) (second args) (nth args 2)]
                              [(str/replace (os/tmpdir) #"/$" "") (first args) (second args)])
        p (str dir "/" (temp-name prefix (if (nil? suffix) ".tmp" suffix)))]
    (with-fs p #(fs/writeFileSync p "" #js {"flag" "wx" "mode" 0600}))
    (make-path p)))

(defn- create-temp-directory [& args]
  (let [[dir prefix] (if (path? (first args))
                       [(ps (first args)) (second args)]
                       [(str/replace (os/tmpdir) #"/$" "") (first args)])
        p (str dir "/" (temp-name prefix ""))]
    (with-fs p #(fs/mkdirSync p #js {"mode" 0700}))
    (make-path p)))

(defn- delete! [p]
  (let [s (lstat p)]
    (when-not s (throw (NoSuchFileException. p)))
    (with-fs p #(if (.isDirectory s) (fs/rmdirSync p) (fs/unlinkSync p)))))

(defn- walk-seq [p max-depth]
  (letfn [(step [q depth]
            (lazy-seq
             (cons (make-path q)
                   (when (and (< depth max-depth) (some-> (jvm/stat q) .isDirectory))
                     (mapcat #(step (if (= "/" q) (str "/" %) (str q "/" %)) (inc depth))
                             (with-fs q #(vec (fs/readdirSync q))))))))]
    (step p 0)))

(deftype FileTime [ms]
  Object
  (toMillis [_] ms)
  (toString [_] (str/replace (.toISOString (js/Date. ms)) #"\.000Z$" "Z"))
  (compareTo [_ o] (compare ms (.-ms ^js o)))
  IEquiv
  (-equiv [_ o] (and (instance? FileTime o) (= ms (.-ms ^js o))))
  IComparable
  (-compare [_ o] (compare ms (.-ms ^js o))))

(def Files
  (jvm/strict-statics
   "java.nio.file.Files"
   (js-obj
    "exists" (fn [p & opts] (some? (stat-of (ps p) opts)))
    "notExists" (fn [p & opts] (nil? (stat-of (ps p) opts)))
    "isDirectory" (fn [p & opts] (boolean (some-> (stat-of (ps p) opts) .isDirectory)))
    "isRegularFile" (fn [p & opts] (boolean (some-> (stat-of (ps p) opts) .isFile)))
    "isSymbolicLink" (fn [p] (boolean (some-> (lstat (ps p)) .isSymbolicLink)))
    "isReadable" (fn [p] (try (fs/accessSync (ps p) (.-R_OK ^js (.-constants fs))) true (catch :default _ false)))
    "isWritable" (fn [p] (try (fs/accessSync (ps p) (.-W_OK ^js (.-constants fs))) true (catch :default _ false)))
    "isExecutable" (fn [p] (try (fs/accessSync (ps p) (.-X_OK ^js (.-constants fs))) true (catch :default _ false)))
    "isHidden" (fn [p] (str/starts-with? (or (some-> ^js (.getFileName (if (path? p) p (make-path (ps p)))) str) "") "."))
    "isSameFile" (fn [a b] (or (= (ps a) (ps b))
                               (let [^js x (with-fs (ps a) #(fs/statSync (ps a))) ^js y (with-fs (ps b) #(fs/statSync (ps b)))]
                                 (and (= (.-ino x) (.-ino y)) (= (.-dev x) (.-dev y))))))
    "size" (fn [p] (let [p (ps p)] (.-size ^js (with-fs p #(fs/statSync p)))))
    "getLastModifiedTime" (fn [p & _] (let [p (ps p)] (FileTime. (js/Math.floor (.-mtimeMs ^js (with-fs p #(fs/statSync p)))))))
    "readAllBytes" (fn [p] (let [p (ps p)] (jb/->bytes (with-fs p #(fs/readFileSync p)))))
    "readString" (fn ([p] (let [p (ps p)] (jb/decode (with-fs p #(fs/readFileSync p)) jb/UTF_8)))
                   ([p cs] (let [p (ps p)] (jb/decode (with-fs p #(fs/readFileSync p)) cs))))
    "readAllLines" (fn ([p] (let [p (ps p)] (vec (str/split-lines (jb/decode (with-fs p #(fs/readFileSync p)) jb/UTF_8)))))
                     ([p cs] (let [p (ps p)] (vec (str/split-lines (jb/decode (with-fs p #(fs/readFileSync p)) cs))))))
    "lines" (fn ([p] (let [p (ps p)] (Stream. (str/split-lines (jb/decode (with-fs p #(fs/readFileSync p)) jb/UTF_8)))))
              ([p cs] (let [p (ps p)] (Stream. (str/split-lines (jb/decode (with-fs p #(fs/readFileSync p)) cs))))))
    "write" (fn [p data & more]
              (let [s (ps p)]
                (if (or (instance? js/Int8Array data) (instance? js/Uint8Array data))
                  (write-bytes! s (js/Buffer.from (jb/u8 data)) more)
                  (let [[cs opts] (charset-and-opts more)
                        text (apply str (map #(str % "\n") data))]
                    (write-bytes! s (js/Buffer.from (jb/u8 (jb/encode text cs))) opts)))
                p))
    "writeString" (fn [p csq & more]
                    (let [s (ps p) [cs opts] (charset-and-opts more)]
                      (write-bytes! s (js/Buffer.from (jb/u8 (jb/encode (str csq) cs))) opts)
                      p))
    "createFile" (fn [p & _] (let [s (ps p)] (with-fs s #(fs/writeFileSync s "" #js {"flag" "wx"})) p))
    "createDirectory" (fn [p & _] (let [s (ps p)] (with-fs s #(fs/mkdirSync s)) p))
    "createDirectories" (fn [p & _] (let [s (ps p) st (jvm/stat s)]
                                      (cond (nil? st) (with-fs s #(fs/mkdirSync s #js {"recursive" true}))
                                            (not (.isDirectory st)) (throw (FileAlreadyExistsException. s)))
                                      p))
    "createTempFile" create-temp-file
    "createTempDirectory" create-temp-directory
    "delete" (fn [p] (delete! (ps p)) nil)
    "deleteIfExists" (fn [p] (let [s (ps p)] (if (lstat s) (do (delete! s) true) false)))
    "copy" (fn [src dst & opts]
             (let [a (ps src) b (ps dst) o (flatten-opts opts)]
               (when-not (jvm/stat a) (throw (NoSuchFileException. a)))
               (when (and (lstat b) (not (contains? o "REPLACE_EXISTING"))) (throw (FileAlreadyExistsException. b)))
               (if (.isDirectory (jvm/stat a))
                 (when-not (jvm/stat b) (with-fs b #(fs/mkdirSync b)))
                 (with-fs b #(fs/copyFileSync a b)))
               dst))
    "move" (fn [src dst & opts]
             (let [a (ps src) b (ps dst) o (flatten-opts opts)]
               (when-not (lstat a) (throw (NoSuchFileException. a)))
               (when (and (lstat b) (not (contains? o "REPLACE_EXISTING")) (not= a b))
                 (throw (FileAlreadyExistsException. b)))
               (with-fs a #(fs/renameSync a b))
               dst))
    "list" (fn [p] (let [s (ps p) st (jvm/stat s)]
                     (cond (nil? st) (throw (NoSuchFileException. s))
                           (not (.isDirectory st)) (throw (NotDirectoryException. s)))
                     (Stream. (mapv #(make-path (if (= "/" s) (str "/" %) (str s "/" %))) (fs/readdirSync s)))))
    "walk" (fn [p & more]
             (let [s (ps p)
                   depth (if (number? (first more)) (first more) js/Number.MAX_SAFE_INTEGER)]
               (when-not (jvm/stat s) (throw (NoSuchFileException. s)))
               (Stream. (walk-seq s depth)))))
   ["newInputStream" "newOutputStream" "newBufferedReader" "newBufferedWriter" "probeContentType"
    "newByteChannel" "getAttribute" "readAttributes" "setPosixFilePermissions" "getPosixFilePermissions"]))

(def Paths (js-obj "get" path-of))

(def PathClass
  (let [c (js* "(function(){ var P = function Path(){}; return P; })()")]
    (set! (.-prototype c) (.-prototype Path))
    (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" (fn [x] (instance? Path x))})
    (gobj/set c "of" path-of)
    c))

(defn install! []
  (reset! jvm/path-of make-path))

(defn classes []
  {'java.nio.file.Path PathClass
   'java.nio.file.Paths Paths
   'java.nio.file.Files Files
   'java.nio.file.StandardOpenOption StandardOpenOption
   'java.nio.file.StandardCopyOption StandardCopyOption
   'java.nio.file.LinkOption LinkOption
   'java.nio.file.FileVisitOption (enum "java.nio.file.FileVisitOption" ["FOLLOW_LINKS"])
   'java.nio.file.OpenOption (marker "java.nio.file.OpenOption")
   'java.nio.file.CopyOption (marker "java.nio.file.CopyOption")
   'java.nio.file.attribute.FileAttribute (marker "java.nio.file.attribute.FileAttribute")
   'java.nio.file.attribute.FileTime (js-obj "fromMillis" (fn [ms] (FileTime. ms)))
   'java.nio.file.FileSystemException FileSystemException
   'java.nio.file.NoSuchFileException NoSuchFileException
   'java.nio.file.FileAlreadyExistsException FileAlreadyExistsException
   'java.nio.file.DirectoryNotEmptyException DirectoryNotEmptyException
   'java.nio.file.NotDirectoryException NotDirectoryException
   'java.nio.file.AccessDeniedException AccessDeniedException
   'java.nio.file.InvalidPathException InvalidPathException})
