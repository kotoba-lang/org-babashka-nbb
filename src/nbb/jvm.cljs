(ns nbb.jvm
  "JVM-compatibility SCAFFOLDING for the kbb engine (kotoba-lang/kotoba bin/kbb).

  Why this exists: fleet codemods (clojure.* / java.* -> kotoba stdlib) land a
  repository only when its own test suite is green BEFORE the change. Suites
  written for the JVM died on this engine at their first host-interop symbol
  (`slurp`, `Exception`, `clojure.lang.ExceptionInfo`, `clojure.java.io`,
  `java.io.File`, `System/exit`, `Long/parseLong`, `format`, ...), so the
  codemods could never start on them. This namespace gives those names their
  JVM meaning where a test can observe it, so the suites run and the codemods
  can then REMOVE the java.* / clojure.* uses from the sources over time. It is
  not a JVM and not a target to write new code against: new code uses the
  kotoba stdlib (kotoba.io, kotoba.text, ...).

  Rule for what is here: a name is shimmed only when its observable semantics
  can be matched on Node (messages, exception classes, path normalisation,
  rounding). Names whose semantics cannot be matched are left UNRESOLVED on
  purpose (an analysis error naming the symbol beats a silently different
  answer): Long/MAX_VALUE and Long/MIN_VALUE (not representable in a double),
  instance? checks on Long/Integer/Double (1 and 1.0 are the same JS value),
  java.lang.Error / AssertionError, NullPointerException / ClassCastException /
  IndexOutOfBoundsException / ArithmeticException (the host throws TypeError /
  Error or nothing at all for those situations), streams (io/reader,
  io/input-stream, io/copy, ...), bytes (.getBytes, String. from bytes,
  StandardCharsets, Base64), java.time, java.nio.file, java.security.
  Known, documented deviations of what IS here: Long/parseLong of a value
  beyond 2^53 returns the nearest double; format accepts an integral value for
  %f/%e (the JVM throws for a Long there; a JS number cannot say which it
  was); io/resource returns a js/URL whose str is `file:///...` (the JVM prints
  `file:/...`)."
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            ["node:url" :as url]
            [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.classpath :as cp]))

;; ---------------------------------------------------------------------------
;; exceptions
;; ---------------------------------------------------------------------------

(def ^:private real-instance?
  "instanceof that ignores a Symbol.hasInstance override (the prototype chain)."
  (js* "(function(C, x){ return Function.prototype[Symbol.hasInstance].call(C, x); })"))

(def ^:private make-class
  ;; Java constructor shapes: (), (msg), (msg, cause), (cause).
  ;; getMessage of the no-arg / (nil) form is null, as on the JVM; a
  ;; (cause) form's message is cause.toString().
  (js* "(function(jname, Parent){
  var C = class extends Parent {
    constructor(a, b) {
      var n = arguments.length, msg, cause;
      if (n === 0) { msg = null; }
      else if (n === 1 && (a instanceof Error)) { cause = a; msg = String(a); }
      else { msg = (a == null) ? null : String(a); cause = b; }
      super(msg == null ? '' : msg);
      Object.defineProperty(this, 'message', {value: msg, writable: true, configurable: true, enumerable: false});
      if (cause != null) { Object.defineProperty(this, 'cause', {value: cause, writable: true, configurable: true, enumerable: false}); }
    }
  };
  Object.defineProperty(C, 'name', {value: jname, configurable: true});
  Object.defineProperty(C.prototype, 'name', {value: jname, writable: true, configurable: true, enumerable: false});
  return C; })"))

(defn- set-has-instance! [c f]
  (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" f "configurable" true}))

(def Throwable
  (let [c (make-class "java.lang.Throwable" js/Error)]
    ;; every JS error is a Throwable (a JS program can throw non-Errors; the
    ;; JVM cannot, so those are not)
    (set-has-instance! c (fn [x] (instance? js/Error x)))
    c))

(defn- shim? [x] (real-instance? Throwable x))

(defn- foreign-error?
  "A JS Error (TypeError, cljs ExceptionInfo, ...) not built from these
  classes. On the JVM such failures are RuntimeExceptions: ExceptionInfo
  extends RuntimeException, a nil deref is an NPE, a bad cast a CCE."
  [x]
  (and (instance? js/Error x) (not (shim? x))))

(defn- defclass [jname parent]
  (let [c (make-class jname parent)]
    (set-has-instance! c (fn [x] (real-instance? c x)))
    c))

(def Exception
  (let [c (make-class "java.lang.Exception" Throwable)]
    (set-has-instance! c (fn [x] (or (real-instance? c x) (foreign-error? x))))
    c))

(def RuntimeException
  (let [c (make-class "java.lang.RuntimeException" Exception)]
    (set-has-instance! c (fn [x] (or (real-instance? c x) (foreign-error? x))))
    c))

(def IllegalArgumentException (defclass "java.lang.IllegalArgumentException" RuntimeException))
(def IllegalStateException (defclass "java.lang.IllegalStateException" RuntimeException))
(def NumberFormatException (defclass "java.lang.NumberFormatException" IllegalArgumentException))
(def UnsupportedOperationException (defclass "java.lang.UnsupportedOperationException" RuntimeException))
(def InterruptedException (defclass "java.lang.InterruptedException" Exception))
(def IOException (defclass "java.io.IOException" Exception))
(def FileNotFoundException (defclass "java.io.FileNotFoundException" IOException))
(def UncheckedIOException (defclass "java.io.UncheckedIOException" RuntimeException))
(def NoSuchElementException (defclass "java.util.NoSuchElementException" RuntimeException))
(def TimeoutException (defclass "java.util.concurrent.TimeoutException" Exception))
(def IllegalFormatException (defclass "java.util.IllegalFormatException" IllegalArgumentException))
(def MissingFormatArgumentException (defclass "java.util.MissingFormatArgumentException" IllegalFormatException))
(def UnknownFormatConversionException (defclass "java.util.UnknownFormatConversionException" IllegalFormatException))
(def IllegalFormatConversionException (defclass "java.util.IllegalFormatConversionException" IllegalFormatException))

(defn- define-method! [obj k f]
  (when-not (js-in k obj)
    (js/Object.defineProperty obj k #js {"value" f "writable" true "configurable" true "enumerable" false})))

(defn- install-throwable-methods!
  "Throwable's instance methods on every JS error (non-enumerable, only where
  absent), so `(.getMessage e)` works on a TypeError or an ex-info as on the JVM."
  []
  (let [p (.-prototype js/Error)]
    (define-method! p "getMessage" (fn [] (this-as ^js this (let [m (.-message this)] (if (undefined? m) nil m)))))
    (define-method! p "getLocalizedMessage" (fn [] (this-as ^js this (.getMessage this))))
    (define-method! p "getCause" (fn [] (this-as ^js this (let [c (gobj/get this "cause")] (if (undefined? c) nil c)))))
    (define-method! p "printStackTrace" (fn [] (this-as ^js this (js/console.error (.-stack this))) nil)))
  (define-method! (.-prototype ExceptionInfo) "getData" (fn [] (this-as ^js this (ex-data this))))
  ;; the JVM's Throwable.toString: the class name, then ": message" when there is one
  (js/Object.defineProperty (.-prototype Throwable) "toString"
                            #js {"value" (fn [] (this-as ^js this
                                                  (let [m (.getLocalizedMessage this)]
                                                    (if (nil? m) (.-name this) (str (.-name this) ": " m)))))
                                 "writable" true "configurable" true "enumerable" false}))

(defn ex-cause*
  "clojure.core/ex-cause on the JVM answers for any Throwable; cljs.core's only
  for ExceptionInfo."
  [ex]
  (cond (instance? ExceptionInfo ex) (.-cause ex)
        (instance? js/Error ex) (let [c (gobj/get ex "cause")] (if (undefined? c) nil c))
        :else nil))

;; ---------------------------------------------------------------------------
;; numbers
;; ---------------------------------------------------------------------------

(defn- strict-statics
  "The class object `o` behind a Proxy that throws when one of the `absent`
  JVM statics is read. Without it `Long/MAX_VALUE` (deliberately not shimmed:
  2^63-1 is not a double) would read as undefined -- a silently different
  answer instead of an error naming the field. The list is explicit: the
  engine itself probes these objects for (minified) protocol marks, so
  \"any missing key\" cannot be told apart from a user's field read."
  [jname o absent]
  (let [absent (set absent)]
    (js/Proxy. o
               #js {"get" (fn [target k receiver]
                            (if (and (string? k) (contains? absent k))
                              (throw (IllegalArgumentException.
                                      (str "Unable to find static field: " k " in class " jname
                                           " (deliberately not shimmed on this engine; see nbb.jvm)")))
                              (js/Reflect.get target k receiver)))})))

(defn- for-input [s radix]
  (str "For input string: \"" s "\"" (when (not= radix 10) (str " under radix " radix))))

(defn- parse-integral
  "Integer.parseInt / Long.parseLong: optional sign, then digits of `radix`,
  nothing else (no whitespace, no decimal point), within [lo, hi] -- else
  NumberFormatException with the JVM's message."
  [s radix lo hi]
  (when (nil? s) (throw (NumberFormatException. "Cannot parse null string: null")))
  (let [s (str s)
        m (re-matches #"([+-]?)([0-9A-Za-z]+)" s)]
    (when-not m (throw (NumberFormatException. (for-input s radix))))
    (let [[_ sign ds] m
          big-radix (js/BigInt radix)
          v (reduce (fn [acc ch]
                      (let [d (js/parseInt ch 36)]
                        (if (or (js/isNaN d) (>= d radix))
                          (throw (NumberFormatException. (for-input s radix)))
                          (+ (* acc big-radix) (js/BigInt d)))))
                    (js/BigInt 0) ds)
          v (if (= sign "-") (- v) v)]
      (when (or (< v lo) (> v hi)) (throw (NumberFormatException. (for-input s radix))))
      (js/Number v))))

(def ^:private int-lo (js/BigInt -2147483648))
(def ^:private int-hi (js/BigInt 2147483647))
(def ^:private long-lo (- (js/BigInt "9223372036854775808")))
(def ^:private long-hi (js/BigInt "9223372036854775807"))

(defn parse-int
  ([s] (parse-int s 10))
  ([s radix] (parse-integral s radix int-lo int-hi)))

(defn parse-long*
  ([s] (parse-long* s 10))
  ([s radix] (parse-integral s radix long-lo long-hi)))

(defn- unsigned-string [n bits radix]
  (.toString (js/BigInt.asUintN bits (js/BigInt n)) radix))

(defn parse-double*
  "Double.parseDouble: surrounding whitespace (chars <= space) is trimmed;
  NaN, Infinity, decimal and exponent forms with an optional f/F/d/D suffix."
  [s]
  (when (nil? s) (throw (js/TypeError. "Cannot invoke \"String.trim()\" because \"in\" is null")))
  (let [t (str/replace (str s) #"^[\x00-\x20]+|[\x00-\x20]+$" "")]
    (cond (= "" t) (throw (NumberFormatException. "empty String"))
          (re-matches #"[+-]?NaN" t) js/NaN
          (re-matches #"[+-]?Infinity" t) (if (str/starts-with? t "-") js/Number.NEGATIVE_INFINITY js/Number.POSITIVE_INFINITY)
          (re-matches #"[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?[fFdD]?" t) (js/Number (str/replace t #"[fFdD]$" ""))
          :else (throw (NumberFormatException. (for-input s 10))))))

(defn- value-of [parse]
  (fn [x & more] (if (number? x) x (apply parse x more))))

(defn- compare* [a b] (cond (< a b) -1 (> a b) 1 :else 0))

(def Integer
  (strict-statics "java.lang.Integer" (js-obj "parseInt" parse-int
          "valueOf" (value-of parse-int)
          "MAX_VALUE" 2147483647
          "MIN_VALUE" -2147483648
          "toString" (fn ([n] (str n)) ([n radix] (.toString n radix)))
          "toHexString" (fn [n] (unsigned-string n 32 16))
          "toBinaryString" (fn [n] (unsigned-string n 32 2))
          "toOctalString" (fn [n] (unsigned-string n 32 8))
          "compare" compare*
          "max" max "min" min "sum" +)
                  ["SIZE" "BYTES" "TYPE"]))

(def Long
  (strict-statics "java.lang.Long" (js-obj "parseLong" parse-long*
          "valueOf" (value-of parse-long*)
          "toString" (fn ([n] (str n)) ([n radix] (.toString n radix)))
          "toHexString" (fn [n] (unsigned-string n 64 16))
          "toBinaryString" (fn [n] (unsigned-string n 64 2))
          "toOctalString" (fn [n] (unsigned-string n 64 8))
          "compare" compare*
          "max" max "min" min "sum" +)
                  ["MAX_VALUE" "MIN_VALUE" "SIZE" "BYTES" "TYPE"]))

(def Double
  (strict-statics "java.lang.Double" (js-obj "parseDouble" parse-double*
          "valueOf" (value-of parse-double*)
          "isNaN" (fn [x] (js/Number.isNaN x))
          "isFinite" (fn [x] (js/Number.isFinite x))
          "isInfinite" (fn [x] (or (= x js/Number.POSITIVE_INFINITY) (= x js/Number.NEGATIVE_INFINITY)))
          "MAX_VALUE" js/Number.MAX_VALUE
          "MIN_VALUE" js/Number.MIN_VALUE
          "POSITIVE_INFINITY" js/Number.POSITIVE_INFINITY
          "NEGATIVE_INFINITY" js/Number.NEGATIVE_INFINITY
          "NaN" js/NaN
          "compare" compare*
          "max" max "min" min "sum" +)
                  ["MIN_NORMAL" "MAX_EXPONENT" "MIN_EXPONENT" "SIZE" "BYTES" "TYPE"]))

(def Boolean
  (strict-statics "java.lang.Boolean" (js-obj "parseBoolean" (fn [s] (and (some? s) (= "true" (str/lower-case (str s)))))
          "valueOf" (fn [x] (if (boolean? x) x (and (some? x) (= "true" (str/lower-case (str x))))))
          "TRUE" true
          "FALSE" false)
                  ["TYPE"]))

;; ---------------------------------------------------------------------------
;; java.io.File
;; ---------------------------------------------------------------------------

(defn- normalize
  "java.io.UnixFileSystem.normalize: collapse repeated '/', drop a trailing
  '/' (except the root). `.` and `..` are kept, as on the JVM."
  [p]
  (let [p (str/replace p #"/{2,}" "/")]
    (if (and (> (count p) 1) (str/ends-with? p "/")) (subs p 0 (dec (count p))) p)))

(defn- resolve-child [parent child]
  (cond (= "" child) parent
        (str/starts-with? child "/") (if (= "/" parent) child (str parent child))
        (= "/" parent) (str parent child)
        :else (str parent "/" child)))

(def ^:private FileCtor
  (js* "(function(){ return function File(p){ Object.defineProperty(this, 'path', {value: p, enumerable: false}); }; })()"))

(defn- file-path [f] (gobj/get f "path"))

(defn- ->path-string [x]
  (cond (string? x) x
        (real-instance? FileCtor x) (file-path x)
        (instance? js/URL x) (url/fileURLToPath x)
        :else (str x)))

(defn- new-file
  ([p]
   (when (nil? p) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"pathname\" is null")))
   (FileCtor. (normalize (->path-string p))))
  ([parent child]
   (when (nil? child) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"child\" is null")))
   (let [child (normalize (->path-string child))]
     (cond (nil? parent) (FileCtor. child)
           :else (let [ps (->path-string parent)]
                   (if (= "" ps)
                     (FileCtor. (resolve-child "/" child))
                     (FileCtor. (resolve-child (normalize ps) child))))))))

(defn- ^js stat [p]
  (try (fs/statSync p) (catch :default _ nil)))

(defn- abs-path [p]
  (cond (str/starts-with? p "/") p
        (= "" p) (js/process.cwd)
        :else (str (js/process.cwd) "/" p)))

(defn- parent-of [p]
  (let [i (str/last-index-of p "/")]
    (cond (nil? i) nil
          (zero? i) (if (> (count p) 1) "/" nil)
          :else (subs p 0 i))))

(defn- canonical [p]
  ;; realpath of the longest existing prefix, then the remaining segments,
  ;; with . and .. resolved (File.getCanonicalPath)
  (let [r (path/resolve (abs-path p))]
    (loop [head r tail '()]
      (if-let [real (try (fs/realpathSync head) (catch :default _ nil))]
        (if (seq tail) (apply path/join real tail) real)
        (let [parent (path/dirname head)]
          (if (= parent head) r (recur parent (cons (path/basename head) tail))))))))

(defn- file-methods []
  {"getPath" (fn [] (this-as ^js this (file-path this)))
   "toString" (fn [] (this-as ^js this (file-path this)))
   "getName" (fn [] (this-as ^js this (let [p (file-path this) i (str/last-index-of p "/")] (if (nil? i) p (subs p (inc i))))))
   "getParent" (fn [] (this-as ^js this (parent-of (file-path this))))
   "getParentFile" (fn [] (this-as ^js this (some-> (parent-of (file-path this)) new-file)))
   "isAbsolute" (fn [] (this-as ^js this (str/starts-with? (file-path this) "/")))
   "getAbsolutePath" (fn [] (this-as ^js this (abs-path (file-path this))))
   "getAbsoluteFile" (fn [] (this-as ^js this (new-file (abs-path (file-path this)))))
   "getCanonicalPath" (fn [] (this-as ^js this (canonical (file-path this))))
   "getCanonicalFile" (fn [] (this-as ^js this (new-file (canonical (file-path this)))))
   "exists" (fn [] (this-as ^js this (some? (stat (file-path this)))))
   "isFile" (fn [] (this-as ^js this (boolean (some-> (stat (file-path this)) .isFile))))
   "isDirectory" (fn [] (this-as ^js this (boolean (some-> (stat (file-path this)) .isDirectory))))
   "canRead" (fn [] (this-as ^js this (try (fs/accessSync (file-path this) (.-R_OK ^js (.-constants fs))) true (catch :default _ false))))
   "canWrite" (fn [] (this-as ^js this (try (fs/accessSync (file-path this) (.-W_OK ^js (.-constants fs))) true (catch :default _ false))))
   "length" (fn [] (this-as ^js this (if-let [s (stat (file-path this))] (if (.isFile s) (.-size s) 0) 0)))
   "lastModified" (fn [] (this-as ^js this (if-let [s (stat (file-path this))] (js/Math.floor (.-mtimeMs s)) 0)))
   "mkdir" (fn [] (this-as ^js this (try (fs/mkdirSync (file-path this)) true (catch :default _ false))))
   "mkdirs" (fn [] (this-as ^js this (let [p (file-path this)]
                                   (if (stat p) false
                                       (try (fs/mkdirSync p #js {"recursive" true}) true (catch :default _ false))))))
   "delete" (fn [] (this-as ^js this (let [p (file-path this)]
                                   (if-let [s (stat p)]
                                     (try (if (.isDirectory s) (fs/rmdirSync p) (fs/unlinkSync p)) true
                                          (catch :default _ false))
                                     false))))
   "createNewFile" (fn [] (this-as ^js this (try (fs/writeFileSync (file-path this) "" #js {"flag" "wx"}) true
                                             (catch :default ^js e
                                               (if (= "EEXIST" (.-code e)) false
                                                   (throw (IOException. (str (.-message e)))))))))
   "renameTo" (fn [dest] (this-as ^js this (try (fs/renameSync (file-path this) (->path-string dest)) true (catch :default _ false))))
   "list" (fn [] (this-as ^js this (let [p (file-path this)]
                                 (when (some-> (stat p) .isDirectory) (fs/readdirSync p)))))
   "listFiles" (fn [] (this-as ^js this (let [p (file-path this)]
                                      (when (some-> (stat p) .isDirectory)
                                        (.map (fs/readdirSync p) (fn [n] (new-file this n)))))))
   "toPath" (fn [] (throw (UnsupportedOperationException. "java.nio.file.Path is not available on this engine")))
   "equals" (fn [o] (this-as ^js this (and (real-instance? FileCtor o) (= (file-path this) (file-path o)))))
   "hashCode" (fn [] (this-as ^js this (bit-xor (hash (file-path this)) 1234321)))})

(defn- install-file-methods! []
  (let [proto (.-prototype FileCtor)]
    (doseq [[k f] (file-methods)]
      (js/Object.defineProperty proto k #js {"value" f "writable" true "configurable" true "enumerable" false}))))

(extend-type FileCtor
  IEquiv
  (-equiv [a b] (and (real-instance? FileCtor b) (= (file-path a) (file-path b))))
  IHash
  (-hash [a] (hash (file-path a)))
  IPrintWithWriter
  (-pr-writer [a writer _]
    (-write writer (str "#object[java.io.File \"" (file-path a) "\"]"))))

(def File
  ;; `(File. p)` / `(File. parent child)` construct through new-file, which
  ;; normalises; `(instance? File x)` is the prototype chain of FileCtor.
  (let [c (js* "(function(ctor, mk){ var F = function File(a, b){ return arguments.length < 2 ? mk(a) : mk(a, b); }; F.prototype = ctor.prototype; Object.defineProperty(F, Symbol.hasInstance, {value: function(x){ return x instanceof ctor; }}); return F; })(~{}, ~{})"
               FileCtor new-file)]
    (gobj/set c "separator" "/")
    (gobj/set c "separatorChar" "/")
    (gobj/set c "pathSeparator" ":")
    (gobj/set c "pathSeparatorChar" ":")
    (gobj/set c "createTempFile"
              (fn [prefix suffix & [dir]]
                (let [d (if dir (->path-string dir) (os/tmpdir))
                      n (str prefix (.toString (js/Math.floor (* (js/Math.random) 1e16)) 36) (or suffix ".tmp"))
                      p (path/join d n)]
                  (fs/writeFileSync p "")
                  (new-file p))))
    c))

;; ---------------------------------------------------------------------------
;; slurp / spit
;; ---------------------------------------------------------------------------

(defn- charset->node [enc]
  (if (nil? enc) "utf8"
      (case (str/upper-case (str enc))
        ("UTF-8" "UTF8") "utf8"
        ("ISO-8859-1" "LATIN1" "ISO8859_1") "latin1"
        ("US-ASCII" "ASCII") "ascii"
        ("UTF-16LE") "utf16le"
        (throw (IllegalArgumentException. (str "Unsupported charset on this engine: " enc))))))

(defn- io-path
  "The file a slurp/spit/io call reads: a path string, a File, or a file: URL.
  Non-file URLs are refused (the JVM would fetch them; this engine's slurp is
  synchronous)."
  [x]
  (cond (and (string? x) (re-find #"^(?:https?|ftp|jar|file):" x))
        (if (str/starts-with? x "file:")
          (url/fileURLToPath x)
          (throw (UnsupportedOperationException. (str "slurp/spit of a non-file URL is not available on this engine: " x))))
        (instance? js/URL x) (if (= "file:" (.-protocol x))
                               (url/fileURLToPath x)
                               (throw (UnsupportedOperationException. (str "slurp/spit of a non-file URL is not available on this engine: " x))))
        (real-instance? FileCtor x) (file-path x)
        (string? x) x
        :else (throw (IllegalArgumentException. (str "Cannot open <" (pr-str x) "> as a file on this engine.")))))

(defn- fnf [p e]
  (FileNotFoundException.
   (str p " ("
        (case (.-code e)
          "ENOENT" "No such file or directory"
          "EISDIR" "Is a directory"
          "EACCES" "Permission denied"
          (.-message e))
        ")")))

(defn slurp*
  "clojure.core/slurp, synchronous, relative to the working directory."
  [f & {:keys [encoding] :as _opts}]
  (let [p (io-path f)
        enc (charset->node encoding)]
    (try (fs/readFileSync p enc)
         (catch :default ^js e
           (if (.-code e) (throw (fnf (->path-string f) e)) (throw e))))))

(defn spit*
  "clojure.core/spit: (str content) to f, :append true appends."
  [f content & {:keys [append encoding]}]
  (let [p (io-path f)
        enc (charset->node encoding)]
    (try (if append
           (fs/appendFileSync p (str content) enc)
           (fs/writeFileSync p (str content) enc))
         (catch :default ^js e
           (if (.-code e) (throw (fnf (->path-string f) e)) (throw e))))
    nil))

;; ---------------------------------------------------------------------------
;; clojure.java.io (the file-level subset)
;; ---------------------------------------------------------------------------

(defn as-file [x]
  (cond (nil? x) nil
        (real-instance? FileCtor x) x
        (string? x) (new-file x)
        (instance? js/URL x) (if (= "file:" (.-protocol x))
                               (new-file (url/fileURLToPath x))
                               (throw (IllegalArgumentException. (str "Not a file: " x))))
        :else (throw (IllegalArgumentException. (str "Cannot coerce " (pr-str x) " to a file on this engine.")))))

(defn as-relative-path [x]
  (let [^js f (as-file x)]
    (if (.isAbsolute f)
      (throw (IllegalArgumentException. (str f " is not a relative path")))
      (.getPath f))))

(defn io-file
  ([arg] (as-file arg))
  ([parent child] (new-file (as-file parent) (as-relative-path child)))
  ([parent child & more] (reduce io-file (io-file parent child) more)))

(defn make-parents [f & more]
  (when-let [^js parent (.getParentFile ^js (apply io-file f more))]
    (.mkdirs parent)))

(defn delete-file [f & [silently]]
  (or (.delete ^js (io-file f))
      silently
      (throw (IOException. (str "Couldn't delete " f)))))

(defn resource
  "The classpath entry that has `n`, as a file: URL, or nil (jars are not on
  this engine's classpath)."
  ([n] (resource n nil))
  ([n _loader]
   (when-not (str/starts-with? (str n) "/")
    (some (fn [entry]
           (let [p (path/resolve entry n)]
             (when (stat p) (url/pathToFileURL p))))
         (remove str/blank? (cp/split-classpath (cp/get-classpath)))))))

(defn as-url [x]
  (cond (nil? x) nil
        (instance? js/URL x) x
        (real-instance? FileCtor x) (url/pathToFileURL (.getAbsolutePath ^js x))
        :else (js/URL. (str x))))

;; ---------------------------------------------------------------------------
;; System
;; ---------------------------------------------------------------------------

(def ^:private set-props (atom {}))

(defn- os-name []
  (case js/process.platform
    "darwin" "Mac OS X"
    "linux" "Linux"
    "win32" "Windows"
    js/process.platform))

(defn- property [k]
  (if (contains? @set-props k)
    (get @set-props k)
    (case k
      "user.dir" (js/process.cwd)
      "user.home" (os/homedir)
      "user.name" (.-username ^js (os/userInfo))
      "java.io.tmpdir" (let [t (os/tmpdir)] (if (str/ends-with? t "/") t (str t "/")))
      "os.name" (os-name)
      "os.arch" (case js/process.arch "arm64" "aarch64" "x64" "amd64" js/process.arch)
      "line.separator" "\n"
      "file.separator" "/"
      "path.separator" ":"
      nil)))

(defn- print-stream [^js stream]
  (js-obj "println" (fn ([] (.write stream "\n") nil) ([x] (.write stream (str (if (nil? x) "null" x) "\n")) nil))
          "print" (fn [x] (.write stream (str (if (nil? x) "null" x))) nil)
          "flush" (fn [] nil)))

(def System
  (strict-statics "java.lang.System" (js-obj "exit" (fn [code] (js/process.exit code))
          "getenv" (fn ([] (into {} (map (fn [k] [k (gobj/get js/process.env k)])) (js-keys js/process.env)))
                     ([k] (let [v (gobj/get js/process.env k)] (if (undefined? v) nil v))))
          "getProperty" (fn ([k] (property k)) ([k default] (let [v (property k)] (if (nil? v) default v))))
          "setProperty" (fn [k v] (let [old (property k)] (swap! set-props assoc k v) old))
          "clearProperty" (fn [k] (let [old (property k)] (swap! set-props assoc k nil) old))
          "nanoTime" (fn [] (js/Number (js/process.hrtime.bigint)))
          "currentTimeMillis" (fn [] (js/Date.now))
          "lineSeparator" (fn [] "\n")
          "out" (print-stream js/process.stdout)
          "err" (print-stream js/process.stderr))
                  ["in"]))

(defn- blocking-stdio!
  "System/exit is process.exit, which drops stdout/stderr writes still queued
  on a pipe (macOS: 10,880 of 20,000 lines reached `| wc -l`, measured
  2026-09-24). A JVM's System.out is synchronous, so make piped stdio
  synchronous too; TTYs and files already are."
  []
  (doseq [s [js/process.stdout js/process.stderr]]
    (let [^js h (some-> s (gobj/get "_handle"))]
      (when (and h (fn? (gobj/get h "setBlocking")))
        (.setBlocking h true)))))

;; ---------------------------------------------------------------------------
;; format
;; ---------------------------------------------------------------------------

(defn- decimal-digits
  "abs(x) as [digits e]: value = 0.<digits> * 10^e, digits without leading
  zeros, from the shortest round-trip representation (what Java's
  Double.toString and the Formatter start from)."
  [x]
  (let [s (str (js/Math.abs x))
        [mant ex] (str/split s #"e")
        ex (if ex (js/parseInt ex 10) 0)
        [ip fp] (str/split mant #"\.")
        fp (or fp "")
        ds (str ip fp)
        e (+ (count ip) ex)
        lead (count (take-while #(= "0" %) ds))
        ds (subs ds lead)
        e (- e lead)]
    (if (= "" ds) ["0" 1] [ds e])))

(defn- round-half-up
  "digits/e rounded HALF_UP to `prec` fraction digits -> [int-part frac-part]."
  [[ds e] prec]
  (let [keep (+ e prec)]
    (if (neg? keep)
      ["0" (apply str (repeat prec "0"))]
      (let [padded (if (> keep (count ds)) (str ds (apply str (repeat (- keep (count ds)) "0"))) ds)
            head (subs padded 0 keep)
            up? (and (< keep (count padded)) (>= (js/parseInt (nth padded keep) 10) 5))
            ;; n = round(value * 10^prec)
            n (+ (js/BigInt (if (= "" head) "0" head)) (js/BigInt (if up? 1 0)))
            s (.toString n)
            s (if (<= (count s) prec) (str (apply str (repeat (- (inc prec) (count s)) "0")) s) s)
            cut (- (count s) prec)]
        [(subs s 0 cut) (subs s cut)]))))

(defn- group3 [ip]
  (str/replace ip #"\B(?=(\d{3})+(?!\d))" ","))

(defn- fmt-ex [s] (IllegalFormatConversionException. s))

(defn- pad [s width left-flag zero-flag sign-len]
  (let [n (count s)]
    (if (or (nil? width) (>= n width)) s
        (cond left-flag (str s (apply str (repeat (- width n) " ")))
              zero-flag (str (subs s 0 sign-len) (apply str (repeat (- width n) "0")) (subs s sign-len))
              :else (str (apply str (repeat (- width n) " ")) s)))))

(defn- signed [neg? body flags]
  (cond neg? (if (str/includes? flags "(") (str "(" body ")") (str "-" body))
        (str/includes? flags "+") (str "+" body)
        (str/includes? flags " ") (str " " body)
        :else body))

(defn- java-type-name [x]
  (cond (nil? x) "null"
        (string? x) "java.lang.String"
        (boolean? x) "java.lang.Boolean"
        (keyword? x) "clojure.lang.Keyword"
        (number? x) (if (js/Number.isInteger x) "java.lang.Long" "java.lang.Double")
        :else "java.lang.Object"))

(defn- integral? [x] (or (and (number? x) (js/Number.isInteger x)) (= "bigint" (goog/typeOf x))))

(defn- fmt-one [conv flags width prec arg]
  (let [lc (str/lower-case conv)
        upper? (not= lc conv)
        left-flag (str/includes? flags "-")
        zero-flag (str/includes? flags "0")
        out (case lc
              "s" (let [s (if (nil? arg) "null" (str arg))
                        s (if prec (subs s 0 (min prec (count s))) s)]
                    (pad s width left-flag false 0))
              "b" (let [s (str (if (boolean? arg) arg (some? arg)))
                        s (if prec (subs s 0 (min prec (count s))) s)]
                    (pad s width left-flag false 0))
              "c" (let [s (cond (nil? arg) "null"
                                (and (string? arg) (= 1 (count arg))) arg
                                (integral? arg) (js/String.fromCodePoint arg)
                                :else (throw (fmt-ex (str "c != " (java-type-name arg)))))]
                    (pad s width left-flag false 0))
              "d" (cond (nil? arg) (pad "null" width left-flag false 0)
                        (integral? arg)
                        (let [neg (neg? arg)
                              body (str (if neg (- arg) arg))
                              body (if (str/includes? flags ",") (group3 body) body)
                              s (signed neg body flags)]
                          (pad s width left-flag zero-flag (if (or neg (re-find #"[+ ]" flags)) 1 0)))
                        :else (throw (fmt-ex (str "d != " (java-type-name arg)))))
              ("x" "o") (cond (nil? arg) (pad "null" width left-flag false 0)
                              (integral? arg)
                              (let [radix (if (= "x" lc) 16 8)
                                    body (if (neg? arg) (unsigned-string arg 64 radix) (.toString (js/BigInt arg) radix))
                                    body (if (str/includes? flags "#") (str (if (= 16 radix) "0x" "0") body) body)]
                                (pad body width left-flag zero-flag 0))
                              :else (throw (fmt-ex (str lc " != " (java-type-name arg)))))
              ("f" "e") (cond (nil? arg) (pad "null" width left-flag false 0)
                              (not (number? arg)) (throw (fmt-ex (str lc " != " (java-type-name arg))))
                              (js/Number.isNaN arg) (pad "NaN" width left-flag false 0)
                              (not (js/Number.isFinite arg))
                              (pad (signed (neg? arg) "Infinity" flags) width left-flag false 0)
                              :else
                              (let [prec (or prec 6)
                                    neg (or (neg? arg) (and (zero? arg) (neg? (/ 1 arg))))
                                    [ds e] (decimal-digits arg)
                                    body (if (= "f" lc)
                                           (let [[ip fp] (round-half-up [ds e] prec)
                                                 ip (if (str/includes? flags ",") (group3 ip) ip)]
                                             (if (pos? prec) (str ip "." fp) ip))
                                           ;; %e: one digit before the point
                                           (let [zero-val (= ds "0")
                                                 [ip fp] (round-half-up [ds 1] prec)
                                                 carry (> (count ip) 1)
                                                 ip (if carry (subs ip 0 1) ip)
                                                 fp (if carry (subs (str "0" fp) 0 prec) fp)
                                                 x (if zero-val 0 (+ (dec e) (if carry 1 0)))]
                                             (str ip (when (pos? prec) (str "." fp))
                                                  "e" (if (neg? x) "-" "+")
                                                  (let [a (str (js/Math.abs x))] (if (< (count a) 2) (str "0" a) a)))))
                                    s (signed neg body flags)]
                                (pad s width left-flag zero-flag (if (or neg (re-find #"[+ ]" flags)) 1 0))))
              (throw (UnknownFormatConversionException. (str "Conversion = '" conv "'"))))]
    (if upper? (str/upper-case out) out)))

(defn format*
  "clojure.core/format: java.util.Formatter's %s %S %b %c %d %o %x %X %f %e
  %E %n %% with flags - + space 0 , ( # and width/precision, and n$ indexes.
  %f/%e round HALF_UP from the shortest decimal representation, as Java does
  ((format \"%.2f\" 1.005) => \"1.01\", where JS toFixed gives \"1.00\")."
  [fmt & args]
  (let [args (vec args)
        re #"%(\d+\$)?([-#+ 0,(<]*)(\d+)?(\.\d+)?([a-zA-Z%])"
        sb (array)
        idx (volatile! 0)]
    (loop [pos 0]
      (let [m (.exec (js/RegExp. (.-source re) "g") (subs fmt pos))]
        (if (nil? m)
          (.push sb (subs fmt pos))
          (let [start (+ pos (.-index m))
                [whole argi flags width prec conv] (vec m)]
            (.push sb (subs fmt pos start))
            (case conv
              "%" (.push sb (pad "%" (some-> width (js/parseInt 10)) (str/includes? (or flags "") "-") false 0))
              "n" (.push sb "\n")
              (let [i (if argi (dec (js/parseInt argi 10)) (let [i @idx] (vswap! idx inc) i))]
                (when (>= i (count args))
                  (throw (MissingFormatArgumentException. (str "Format specifier '" whole "'"))))
                (.push sb (fmt-one conv (or flags "") (some-> width (js/parseInt 10))
                                   (some-> prec (subs 1) (js/parseInt 10)) (nth args i)))))
            (recur (+ start (count whole)))))))
    (.join sb "")))

;; ---------------------------------------------------------------------------
;; install
;; ---------------------------------------------------------------------------

(def ^:private installed (atom false))

(defn install! []
  (when (compare-and-set! installed false true)
    (install-throwable-methods!)
    (install-file-methods!)
    (blocking-stdio!)))

(def exception-classes
  {"Throwable" Throwable
   "Exception" Exception
   "RuntimeException" RuntimeException
   "IllegalArgumentException" IllegalArgumentException
   "IllegalStateException" IllegalStateException
   "NumberFormatException" NumberFormatException
   "UnsupportedOperationException" UnsupportedOperationException
   "InterruptedException" InterruptedException})

(defn classes
  "The sci :classes entries: java.lang names both bare (auto-imported on the
  JVM) and fully qualified; others fully qualified only (they need :import)."
  []
  (merge
   (into {} (mapcat (fn [[n c]] [[(symbol n) c] [(symbol (str "java.lang." n)) c]]))
         (assoc exception-classes
                "System" System "Integer" Integer "Long" Long "Double" Double "Boolean" Boolean))
   {'java.io.File File
    'java.io.IOException IOException
    'java.io.FileNotFoundException FileNotFoundException
    'java.io.UncheckedIOException UncheckedIOException
    'java.util.NoSuchElementException NoSuchElementException
    'java.util.concurrent.TimeoutException TimeoutException
    'java.util.IllegalFormatException IllegalFormatException
    'java.util.MissingFormatArgumentException MissingFormatArgumentException
    'java.util.UnknownFormatConversionException UnknownFormatConversionException
    'java.util.IllegalFormatConversionException IllegalFormatConversionException
    'clojure.lang.ExceptionInfo ExceptionInfo
    'cljs.core.ExceptionInfo ExceptionInfo}))
