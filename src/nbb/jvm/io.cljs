(ns nbb.jvm.io
  "JVM-compatibility SCAFFOLDING, round 3: java.io streams, readers and
  writers over files, synchronously (see nbb.jvm for why this exists; it is
  not a target for new code, which uses kotoba.io / kotoba.fs).

  Byte streams: FileInputStream / FileOutputStream (fs.openSync / readSync /
  writeSync at an explicit position, so skip past EOF, available and append
  behave as the JDK's), BufferedInputStream (mark / reset) and
  BufferedOutputStream (the JDK's 8192-byte buffer algorithm: bytes reach
  the file on flush / close, or when the buffer fills).
  Char streams: InputStreamReader / FileReader, StringReader,
  BufferedReader (readLine on \\n, \\r and \\r\\n with the JDK's skipLF rule,
  nil at EOF), PushbackReader (also a tools.reader reader, so
  `(edn/read (PushbackReader. (io/reader f)))` reads form after form),
  OutputStreamWriter / FileWriter, BufferedWriter (the JDK's 8192-char
  buffer), StringWriter.
  clojure.java.io: reader writer input-stream output-stream copy with
  :encoding / :append / :buffer-size, the IOFactory dispatch of Clojure 1.12
  (a Reader is wrapped in a BufferedReader, a BufferedReader is itself, a
  String is a file path, bytes are a ByteArrayInputStream); copy's do-copy
  table (streams / readers / files / strings / byte[] / char[]).
  clojure.core: line-seq; with-open (the engine's own expansion could not
  resolve its recursive call; see with-open*); slurp of a Reader /
  InputStream (closes it, as the JVM's slurp does); spit to a Writer /
  OutputStream.
  The exceptions and messages are the JDK's: FileNotFoundException \"p (No
  such file or directory)\" / \"(Is a directory)\", IOException \"Stream
  Closed\" (file streams) vs \"Stream closed\" (readers / writers). Checked
  against JDK 21 / Clojure 1.12 (2026-09-24).

  Documented deviations: an InputStreamReader decodes everything left in its
  stream at its first read (the JDK decodes in 8192-byte steps; only
  observable if the underlying stream is also read directly); an
  OutputStreamWriter hands bytes on in 8192-byte chunks that may split a
  multi-byte character (the JDK splits on character boundaries; only
  observable by reading the file before flush). Deliberately NOT here:
  System/in, RandomAccessFile, channels, java.nio ByteBuffer, PrintWriter /
  PrintStream, ObjectInput/OutputStream, anything asynchronous, and
  `(binding [*out* w] ...)` into these writers (print on this engine goes
  through *print-fn*, not *out*)."
  (:require ["node:fs" :as fs]
            [cljs.tools.reader.reader-types :as rt]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]))

(def EOFException (jvm/defclass "java.io.EOFException" jvm/IOException))

(defn- closed! [msg] (throw (jvm/IOException. msg)))

(defn- check-off-len [len off n]
  (when (or (neg? off) (neg? n) (> (+ off n) len))
    (throw (jb/IndexOutOfBoundsException. (str "Range [" off ", " off " + " n ") out of bounds for length " len)))))

(defn- open-error
  "The JDK's FileNotFoundException for opening p."
  [p ^js e]
  (jvm/fnf p e))

;; ---------------------------------------------------------------------------
;; byte streams
;; ---------------------------------------------------------------------------

(deftype FileInputStream [fd ^:mutable pos ^:mutable closed]
  Object
  (read [this] (let [b (js/Int8Array. 1)]
                 (if (= -1 (.read this b 0 1)) -1 (bit-and (aget b 0) 0xff))))
  (read [this b] (.read this b 0 (.-length b)))
  (read [_ b off n]
    (when closed (closed! "Stream Closed"))
    (check-off-len (.-length b) off n)
    (if (zero? n) 0
        (let [k (fs/readSync fd b off n pos)]
          (if (zero? k) -1 (do (set! pos (+ pos k)) k)))))
  (readAllBytes [this] (.readNBytes this js/Number.MAX_SAFE_INTEGER))
  (readNBytes [_ n]
    (when closed (closed! "Stream Closed"))
    (let [out (jb/new-baos) buf (js/Int8Array. 8192)]
      (loop [left n]
        (when (pos? left)
          (let [k (fs/readSync fd buf 0 (min left 8192) pos)]
            (when (pos? k)
              (set! pos (+ pos k))
              (.write out buf 0 k)
              (recur (- left k))))))
      (.toByteArray out)))
  (skip [_ n] (when closed (closed! "Stream Closed")) (set! pos (+ pos n)) n)
  (available [_] (when closed (closed! "Stream Closed"))
    (max 0 (- (.-size (fs/fstatSync fd)) pos)))
  (transferTo [this out] (let [b (.readAllBytes this)] (.write out b) (.-length b)))
  (markSupported [_] false)
  (close [_] (when-not closed (set! closed true) (fs/closeSync fd)) nil))

(defn- open-read-fd [x]
  (when (nil? x) (throw (js/TypeError. "Cannot invoke \"java.io.File.getPath()\" because \"file\" is null")))
  (let [p (jvm/->path-string x)
        s (jvm/stat p)]
    (cond (nil? s) (throw (open-error p #js {:code "ENOENT"}))
          (.isDirectory s) (throw (open-error p #js {:code "EISDIR"}))
          :else (try (fs/openSync p "r") (catch :default e (throw (open-error p e)))))))

(defn new-fis [x] (FileInputStream. (open-read-fd x) 0 false))

(deftype FileOutputStream [fd ^:mutable closed]
  Object
  (write [this x] (if (number? x)
                    (.write this (.of js/Int8Array x) 0 1)
                    (.write this x 0 (.-length (jb/u8 x)))))
  (write [_ b off n]
    (when closed (closed! "Stream Closed"))
    (let [v (jb/u8 b)]
      (check-off-len (.-length v) off n)
      (fs/writeSync fd v off n)
      nil))
  (flush [_] nil)
  (close [_] (when-not closed (set! closed true) (fs/closeSync fd)) nil))

(defn new-fos
  ([x] (new-fos x false))
  ([x append]
   (when (nil? x) (throw (js/TypeError. "Cannot invoke \"java.io.File.getPath()\" because \"file\" is null")))
   (let [p (jvm/->path-string x)
         s (jvm/stat p)]
     (when (and s (.isDirectory s)) (throw (open-error p #js {:code "EISDIR"})))
     (FileOutputStream. (try (fs/openSync p (if append "a" "w")) (catch :default e (throw (open-error p e)))) false))))

(deftype BufferedInputStream [^js in ^:mutable replay ^:mutable rpos ^:mutable marked ^:mutable limit ^:mutable closed]
  ;; replay: bytes read since mark (a JS array), served again after reset
  Object
  (read [this] (let [b (js/Int8Array. 1)]
                 (if (= -1 (.read this b 0 1)) -1 (bit-and (aget b 0) 0xff))))
  (read [this b] (.read this b 0 (.-length b)))
  (read [_ b off n]
    (when closed (closed! "Stream closed"))
    (check-off-len (.-length b) off n)
    (if (zero? n) 0
        (let [from-replay (when (and replay (< rpos (alength replay)))
                            (let [k (min n (- (alength replay) rpos))]
                              (dotimes [i k] (aset b (+ off i) (aget replay (+ rpos i))))
                              (set! rpos (+ rpos k))
                              k))]
          (if from-replay
            from-replay
            (let [k (.read in b off n)]
              (when (and marked (pos? k))
                (if (> (+ (alength replay) k) limit)
                  (do (set! marked false) (set! replay nil))
                  (do (dotimes [i k] (.push replay (aget b (+ off i))))
                      (set! rpos (alength replay)))))
              k)))))
  (readAllBytes [this] (let [out (jb/new-baos) buf (js/Int8Array. 8192)]
                         (loop [] (let [k (.read this buf 0 8192)] (when (pos? k) (.write out buf 0 k) (recur))))
                         (.toByteArray out)))
  (readNBytes [this n] (let [buf (js/Int8Array. n) k (.read this buf 0 n)] (if (pos? k) (.slice buf 0 k) (js/Int8Array. 0))))
  (skip [this n] (let [b (js/Int8Array. (max 0 n)) k (.read this b 0 (max 0 n))] (max 0 k)))
  (available [_] (+ (if replay (- (alength replay) rpos) 0) (.available in)))
  (markSupported [_] true)
  (mark [_ lim] (set! marked true) (set! limit lim)
    (set! replay (if (and replay (< rpos (alength replay))) (.slice replay rpos) #js []))
    (set! rpos 0) nil)
  (reset [_] (if (and marked replay) (set! rpos 0) (closed! "Resetting to invalid mark")) nil)
  (transferTo [this out] (let [b (.readAllBytes this)] (.write out b) (.-length b)))
  (close [_] (when-not closed (set! closed true) (.close in)) nil))

(defn new-bis ([in] (BufferedInputStream. in nil 0 false 0 false)) ([in _size] (new-bis in)))

(deftype BufferedOutputStream [^js out buf ^:mutable cnt ^:mutable closed]
  ;; java.io.BufferedOutputStream's algorithm
  Object
  (flushBuffer [_] (when (pos? cnt) (.write out buf 0 cnt) (set! cnt 0)))
  (write [this x] (if (number? x)
                    (do (when (>= cnt (.-length buf)) (.flushBuffer this))
                        (aset buf cnt x) (set! cnt (inc cnt)) nil)
                    (.write this x 0 (.-length (jb/u8 x)))))
  (write [this b off n]
    (let [v (jb/u8 b)]
      (check-off-len (.-length v) off n)
      (cond (>= n (.-length buf)) (do (.flushBuffer this) (.write out b off n))
            :else (do (when (> n (- (.-length buf) cnt)) (.flushBuffer this))
                      (.set buf (js/Int8Array. (.-buffer v) (+ (.-byteOffset v) off) n) cnt)
                      (set! cnt (+ cnt n))))
      nil))
  (flush [this] (.flushBuffer this) (.flush out) nil)
  (close [this] (when-not closed (set! closed true) (try (.flush this) (finally (.close out)))) nil))

(defn new-bos ([out] (new-bos out 8192))
  ([out size] (when (<= size 0) (throw (jvm/IllegalArgumentException. "Buffer size <= 0")))
   (BufferedOutputStream. out (js/Int8Array. size) 0 false)))

(defn input-stream? [x]
  (or (instance? FileInputStream x) (instance? BufferedInputStream x) (instance? jb/ByteArrayInputStream x)))

(defn output-stream? [x]
  (or (instance? FileOutputStream x) (instance? BufferedOutputStream x) (instance? jb/ByteArrayOutputStream x)))

;; ---------------------------------------------------------------------------
;; readers: every reader here is a char source with read() / read(cbuf off
;; len) / ready / close; a char[] is a JS array of one-unit strings
;; ---------------------------------------------------------------------------

(defn- read-into
  "read(cbuf, off, len) for a reader whose remaining text is (subs s pos)."
  [s pos cbuf off n]
  (let [k (min n (- (count s) pos))]
    (dotimes [i k] (aset cbuf (+ off i) (.charAt s (+ pos i))))
    k))

(deftype StringReader [s ^:mutable pos ^:mutable mark-pos ^:mutable closed]
  Object
  (read [_] (when closed (closed! "Stream closed"))
    (if (< pos (count s)) (let [c (.charCodeAt s pos)] (set! pos (inc pos)) c) -1))
  (read [this cbuf] (.read this cbuf 0 (alength cbuf)))
  (read [_ cbuf off n] (when closed (closed! "Stream closed"))
    (check-off-len (alength cbuf) off n)
    (cond (zero? n) 0
          (>= pos (count s)) -1
          :else (let [k (read-into s pos cbuf off n)] (set! pos (+ pos k)) k)))
  (ready [_] (when closed (closed! "Stream closed")) true)
  (skip [_ n] (when closed (closed! "Stream closed"))
    (let [k (max (- pos) (min n (- (count s) pos)))] (set! pos (+ pos k)) k))
  (markSupported [_] true)
  (mark [_ _] (when closed (closed! "Stream closed")) (set! mark-pos pos) nil)
  (reset [_] (when closed (closed! "Stream closed")) (set! pos mark-pos) nil)
  (close [_] (set! closed true) nil))

(defn new-string-reader [s]
  (when (nil? s) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"s\" is null")))
  (StringReader. (str s) 0 0 false))

(deftype InputStreamReader [^js in cs ^:mutable text ^:mutable pos ^:mutable closed]
  Object
  (fill [_] (when (nil? text) (set! text (jb/decode (.readAllBytes in) cs))))
  (read [this] (when closed (closed! "Stream closed"))
    (.fill this)
    (if (< pos (count text)) (let [c (.charCodeAt text pos)] (set! pos (inc pos)) c) -1))
  (read [this cbuf] (.read this cbuf 0 (alength cbuf)))
  (read [this cbuf off n] (when closed (closed! "Stream closed"))
    (check-off-len (alength cbuf) off n)
    (.fill this)
    (cond (zero? n) 0
          (>= pos (count text)) -1
          :else (let [k (read-into text pos cbuf off n)] (set! pos (+ pos k)) k)))
  (ready [this] (when closed (closed! "Stream closed"))
    (if (some? text) (< pos (count text)) (pos? (.available in))))
  (skip [this n] (.fill this) (let [k (max 0 (min n (- (count text) pos)))] (set! pos (+ pos k)) k))
  (markSupported [_] false)
  (getEncoding [_] (when-not closed (.name cs)))
  (close [_] (when-not closed (set! closed true) (.close in)) nil))

(defn new-isr
  ([in] (new-isr in jb/UTF_8))
  ([in cs] (when (nil? in) (throw (js/TypeError. "Cannot invoke \"Object.getClass()\" because \"in\" is null")))
   (InputStreamReader. in (jb/->charset cs) nil 0 false)))

(defn new-file-reader
  ([f] (new-isr (new-fis f)))
  ([f cs] (new-isr (new-fis f) cs)))

(deftype BufferedReader [^js in ^:mutable skip-lf ^:mutable peeked ^:mutable closed]
  ;; peeked: one char code read ahead of the caller (-2 = none); the JDK's
  ;; skipLF: after a line ended at \r, a following \n is not a new line
  Object
  (next [_] (if (not= peeked -2) (let [c peeked] (set! peeked -2) c) (.read in)))
  (peek [this] (when (= peeked -2) (set! peeked (.read in))) peeked)
  (read [this]
    (when closed (closed! "Stream closed"))
    (when skip-lf (set! skip-lf false) (when (= 10 (.peek this)) (.next this)))
    (.next this))
  (read [this cbuf] (.read this cbuf 0 (alength cbuf)))
  (read [this cbuf off n]
    (when closed (closed! "Stream closed"))
    (check-off-len (alength cbuf) off n)
    (if (zero? n) 0
        (loop [i 0]
          (if (< i n)
            (let [c (.read this)]
              (if (= -1 c)
                (if (zero? i) -1 i)
                (do (aset cbuf (+ off i) (js/String.fromCharCode c)) (recur (inc i)))))
            i))))
  (readLine [this]
    (when closed (closed! "Stream closed"))
    (when skip-lf (set! skip-lf false) (when (= 10 (.peek this)) (.next this)))
    (let [sb #js []]
      (loop [any false]
        (let [c (.next this)]
          (cond (= -1 c) (when any (.join sb ""))
                (= 10 c) (.join sb "")
                (= 13 c) (do (set! skip-lf true) (.join sb ""))
                :else (do (.push sb (js/String.fromCharCode c)) (recur true)))))))
  (ready [this]
    (when closed (closed! "Stream closed"))
    (or (not= peeked -2) (.ready in)))
  (skip [this n] (loop [k 0] (if (and (< k n) (not= -1 (.read this))) (recur (inc k)) k)))
  (markSupported [_] false)
  (close [_] (when-not closed (set! closed true) (.close in)) nil))

(defn new-buffered-reader
  ([in] (when (nil? in) (throw (js/TypeError. "Cannot invoke \"Object.getClass()\" because \"in\" is null")))
   (BufferedReader. in false -2 false))
  ([in size] (when (<= size 0) (throw (jvm/IllegalArgumentException. "Buffer size <= 0")))
   (new-buffered-reader in)))

(deftype PushbackReader [^js in buf size ^:mutable closed]
  ;; buf: pushed-back char codes, last pushed first out
  Object
  (read [_] (when closed (closed! "Stream closed"))
    (if (pos? (alength buf)) (.pop buf) (.read in)))
  (read [this cbuf] (.read this cbuf 0 (alength cbuf)))
  (read [this cbuf off n]
    (when closed (closed! "Stream closed"))
    (check-off-len (alength cbuf) off n)
    (if (zero? n) 0
        (loop [i 0]
          (if (< i n)
            (let [c (.read this)]
              (if (= -1 c) (if (zero? i) -1 i)
                  (do (aset cbuf (+ off i) (js/String.fromCharCode c)) (recur (inc i)))))
            i))))
  (unread [this c]
    (when closed (closed! "Stream closed"))
    (cond (array? c) (doseq [ch (reverse (array-seq c))] (.unread this ch))
          :else (let [code (if (string? c) (.charCodeAt c 0) c)]
                  (when (>= (alength buf) size) (closed! "Pushback buffer overflow"))
                  (.push buf code)))
    nil)
  (ready [_] (when closed (closed! "Stream closed")) (or (pos? (alength buf)) (.ready in)))
  (markSupported [_] false)
  (close [_] (when-not closed (set! closed true) (.close in)) nil)
  rt/Reader
  (read-char [this] (let [c (.read this)] (when-not (= -1 c) (js/String.fromCharCode c))))
  (peek-char [this] (let [c (.read this)] (when-not (= -1 c) (.push buf c) (js/String.fromCharCode c))))
  rt/IPushbackReader
  (unread [_ ch] (when (some? ch) (.push buf (.charCodeAt ch 0)))))

(defn new-pushback-reader
  ([in] (new-pushback-reader in 1))
  ([in size]
   (when (<= size 0) (throw (jvm/IllegalArgumentException. "size <= 0")))
   (PushbackReader. in #js [] size false)))

(defn reader? [x]
  (or (instance? StringReader x) (instance? InputStreamReader x)
      (instance? BufferedReader x) (instance? PushbackReader x)))

;; ---------------------------------------------------------------------------
;; writers
;; ---------------------------------------------------------------------------

(defn- write-arg->string
  "Writer.write's arguments as the string they write: (int c), (String),
  (char[]), (String off len), (char[] off len)."
  ([x] (cond (number? x) (js/String.fromCharCode (bit-and x 0xFFFF))
             (string? x) x
             (array? x) (.join x "")
             (nil? x) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"str\" is null"))
             :else (str x)))
  ([x off n]
   (let [len (if (array? x) (alength x) (count x))]
     (check-off-len len off n)
     (if (array? x) (.join (.slice x off (+ off n)) "") (subs x off (+ off n))))))

(defn- append-arg
  ([x] (if (nil? x) "null" (str x)))
  ([x start end] (let [s (if (nil? x) "null" (str x))]
                   (when (or (neg? start) (> start end) (> end (count s)))
                     (throw (jb/StringIndexOutOfBoundsException. (str "begin " start ", end " end ", length " (count s)))))
                   (subs s start end))))

(deftype StringWriter [^:mutable sb]
  Object
  (write [this x] (set! sb (str sb (write-arg->string x))) nil)
  (write [this x off n] (set! sb (str sb (write-arg->string x off n))) nil)
  (append [this x] (set! sb (str sb (append-arg x))) this)
  (append [this x start end] (set! sb (str sb (append-arg x start end))) this)
  (toString [_] sb)
  (getBuffer [_] sb)
  (flush [_] nil)
  (close [_] nil))

(deftype OutputStreamWriter [^js out cs ^:mutable bytes ^:mutable cnt ^:mutable pending ^:mutable closed]
  ;; bytes/cnt: encoded bytes not yet handed on (8192 at a time, like the
  ;; JDK's StreamEncoder); pending: a high surrogate waiting for its low half
  Object
  (put [this s]
    (when closed (closed! "Stream closed"))
    (let [s (str pending s)
          last-unit (when (pos? (count s)) (.charCodeAt s (dec (count s))))
          [s p] (if (and last-unit (<= 0xD800 last-unit 0xDBFF)) [(subs s 0 (dec (count s))) (.charAt s (dec (count s)))] [s ""])
          enc (jb/encode s cs)]
      (set! pending p)
      (loop [i 0]
        (when (< i (.-length enc))
          (let [k (min (- 8192 cnt) (- (.-length enc) i))]
            (.set bytes (.subarray enc i (+ i k)) cnt)
            (set! cnt (+ cnt k))
            (when (= cnt 8192) (.write out bytes 0 cnt) (set! cnt 0))
            (recur (+ i k)))))
      nil))
  (write [this x] (.put this (write-arg->string x)))
  (write [this x off n] (.put this (write-arg->string x off n)))
  (append [this x] (.put this (append-arg x)) this)
  (append [this x start end] (.put this (append-arg x start end)) this)
  (flushBuffer [_] (when (pos? cnt) (.write out bytes 0 cnt) (set! cnt 0)))
  (flush [this] (when closed (closed! "Stream closed")) (.flushBuffer this) (.flush out) nil)
  (getEncoding [_] (when-not closed (.name cs)))
  (close [this]
    (when-not closed
      (when (seq pending) (let [p pending] (set! pending "") (.put this p)))
      (.flushBuffer this)
      (set! closed true)
      (.close out))
    nil))

(defn new-osw
  ([out] (new-osw out jb/UTF_8))
  ([out cs] (when (nil? out) (throw (js/TypeError. "Cannot invoke \"Object.getClass()\" because \"out\" is null")))
   (OutputStreamWriter. out (jb/->charset cs) (js/Int8Array. 8192) 0 "" false)))

(defn new-file-writer
  ([f] (new-osw (new-fos f false)))
  ([f a] (if (boolean? a) (new-osw (new-fos f a)) (new-osw (new-fos f false) a)))
  ([f cs append] (new-osw (new-fos f append) cs)))

(deftype BufferedWriter [^js out cb ^:mutable next-char ^:mutable closed]
  ;; java.io.BufferedWriter's algorithm: cb holds up to (alength cb) chars
  Object
  (flushBuffer [_]
    (when closed (closed! "Stream closed"))
    (when (pos? next-char) (.write out (.join (.slice cb 0 next-char) "")) (set! next-char 0)))
  (put [this s]
    (when closed (closed! "Stream closed"))
    (let [n-chars (alength cb) t (count s)]
      (loop [off 0]
        (when (< off t)
          (let [d (min (- n-chars next-char) (- t off))]
            (dotimes [i d] (aset cb (+ next-char i) (.charAt s (+ off i))))
            (set! next-char (+ next-char d))
            (when (>= next-char n-chars) (.flushBuffer this))
            (recur (+ off d))))))
    nil)
  (write [this x] (if (and (array? x) (>= (alength x) (alength cb)))
                    (do (.flushBuffer this) (.write out (write-arg->string x)))
                    (.put this (write-arg->string x)))
    nil)
  (write [this x off n] (if (and (array? x) (>= n (alength cb)))
                          (do (.flushBuffer this) (.write out (write-arg->string x off n)))
                          (.put this (write-arg->string x off n)))
    nil)
  (newLine [this] (.put this "\n"))
  (append [this x] (.put this (append-arg x)) this)
  (append [this x start end] (.put this (append-arg x start end)) this)
  (flush [this] (.flushBuffer this) (.flush out) nil)
  (close [this] (when-not closed (try (.flushBuffer this) (finally (set! closed true) (.close out)))) nil))

(defn new-buffered-writer
  ([out] (new-buffered-writer out 8192))
  ([out size] (when (<= size 0) (throw (jvm/IllegalArgumentException. "Buffer size <= 0")))
   (BufferedWriter. out (js/Array. size) 0 false)))

(defn writer? [x]
  (or (instance? StringWriter x) (instance? OutputStreamWriter x) (instance? BufferedWriter x)))

;; ---------------------------------------------------------------------------
;; clojure.java.io
;; ---------------------------------------------------------------------------

(defn- opts-map [opts] (apply hash-map opts))
(defn- encoding [o] (or (:encoding o) "UTF-8"))
(defn- buffer-size [o] (or (:buffer-size o) 1024))

(defn- file-like? [x]
  (or (string? x) (instance? jvm/File x) (instance? js/URL x)))

(defn- as-path [x]
  (cond (string? x) (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" x)
                      (if (.startsWith x "file:") (jvm/->path-string (js/URL. x))
                          (throw (jvm/UnsupportedOperationException. (str "Opening a non-file URL is not available on this engine: " x))))
                      x)
        (instance? js/URL x) (if (= "file:" (.-protocol x)) (jvm/->path-string x)
                                 (throw (jvm/UnsupportedOperationException. (str "Opening a non-file URL is not available on this engine: " x))))
        :else x))

(defn input-stream [x & opts]
  (cond (instance? BufferedInputStream x) x
        (input-stream? x) (new-bis x)
        (jb/byte-array? x) (new-bis (jb/new-bais x))
        (file-like? x) (new-bis (new-fis (as-path x)))
        :else (throw (jvm/IllegalArgumentException. (str "Cannot open <" (pr-str x) "> as an InputStream.")))))

(defn output-stream [x & opts]
  (let [o (opts-map opts)]
    (cond (instance? BufferedOutputStream x) x
          (output-stream? x) (new-bos x)
          (file-like? x) (new-bos (new-fos (as-path x) (boolean (:append o))))
          :else (throw (jvm/IllegalArgumentException. (str "Cannot open <" (pr-str x) "> as an OutputStream."))))))

(defn reader [x & opts]
  (let [o (opts-map opts)]
    (cond (instance? BufferedReader x) x
          (reader? x) (new-buffered-reader x)
          (input-stream? x) (new-buffered-reader (new-isr x (encoding o)))
          (jb/byte-array? x) (new-buffered-reader (new-isr (jb/new-bais x) (encoding o)))
          (array? x) (new-buffered-reader (new-string-reader (.join x "")))
          (file-like? x) (new-buffered-reader (new-isr (new-fis (as-path x)) (encoding o)))
          :else (throw (jvm/IllegalArgumentException. (str "Cannot open <" (pr-str x) "> as a Reader."))))))

(defn writer [x & opts]
  (let [o (opts-map opts)]
    (cond (instance? BufferedWriter x) x
          (writer? x) (new-buffered-writer x)
          (output-stream? x) (new-buffered-writer (new-osw x (encoding o)))
          (file-like? x) (new-buffered-writer (new-osw (new-fos (as-path x) (boolean (:append o))) (encoding o)))
          :else (throw (jvm/IllegalArgumentException. (str "Cannot open <" (pr-str x) "> as a Writer."))))))

(defn- copy-stream [in out size]
  (let [buf (js/Int8Array. size)]
    (loop [] (let [k (.read in buf 0 size)] (when (pos? k) (.write out buf 0 k) (recur))))))

(defn- copy-reader [in out size]
  (let [buf (js/Array. size)]
    (loop [] (let [k (.read in buf 0 size)] (when (pos? k) (.write out buf 0 k) (recur))))))

(defn- kind [x]
  (cond (input-stream? x) :input-stream
        (output-stream? x) :output-stream
        (reader? x) :reader
        (writer? x) :writer
        (instance? jvm/File x) :file
        (string? x) :string
        (jb/byte-array? x) :bytes
        (array? x) :chars
        :else nil))

(defn copy
  "clojure.java.io/copy: the do-copy table of Clojure 1.12. Streams it is
  given are not closed; files it opens are."
  [input output & opts]
  (let [o (opts-map opts)
        size (buffer-size o)
        ik (kind input) ok (kind output)]
    (condp = [ik ok]
      [:input-stream :output-stream] (copy-stream input output size)
      [:input-stream :writer] (copy-reader (new-isr input (encoding o)) output size)
      [:input-stream :file] (let [out (new-fos output)] (try (copy-stream input out size) (finally (.close out))))
      [:reader :output-stream] (let [w (new-osw output (encoding o))] (copy-reader input w size) (.flush w))
      [:reader :writer] (copy-reader input output size)
      [:reader :file] (let [out (new-fos output)] (try (apply copy input out opts) (finally (.close out))))
      [:file :output-stream] (let [in (new-fis input)] (try (copy-stream in output size) (finally (.close in))))
      [:file :writer] (let [in (new-fis input)] (try (apply copy in output opts) (finally (.close in))))
      [:file :file] (let [in (new-fis input)]
                      (try (let [out (new-fos output)] (try (copy-stream in out size) (finally (.close out))))
                           (finally (.close in))))
      [:string :output-stream] (apply copy (new-string-reader input) output opts)
      [:string :writer] (.write output input)
      [:string :file] (apply copy (new-string-reader input) output opts)
      [:chars :output-stream] (apply copy (new-string-reader (.join input "")) output opts)
      [:chars :writer] (.write output input)
      [:chars :file] (apply copy (new-string-reader (.join input "")) output opts)
      [:bytes :output-stream] (.write output input)
      [:bytes :writer] (apply copy (jb/new-bais input) output opts)
      [:bytes :file] (apply copy (jb/new-bais input) output opts)
      (throw (jvm/IllegalArgumentException.
              (str "No method in multimethod 'do-copy' for dispatch value: ["
                   (pr-str (type input)) " " (pr-str (type output)) "]"))))
    nil))

(defn with-open*
  "clojure.core/with-open as a sci macro fn. The engine's own expansion
  recursed through `sci.impl.namespaces/with-open`, a symbol sci cannot
  resolve, so EVERY with-open died with \"Unable to resolve symbol:
  sci.impl.namespaces/with-open\" (measured 2026-09-24 on the 2b61d53
  build) -- this one recurses through clojure.core/with-open. Closes in
  reverse order with (. x close), in a finally, as the JVM's does."
  [_ _ bindings & body]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (throw (js/Error. "with-open requires a vector with an even number of forms in bindings")))
  (cond (zero? (count bindings)) (list* 'do body)
        (symbol? (bindings 0))
        (list 'clojure.core/let (subvec bindings 0 2)
              (list 'try
                    (list* 'clojure.core/with-open (subvec bindings 2) body)
                    (list 'finally (list '. (bindings 0) 'close))))
        :else (throw (js/Error. "with-open only allows Symbols in bindings"))))

(defn line-seq*
  "clojure.core/line-seq"
  [^js rdr]
  (when-let [line (.readLine rdr)]
    (cons line (lazy-seq (line-seq* rdr)))))

(defn- read-all-chars [r]
  (let [sb #js [] buf (js/Array. 8192)]
    (loop [] (let [k (.read r buf 0 8192)] (when (pos? k) (.push sb (.join (.slice buf 0 k) "")) (recur))))
    (.join sb "")))

(defn slurp-stream
  "slurp of a Reader / InputStream this engine models (closing it), else nil."
  [x opts]
  (when (or (reader? x) (instance? FileInputStream x) (instance? BufferedInputStream x))
    (let [r (apply reader x (mapcat identity opts))]
      (try (read-all-chars r) (finally (.close r))))))

(defn spit-stream
  "spit to a Writer / OutputStream this engine models (closing it); true when
  it handled x."
  [x content opts]
  (when (or (writer? x) (output-stream? x))
    (let [w (apply writer x (mapcat identity opts))]
      (try (.write w (str content)) (finally (.close w))))
    true))

(defn install! []
  (reset! jvm/stream-slurp slurp-stream)
  (reset! jvm/stream-spit spit-stream))

(defn- abstract-class [nm pred]
  (let [c (js* "(function(n){ var C = function(){ throw new Error(n + ' is abstract'); }; Object.defineProperty(C, 'name', {value: n}); return C; })(~{})" nm)]
    (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" pred})
    c))

(defn- ctor [c f sym] {:class c :constructor (with-meta sym {:sci.impl/constructor f})})

(defn classes []
  {'java.io.FileInputStream (ctor FileInputStream new-fis 'java.io.FileInputStream)
   'java.io.FileOutputStream (ctor FileOutputStream new-fos 'java.io.FileOutputStream)
   'java.io.BufferedInputStream (ctor BufferedInputStream new-bis 'java.io.BufferedInputStream)
   'java.io.BufferedOutputStream (ctor BufferedOutputStream new-bos 'java.io.BufferedOutputStream)
   'java.io.InputStreamReader (ctor InputStreamReader new-isr 'java.io.InputStreamReader)
   'java.io.FileReader (ctor InputStreamReader new-file-reader 'java.io.FileReader)
   'java.io.StringReader (ctor StringReader new-string-reader 'java.io.StringReader)
   'java.io.BufferedReader (ctor BufferedReader new-buffered-reader 'java.io.BufferedReader)
   'java.io.PushbackReader (ctor PushbackReader new-pushback-reader 'java.io.PushbackReader)
   'java.io.OutputStreamWriter (ctor OutputStreamWriter new-osw 'java.io.OutputStreamWriter)
   'java.io.FileWriter (ctor OutputStreamWriter new-file-writer 'java.io.FileWriter)
   'java.io.BufferedWriter (ctor BufferedWriter new-buffered-writer 'java.io.BufferedWriter)
   'java.io.StringWriter (ctor StringWriter (fn ([] (StringWriter. "")) ([_] (StringWriter. ""))) 'java.io.StringWriter)
   'java.io.InputStream (abstract-class "java.io.InputStream" input-stream?)
   'java.io.OutputStream (abstract-class "java.io.OutputStream" output-stream?)
   'java.io.Reader (abstract-class "java.io.Reader" reader?)
   'java.io.Writer (abstract-class "java.io.Writer" writer?)
   'java.io.EOFException EOFException})
