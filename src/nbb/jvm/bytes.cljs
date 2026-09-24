(ns nbb.jvm.bytes
  "JVM-compatibility SCAFFOLDING, round 2: byte[] and the names built on it
  (see nbb.jvm for why this exists and the rule for what is here; it is not a
  target for new code, which uses the kotoba stdlib).

  Representation: a Java byte[] is a signed Int8Array, so `(aget b i)` of the
  byte 0xFF is -1 as on the JVM, and every name below agrees on it:
  byte-array / bytes? / bytes / byte / unchecked-byte / aset-byte, seq / count
  / nth over a byte[], `(String. bytes charset)`, `(.getBytes s charset)`,
  java.nio.charset.StandardCharsets / Charset, java.util.Base64 encoders and
  decoders, java.io.ByteArrayOutputStream / ByteArrayInputStream,
  java.security.MessageDigest, java.util.Arrays/equals on byte arrays.
  Inputs are lenient where the JVM would not compile at all (a Uint8Array /
  Buffer or a JS array of numbers is read as bytes): that can only turn a
  JVM compile error into an answer, never change a JVM answer.

  Charsets: UTF-8, US-ASCII, ISO-8859-1, UTF-16 / UTF-16BE / UTF-16LE, with
  the JDK's replacement behaviour (unmappable -> '?', a lone surrogate -> '?'
  in UTF-8, malformed input -> U+FFFD; checked against JDK 21). Any other
  charset name is refused with the JVM's exception, not approximated.

  Deliberately NOT here (semantics not matchable): (byte-array n x) cannot
  refuse a Long fill value the way the JVM does (a JS number cannot say it
  was a Byte); `=` on two byte[] is identity, as on the JVM."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]))

;; ---------------------------------------------------------------------------
;; byte[]
;; ---------------------------------------------------------------------------

(defn byte-array? [x] (instance? js/Int8Array x))

(defn iterator-seq*
  "clojure.core/iterator-seq over a Java-shaped iterator (.hasNext / .next)."
  [^js it]
  (lazy-seq (when (.hasNext it) (cons (.next it) (iterator-seq* it)))))

(extend-type js/Int8Array
  ISeqable
  (-seq [a] (when (pos? (.-length a)) (IndexedSeq. a 0 nil)))
  ICounted
  (-count [a] (.-length a))
  IIndexed
  (-nth
    ([a n] (if (and (<= 0 n) (< n (.-length a))) (aget a n)
               (throw (js/Error. (str "Index " n " out of bounds for length " (.-length a))))))
    ([a n not-found] (if (and (<= 0 n) (< n (.-length a))) (aget a n) not-found)))
  ILookup
  (-lookup
    ([a k] (-lookup a k nil))
    ([a k not-found] (if (and (number? k) (<= 0 k) (< k (.-length a))) (aget a k) not-found))))

(defn ^js u8
  "The bytes of x as a Uint8Array view (no copy for typed arrays)."
  [x]
  (cond (instance? js/Uint8Array x) x
        (js/ArrayBuffer.isView x) (js/Uint8Array. (.-buffer x) (.-byteOffset x) (.-byteLength x))
        (instance? js/ArrayBuffer x) (js/Uint8Array. x)
        (array? x) (.from js/Uint8Array x)
        :else (throw (js/TypeError. (str "Not a byte array: " (pr-str x))))))

(defn ->bytes
  "A fresh Java byte[] (signed Int8Array) with the bytes of x."
  [x]
  (.from js/Int8Array (u8 x)))

(defn- out-of-range [x] (jvm/IllegalArgumentException. (str "Value out of range for byte: " x)))

(defn byte*
  "clojure.core/byte: range-checked, truncating."
  [x]
  (when-not (number? x)
    (throw (js/TypeError. (str "class " (type->str (type x)) " cannot be cast to class java.lang.Number"))))
  (let [n (js/Math.trunc x)]
    (if (or (< n -128) (> n 127)) (throw (out-of-range x)) n)))

(defn unchecked-byte* [x] (aget (.of js/Int8Array (js/Math.trunc x)) 0))

(defn byte-array*
  "clojure.core/byte-array: (byte-array size-or-seq), (byte-array size init).
  Seq elements are narrowed like Number.byteValue (wrap, truncate)."
  ([size-or-seq]
   (if (number? size-or-seq)
     (js/Int8Array. size-or-seq)
     (.from js/Int8Array (into-array (map #(js/Math.trunc %) size-or-seq)))))
  ([size init]
   (let [a (js/Int8Array. size)]
     (if (number? init)
       (.fill a (byte* init))
       (loop [i 0 s (seq init)]
         (when (and s (< i size))
           (aset a i (js/Math.trunc (first s)))
           (recur (inc i) (next s)))))
     a)))

(defn bytes* [x]
  (if (or (nil? x) (byte-array? x)) x
      (throw (js/TypeError. (str "class " (type->str (type x)) " cannot be cast to class [B")))))

(defn aset-byte* [a i v] (aset a i (byte* v)) (byte* v))

;; ---------------------------------------------------------------------------
;; charsets
;; ---------------------------------------------------------------------------

(def UnsupportedEncodingException (jvm/defclass "java.io.UnsupportedEncodingException" jvm/IOException))
(def UnsupportedCharsetException (jvm/defclass "java.nio.charset.UnsupportedCharsetException" jvm/IllegalArgumentException))
(def IllegalCharsetNameException (jvm/defclass "java.nio.charset.IllegalCharsetNameException" jvm/IllegalArgumentException))
(def IndexOutOfBoundsException (jvm/defclass "java.lang.IndexOutOfBoundsException" jvm/RuntimeException))
(def StringIndexOutOfBoundsException (jvm/defclass "java.lang.StringIndexOutOfBoundsException" IndexOutOfBoundsException))
(def NoSuchAlgorithmException (jvm/defclass "java.security.NoSuchAlgorithmException" (jvm/defclass "java.security.GeneralSecurityException" jvm/Exception)))

(deftype Charset [nm aliases]
  Object
  (name [_] nm)
  (displayName [_] nm)
  (toString [_] nm)
  (aliases [_] (set aliases))
  (canEncode [_] true)
  (equals [this o] (identical? this o))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (str "#object[java.nio.charset.Charset \"" nm "\"]"))))

(def UTF_8 (Charset. "UTF-8" ["UTF8" "unicode-1-1-utf-8"]))
(def US_ASCII (Charset. "US-ASCII" ["ASCII" "us" "ISO646-US" "iso-ir-6" "ANSI_X3.4-1968" "ANSI_X3.4-1986" "ISO_646.irv:1991" "csASCII" "cp367" "IBM367" "646" "default" "iso_646.irv:1983" "ascii7"]))
(def ISO_8859_1 (Charset. "ISO-8859-1" ["ISO8859_1" "ISO_8859_1" "ISO8859-1" "ISO_8859-1" "ISO_8859-1:1987" "8859_1" "latin1" "l1" "cp819" "IBM819" "IBM-819" "csISOLatin1" "iso-ir-100" "819"]))
(def UTF_16 (Charset. "UTF-16" ["UTF_16" "utf16" "unicode" "UnicodeBig"]))
(def UTF_16BE (Charset. "UTF-16BE" ["UTF_16BE" "ISO-10646-UCS-2" "X-UTF-16BE" "UnicodeBigUnmarked"]))
(def UTF_16LE (Charset. "UTF-16LE" ["UTF_16LE" "X-UTF-16LE" "UnicodeLittleUnmarked"]))

(def ^:private charsets [UTF_8 US_ASCII ISO_8859_1 UTF_16 UTF_16BE UTF_16LE])

(def ^:private by-name
  (into {} (for [^js c charsets n (cons (.name c) (.-aliases c))] [(str/lower-case n) c])))

(defn- legal-name? [s]
  (boolean (re-matches #"[A-Za-z0-9][A-Za-z0-9\-+:_.]*" s)))

(defn charset-for-name
  "Charset.forName."
  [s]
  (when (nil? s) (throw (jvm/IllegalArgumentException. "Null charset name")))
  (let [s (str s)]
    (when-not (legal-name? s) (throw (IllegalCharsetNameException. s)))
    (or (get by-name (str/lower-case s)) (throw (UnsupportedCharsetException. s)))))

(defn- ->charset
  "A Charset or a charset name (the String overloads throw the checked
  UnsupportedEncodingException instead)."
  [x]
  (cond (instance? Charset x) x
        (nil? x) (throw (js/TypeError. "Cannot invoke \"java.nio.charset.Charset.name()\" because \"charset\" is null"))
        :else (or (get by-name (str/lower-case (str x))) (throw (UnsupportedEncodingException. (str x))))))

(def ^:private lone-surrogate #"[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]")

(defn- encode-single-byte
  "Each code point <= max as one byte, anything else '?' (a surrogate pair is
  one '?', as in the JDK)."
  [s max]
  (let [out #js []]
    (loop [i 0]
      (when (< i (count s))
        (let [cp (.codePointAt s i)]
          (.push out (if (<= cp max) cp 63))
          (recur (+ i (if (> cp 0xFFFF) 2 1))))))
    (.from js/Int8Array out)))

(defn- utf16-units [s little? replace-lone?]
  (let [s (if replace-lone? (str/replace s (js/RegExp. (.-source lone-surrogate) "g") "�") s)
        n (count s)
        a (js/Int8Array. (* 2 n))]
    (dotimes [i n]
      (let [c (.charCodeAt s i) hi (bit-shift-right c 8) lo (bit-and c 0xff)]
        (aset a (* 2 i) (if little? lo hi))
        (aset a (inc (* 2 i)) (if little? hi lo))))
    a))

(defn encode
  "String -> Java byte[] in charset cs."
  [s ^js cs]
  (let [s (str s)]
    (condp identical? cs
      UTF_8 (->bytes (.encode (js/TextEncoder.) (str/replace s (js/RegExp. (.-source lone-surrogate) "g") "?")))
      US_ASCII (encode-single-byte s 0x7F)
      ISO_8859_1 (encode-single-byte s 0xFF)
      UTF_16 (if (= "" s) (js/Int8Array. 0)
                 (let [body (utf16-units s false true) a (js/Int8Array. (+ 2 (.-length body)))]
                   (aset a 0 -2) (aset a 1 -1) (.set a body 2) a))
      UTF_16BE (utf16-units s false true)
      UTF_16LE (utf16-units s true true))))

(defn- decode-utf16 [^js b little?]
  (let [n (.-length b)
        units (js/Array.)]
    (loop [i 0]
      (when (< (inc i) n)
        (let [x (aget b i) y (aget b (inc i))]
          (.push units (if little? (bit-or (bit-shift-left y 8) x) (bit-or (bit-shift-left x 8) y)))
          (recur (+ i 2)))))
    (let [s (.join (.map units (fn [u] (js/String.fromCharCode u))) "")
          ;; unpaired surrogates in the input are malformed -> U+FFFD
          s (str/replace s (js/RegExp. (.-source lone-surrogate) "g") "�")]
      (if (odd? n) (str s "�") s))))

(defn decode
  "Java byte[] (or any bytes) -> String in charset cs."
  [bytes ^js cs]
  (let [b (u8 bytes)]
    (condp identical? cs
      UTF_8 (.decode (js/TextDecoder. "utf-8" #js {"fatal" false "ignoreBOM" true}) b)
      ISO_8859_1 (.join (.map (js/Array.from b) (fn [x] (js/String.fromCharCode x))) "")
      US_ASCII (.join (.map (js/Array.from b) (fn [x] (if (< x 0x80) (js/String.fromCharCode x) "�"))) "")
      UTF_16BE (decode-utf16 b false)
      UTF_16LE (decode-utf16 b true)
      UTF_16 (cond (and (>= (.-length b) 2) (= 0xFE (aget b 0)) (= 0xFF (aget b 1))) (decode-utf16 (.subarray b 2) false)
                   (and (>= (.-length b) 2) (= 0xFF (aget b 0)) (= 0xFE (aget b 1))) (decode-utf16 (.subarray b 2) true)
                   :else (decode-utf16 b false)))))

;; ---------------------------------------------------------------------------
;; java.lang.String: constructor, statics, getBytes
;; ---------------------------------------------------------------------------

(defn- check-range [len off n]
  (when (or (neg? off) (neg? n) (> (+ off n) len))
    (throw (StringIndexOutOfBoundsException. (str "Range [" off ", " off " + " n ") out of bounds for length " len)))))

(defn new-string
  "(String.) (String. s) (String. bytes) (String. bytes charset)
  (String. bytes offset length) (String. bytes offset length charset)"
  ([] "")
  ([x] (cond (string? x) x
             (nil? x) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"original\" is null"))
             (and (array? x) (every? string? x)) (.join x "") ; char[]
             :else (decode x UTF_8)))
  ([b cs] (decode b (->charset cs)))
  ([b off n] (new-string b off n UTF_8))
  ([b off n cs]
   (let [v (u8 b)]
     (check-range (.-length v) off n)
     (decode (.subarray v off (+ off n)) (->charset cs)))))

(defn- get-bytes-impl [s cs n]
  (encode s (if (zero? n) UTF_8 (->charset cs))))

(def ^:private get-bytes
  ;; a plain JS function: a multi-arity cljs fn installed as a method would
  ;; dispatch with `this` bound to itself, not to the string
  (js* "(function(impl){ return function getBytes(cs){ return impl(String(this), cs, arguments.length); }; })(~{})"
       get-bytes-impl))

(def JString
  "java.lang.String: (String. ...) goes through the :constructor below,
  instance? is string?, and the statics the suites use."
  (let [c (js* "(function(){ var S = function String(){}; Object.defineProperty(S, Symbol.hasInstance, {value: function(x){ return typeof x === 'string' || x instanceof globalThis.String; }}); return S; })()")]
    (gobj/set c "valueOf" (fn ([x] (cond (nil? x) "null"
                                         (and (array? x) (every? string? x)) (.join x "")
                                         :else (str x)))
                            ([chars off n] (.join (.slice chars off (+ off n)) ""))))
    (gobj/set c "copyValueOf" (fn [chars] (.join chars "")))
    (gobj/set c "join" (fn [sep & xs]
                         (let [items (if (and (= 1 (count xs)) (not (string? (first xs)))) (first xs) xs)]
                           (str/join (str sep) (map #(if (nil? %) "null" (str %)) items)))))
    (gobj/set c "format" (fn [fmt & args]
                           (let [args (if (and (= 1 (count args)) (array? (first args))) (seq (first args)) args)]
                             (apply jvm/format* fmt args))))
    (gobj/set c "CASE_INSENSITIVE_ORDER" (fn [a b] (compare (str/lower-case a) (str/lower-case b))))
    c))

(def string-class-opts
  {:class JString
   :constructor (with-meta 'java.lang.String {:sci.impl/constructor new-string})})

;; ---------------------------------------------------------------------------
;; java.util.Base64
;; ---------------------------------------------------------------------------

(def ^:private std-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/")
(def ^:private url-alphabet "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")

(defn- b64-encode-string [bytes url? pad? line]
  (let [b (u8 bytes)
        alpha (if url? url-alphabet std-alphabet)
        n (.-length b)
        out #js []]
    (loop [i 0]
      (when (< i n)
        (let [b0 (aget b i)
              b1 (if (< (+ i 1) n) (aget b (+ i 1)) 0)
              b2 (if (< (+ i 2) n) (aget b (+ i 2)) 0)
              v (bit-or (bit-shift-left b0 16) (bit-shift-left b1 8) b2)
              rem (- n i)]
          (.push out (.charAt alpha (bit-and (bit-shift-right v 18) 63)))
          (.push out (.charAt alpha (bit-and (bit-shift-right v 12) 63)))
          (if (> rem 1) (.push out (.charAt alpha (bit-and (bit-shift-right v 6) 63))) (when pad? (.push out "=")))
          (if (> rem 2) (.push out (.charAt alpha (bit-and v 63))) (when pad? (.push out "=")))
          (recur (+ i 3)))))
    (let [s (.join out "")]
      (if (and line (> (count s) line))
        (str/join "\r\n" (map #(apply str %) (partition-all line s)))
        s))))

(defn- decode-table [url?]
  (let [t (js/Int16Array. 256)]
    (.fill t -1)
    (dotimes [i 64] (aset t (.charCodeAt (if url? url-alphabet std-alphabet) i) i))
    (aset t 61 -2)
    t))

(def ^:private std-table (decode-table false))
(def ^:private url-table (decode-table true))

(defn- hex-signed [b] (.toString (if (> b 127) (- b 256) b) 16))

(defn- b64-tail
  "The tail of decode0 once the input ended or padding was met."
  [^js out src sl t sp bits shiftto mime?]
  (cond (= shiftto 6) (.push out (bit-and (bit-shift-right bits 16) 0xff))
        (= shiftto 0) (.push out (bit-and (bit-shift-right bits 16) 0xff) (bit-and (bit-shift-right bits 8) 0xff))
        (= shiftto 12) (throw (jvm/IllegalArgumentException. "Last unit does not have enough valid bits")))
  (loop [sp sp]
    (when (< sp sl)
      (if (and mime? (neg? (aget t (aget src sp))))
        (recur (inc sp))
        (throw (jvm/IllegalArgumentException. (str "Input byte array has incorrect ending byte at " (if mime? (inc sp) sp)))))))
  (.from js/Int8Array out))

(defn- b64-decode
  "java.util.Base64.Decoder.decode0 (JDK 21), with its messages. A String is
  read as ISO-8859-1 bytes first, as the JDK does."
  [src url? mime?]
  (let [src (if (string? src) (u8 (encode src ISO_8859_1)) (u8 src))
        sl (.-length src)
        t (if url? url-table std-table)
        out #js []]
    (when (< sl 2)
      (when-not (or (zero? sl) mime?) ; JDK checks base64[0], always -1, for MIME
        (throw (jvm/IllegalArgumentException. "Input byte[] should at least have 2 bytes for base64 bytes"))))
    (let [[sp bits shiftto]
          (loop [sp 0 bits 0 shiftto 18]
            (if (< sp sl)
              (let [raw (aget src sp)
                    sp (inc sp)
                    b (aget t raw)]
                (cond
                  (= b -2)
                  (do (when (or (and (= shiftto 6) (or (= sp sl) (not= 61 (aget src sp))))
                                (= shiftto 18))
                        (throw (jvm/IllegalArgumentException. "Input byte array has wrong 4-byte ending unit")))
                      [(if (= shiftto 6) (inc sp) sp) bits shiftto])
                  (neg? b)
                  (if mime? (recur sp bits shiftto)
                      (throw (jvm/IllegalArgumentException. (str "Illegal base64 character " (hex-signed raw)))))
                  :else
                  (let [bits (bit-or bits (bit-shift-left b shiftto))
                        shiftto (- shiftto 6)]
                    (if (neg? shiftto)
                      (do (.push out (bit-and (bit-shift-right bits 16) 0xff)
                                 (bit-and (bit-shift-right bits 8) 0xff)
                                 (bit-and bits 0xff))
                          (recur sp 0 18))
                      (recur sp bits shiftto)))))
              [sp bits shiftto]))]
      (b64-tail out src sl t sp bits shiftto mime?))))

(deftype Base64Encoder [url? pad? line]
  Object
  (encodeToString [_ b] (b64-encode-string b url? pad? line))
  (encode [_ b] (encode (b64-encode-string b url? pad? line) ISO_8859_1))
  (withoutPadding [_] (Base64Encoder. url? false line)))

(deftype Base64Decoder [url? mime?]
  Object
  (decode [_ src] (b64-decode src url? mime?)))

(def Base64
  (jvm/strict-statics "java.util.Base64"
                      (js-obj "getEncoder" (fn [] (Base64Encoder. false true nil))
                              "getUrlEncoder" (fn [] (Base64Encoder. true true nil))
                              "getMimeEncoder" (fn ([] (Base64Encoder. false true 76))
                                                 ([n _sep] (Base64Encoder. false true (* 4 (quot n 4)))))
                              "getDecoder" (fn [] (Base64Decoder. false false))
                              "getUrlDecoder" (fn [] (Base64Decoder. true false))
                              "getMimeDecoder" (fn [] (Base64Decoder. false true)))
                      []))

;; ---------------------------------------------------------------------------
;; java.io.ByteArrayOutputStream / ByteArrayInputStream
;; ---------------------------------------------------------------------------

(defn- check-off-len [len off n]
  (when (or (neg? off) (neg? n) (> (+ off n) len))
    (throw (IndexOutOfBoundsException. (str "Range [" off ", " off " + " n ") out of bounds for length " len)))))

(deftype ByteArrayOutputStream [^:mutable buf ^:mutable cnt]
  Object
  (write [this x] (if (number? x)
                    (do (.ensure this 1) (aset buf cnt x) (set! cnt (inc cnt)) nil)
                    (.write this x 0 (.-length (u8 x)))))
  (write [this b off n]
    (let [v (u8 b)]
      (check-off-len (.-length v) off n)
      (.ensure this n)
      (.set buf (js/Int8Array. (.-buffer v) (+ (.-byteOffset v) off) n) cnt)
      (set! cnt (+ cnt n))
      nil))
  (writeBytes [this b] (.write this b))
  (ensure [_ n]
    (when (> (+ cnt n) (.-length buf))
      (let [nb (js/Int8Array. (max (* 2 (.-length buf)) (+ cnt n) 32))]
        (.set nb (.subarray buf 0 cnt))
        (set! buf nb))))
  (toByteArray [_] (.slice buf 0 cnt))
  (size [_] cnt)
  (reset [_] (set! cnt 0) nil)
  (toString [_] (decode (.subarray buf 0 cnt) UTF_8))
  (toString [_ cs] (decode (.subarray buf 0 cnt) (->charset cs)))
  (writeTo [_ out] (.write out (.slice buf 0 cnt)) nil)
  (flush [_] nil)
  (close [_] nil))

(defn new-baos
  ([] (ByteArrayOutputStream. (js/Int8Array. 32) 0))
  ([n] (when (neg? n) (throw (jvm/IllegalArgumentException. (str "Negative initial size: " n))))
   (ByteArrayOutputStream. (js/Int8Array. (max n 1)) 0)))

(deftype ByteArrayInputStream [buf ^:mutable pos end ^:mutable mark-pos]
  Object
  (read [_] (if (< pos end) (let [b (bit-and (aget buf pos) 0xff)] (set! pos (inc pos)) b) -1))
  (read [this b] (.read this b 0 (.-length b)))
  (read [_ b off n]
    (check-off-len (.-length b) off n)
    (cond (zero? n) 0
          (>= pos end) -1
          :else (let [k (min n (- end pos))]
                  (.set b (.subarray buf pos (+ pos k)) off)
                  (set! pos (+ pos k))
                  k)))
  (readAllBytes [_] (let [r (.slice buf pos end)] (set! pos end) r))
  (readNBytes [_ n] (let [k (min n (- end pos)) r (.slice buf pos (+ pos k))] (set! pos (+ pos k)) r))
  (skip [_ n] (let [k (max 0 (min n (- end pos)))] (set! pos (+ pos k)) k))
  (available [_] (- end pos))
  (markSupported [_] true)
  (mark [_ _] (set! mark-pos pos) nil)
  (reset [_] (set! pos mark-pos) nil)
  (close [_] nil))

(defn new-bais
  ([b] (let [v (->bytes b)] (ByteArrayInputStream. v 0 (.-length v) 0)))
  ([b off n] (let [v (->bytes b)] (ByteArrayInputStream. v off (min (.-length v) (+ off n)) off))))

(defn- remaining-bytes [x]
  (when (instance? ByteArrayInputStream x)
    (u8 (.readAllBytes ^js x))))

;; ---------------------------------------------------------------------------
;; java.security.MessageDigest (node:crypto; the digests are the same bytes)
;; ---------------------------------------------------------------------------

(def ^:private digest-names
  {"SHA-256" "sha256" "SHA256" "sha256" "SHA-1" "sha1" "SHA1" "sha1" "SHA" "sha1"
   "SHA-224" "sha224" "SHA-384" "sha384" "SHA-512" "sha512" "MD5" "md5"
   "SHA-512/256" "sha512-256" "SHA-512/224" "sha512-224"
   "SHA3-256" "sha3-256" "SHA3-384" "sha3-384" "SHA3-512" "sha3-512" "SHA3-224" "sha3-224"})

(deftype MessageDigest [algo node-name ^:mutable parts]
  Object
  (getAlgorithm [_] algo)
  (update [_ x] (.push parts (js/Buffer.from (if (number? x) #js [(bit-and x 0xff)] (u8 x)))) nil)
  (update [_ b off n] (let [v (u8 b)] (check-off-len (.-length v) off n) (.push parts (js/Buffer.from (.subarray v off (+ off n))))) nil)
  (digest [this]
    (let [h (.createHash crypto node-name)]
      (doseq [p parts] (.update h p))
      (set! parts #js [])
      (->bytes (.digest h))))
  (digest [this b] (.update this b) (.digest this))
  (reset [_] (set! parts #js []) nil)
  (getDigestLength [_] (.-length (.digest (.createHash crypto node-name))))
  (toString [_] (str algo " Message Digest from nbb.jvm")))

(def MessageDigestClass
  (jvm/strict-statics "java.security.MessageDigest"
                      (js-obj "getInstance" (fn [algo & _]
                                              (if-let [n (get digest-names (str/upper-case (str algo)))]
                                                (MessageDigest. algo n #js [])
                                                (throw (NoSuchAlgorithmException. (str algo " MessageDigest not available")))))
                              "isEqual" (fn [a b]
                                          (cond (identical? a b) true
                                                (or (nil? a) (nil? b)) false
                                                :else (let [x (u8 a) y (u8 b)]
                                                        (and (= (.-length x) (.-length y))
                                                             (.timingSafeEqual crypto x y))))))
                      []))

;; ---------------------------------------------------------------------------
;; java.util.Arrays (the byte[] / array subset)
;; ---------------------------------------------------------------------------

(def Arrays
  (jvm/strict-statics "java.util.Arrays"
                      (js-obj "equals" (fn [a b]
                                         (cond (identical? a b) true
                                               (or (nil? a) (nil? b)) false
                                               :else (and (= (alength a) (alength b))
                                                          (every? #(= (aget a %) (aget b %)) (range (alength a))))))
                              "copyOf" (fn [a n] (let [r (if (byte-array? a) (js/Int8Array. n) (js/Array. n))]
                                                   (dotimes [i (min n (alength a))] (aset r i (aget a i)))
                                                   (when-not (byte-array? a) (loop [i (alength a)] (when (< i n) (aset r i nil) (recur (inc i)))))
                                                   r))
                              "copyOfRange" (fn [a from to]
                                              (when (> from to) (throw (jvm/IllegalArgumentException. (str from " > " to))))
                                              (when (or (neg? from) (> from (alength a)))
                                                (throw (IndexOutOfBoundsException. (str "Array index out of range: " from))))
                                              (let [r (if (byte-array? a) (js/Int8Array. (- to from)) (js/Array. (- to from)))]
                                                (dotimes [i (- to from)] (aset r i (if (< (+ from i) (alength a)) (aget a (+ from i)) (if (byte-array? a) 0 nil))))
                                                r))
                              "fill" (fn ([a v] (.fill a v) nil) ([a from to v] (.fill a v from to) nil))
                              "toString" (fn [a] (if (nil? a) "null" (str "[" (str/join ", " (map str (array-seq (js/Array.from a)))) "]"))))
                      []))

(defn install! []
  (reset! jvm/input-stream-bytes remaining-bytes)
  (js/Object.defineProperty (.-prototype js/String) "getBytes"
                            #js {"value" get-bytes "writable" true "configurable" true "enumerable" false}))

(def charset-class
  (js-obj "forName" charset-for-name
          "defaultCharset" (fn [] UTF_8)
          "isSupported" (fn [n] (try (some? (charset-for-name n)) (catch :default e (if (instance? UnsupportedCharsetException e) false (throw e)))))))

(defn classes []
  {'String string-class-opts
   'java.lang.String string-class-opts
   'java.nio.charset.StandardCharsets (js-obj "UTF_8" UTF_8 "US_ASCII" US_ASCII "ISO_8859_1" ISO_8859_1
                                              "UTF_16" UTF_16 "UTF_16BE" UTF_16BE "UTF_16LE" UTF_16LE)
   'java.nio.charset.Charset {:class (let [c (js* "(function(){ var C = function Charset(){}; return C; })()")]
                                       (js/Object.assign c charset-class)
                                       (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" (fn [x] (instance? Charset x))})
                                       c)}
   'java.nio.charset.UnsupportedCharsetException UnsupportedCharsetException
   'java.nio.charset.IllegalCharsetNameException IllegalCharsetNameException
   'java.io.UnsupportedEncodingException UnsupportedEncodingException
   'java.lang.IndexOutOfBoundsException IndexOutOfBoundsException
   'IndexOutOfBoundsException IndexOutOfBoundsException
   'java.lang.StringIndexOutOfBoundsException StringIndexOutOfBoundsException
   'StringIndexOutOfBoundsException StringIndexOutOfBoundsException
   'java.util.Base64 Base64
   'java.io.ByteArrayOutputStream {:class ByteArrayOutputStream
                                   :constructor (with-meta 'java.io.ByteArrayOutputStream {:sci.impl/constructor new-baos})}
   'java.io.ByteArrayInputStream {:class ByteArrayInputStream
                                  :constructor (with-meta 'java.io.ByteArrayInputStream {:sci.impl/constructor new-bais})}
   'java.security.MessageDigest MessageDigestClass
   'java.security.NoSuchAlgorithmException NoSuchAlgorithmException
   'java.util.Arrays Arrays})
