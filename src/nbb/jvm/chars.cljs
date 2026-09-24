(ns nbb.jvm.chars
  "JVM-compatibility SCAFFOLDING, round 3: java.lang.Character and char[]
  (see nbb.jvm for why this exists and the rule for what is here; it is not a
  target for new code, which uses the kotoba stdlib).

  Representation: a char is what ClojureScript already makes it, a
  one-code-unit string (\\a is \"a\"), and a Java char[] is a JS array of those
  strings, one per UTF-16 unit -- the shape round 2's `(String. chars)` and
  `String/valueOf` already read. So `char-array`, `aset-char`, `.toCharArray`,
  `Character/toChars` and `(String. chars off n)` agree with each other and
  with `seq` / `count` / `aget` / `apply str` over the array.

  Character statics take a char (a one-unit string) or an int code point, as
  the JVM overloads do, and the case mappings answer in the argument's kind
  (char in -> char out, int in -> int out). Classification uses the Unicode
  properties of the host's RegExp (\\p{L}, \\p{Nd}, \\p{Uppercase}, ...), which
  are the properties the JDK names; toUpperCase / toLowerCase are the SIMPLE
  (1:1) case mappings the JDK uses (ß stays ß, İ lowercases to i).
  Checked against JDK 21 (2026-09-24).

  Documented deviations: the host's Unicode version is not JDK 21's (Unicode
  15.0), so a code point assigned after 15.0 classifies here and not there;
  getNumericValue answers decimal digits, Latin / fullwidth letters and a
  table of common non-decimal numerals (super/subscripts, Roman numerals,
  circled 1-20, vulgar fractions), and REFUSES any other numeric character
  (UnsupportedOperationException naming it) rather than guess its value;
  `(str chars)` prints the elements, not \"[C@hash\".
  Deliberately NOT here: `(class \\a)` cannot say Character (a char and a
  one-character String are the same value on this engine)."
  (:require [clojure.string :as str]
            [nbb.jvm :as jvm]))

;; ---------------------------------------------------------------------------
;; chars and code points
;; ---------------------------------------------------------------------------

(defn- cp-of
  "A char (one-unit string) or an int -> its code (unit or point)."
  [x]
  (cond (string? x) (if (zero? (count x))
                      (throw (js/TypeError. "An empty string is not a char"))
                      (.charCodeAt x 0))
        (number? x) (js/Math.trunc x)
        (nil? x) (throw (js/TypeError. "Cannot invoke \"java.lang.Character.charValue()\" because the char is null"))
        :else (throw (js/TypeError. (str "Not a char or a code point: " (pr-str x))))))

(defn- cp-str
  "The string of one code point (a lone surrogate stays a lone unit)."
  [cp]
  (if (<= 0 cp 0x10FFFF) (js/String.fromCodePoint cp) ""))

(defn- test-re [re]
  (let [re (js/RegExp. (str "^" re "$") "u")]
    (fn [x] (boolean (.test re (cp-str (cp-of x)))))))

(defn- unit-str [cp] (js/String.fromCharCode cp))

(defn- valid-cp? [cp] (and (integer? cp) (<= 0 cp 0x10FFFF)))

(defn- hex32 [n] (str/upper-case (.toString (js/BigInt.asUintN 32 (js/BigInt n)) 16)))

(defn- invalid-cp [cp]
  (jvm/IllegalArgumentException. (str "Not a valid Unicode code point: 0x" (hex32 cp))))

;; ---------------------------------------------------------------------------
;; classification
;; ---------------------------------------------------------------------------

(def ^:private letter? (test-re "\\p{L}"))
(def ^:private digit? (test-re "\\p{Nd}"))
(def ^:private numeric? (test-re "\\p{N}"))
(def ^:private upper? (test-re "\\p{Uppercase}"))
(def ^:private lower? (test-re "\\p{Lowercase}"))
(def ^:private alphabetic? (test-re "\\p{Alphabetic}"))
(def ^:private title? (test-re "\\p{Lt}"))
(def ^:private ideographic? (test-re "\\p{Ideographic}"))
(def ^:private space-char? (test-re "[\\p{Zs}\\p{Zl}\\p{Zp}]"))
(def ^:private defined? (test-re "\\P{Cn}"))

(defn- whitespace?
  "Character.isWhitespace: a space/line/paragraph separator other than the
  no-break spaces U+00A0 U+2007 U+202F, or one of \\t \\n \\u000B \\f \\r
  \\u001C-\\u001F."
  [x]
  (let [cp (cp-of x)]
    (or (<= 0x09 cp 0x0D) (<= 0x1C cp 0x1F)
        (and (space-char? cp) (not (#{0xA0 0x2007 0x202F} cp))))))

(def ^:private categories
  ;; Character.getType's constants, in the order the host is asked
  [["Lu" 1] ["Ll" 2] ["Lt" 3] ["Lm" 4] ["Lo" 5] ["Mn" 6] ["Me" 7] ["Mc" 8]
   ["Nd" 9] ["Nl" 10] ["No" 11] ["Zs" 12] ["Zl" 13] ["Zp" 14] ["Cc" 15]
   ["Cf" 16] ["Co" 18] ["Cs" 19] ["Pd" 20] ["Ps" 21] ["Pe" 22] ["Pc" 23]
   ["Po" 24] ["Sm" 25] ["Sc" 26] ["Sk" 27] ["So" 28] ["Pi" 29] ["Pf" 30]])

(def ^:private category-tests
  (mapv (fn [[c n]] [(test-re (str "\\p{" c "}")) n]) categories))

(defn- get-type [x]
  (let [cp (cp-of x)]
    (or (some (fn [[t n]] (when (t cp) n)) category-tests) 0)))

;; ---------------------------------------------------------------------------
;; numeric values
;; ---------------------------------------------------------------------------

(defn- decimal-value
  "The value of a \\p{Nd} code point: decimal digits come in contiguous runs
  of whole decades starting at a zero, so it is the distance from the start
  of the run, mod 10."
  [cp]
  (loop [start cp]
    (if (and (pos? start) (digit? (dec start))) (recur (dec start)) (mod (- cp start) 10))))

(defn- latin-letter-value [cp]
  (cond (<= 0x41 cp 0x5A) (+ 10 (- cp 0x41))
        (<= 0x61 cp 0x7A) (+ 10 (- cp 0x61))
        (<= 0xFF21 cp 0xFF3A) (+ 10 (- cp 0xFF21))
        (<= 0xFF41 cp 0xFF5A) (+ 10 (- cp 0xFF41))
        :else nil))

(def ^:private numeral-table
  ;; UnicodeData numeric values of the common non-decimal numerals; -2 is
  ;; the JDK's answer for a fraction
  (merge {0xB2 2 0xB3 3 0xB9 1 0x2070 0 0x3007 0 0xBC -2 0xBD -2 0xBE -2
          0x2189 0 0x215F 1}
         (into {} (map (fn [i] [(+ 0x2074 i) (+ 4 i)]) (range 6)))
         (into {} (map (fn [i] [(+ 0x2080 i) i]) (range 10)))
         (into {} (map (fn [i] [(+ 0x2150 i) -2]) (range 15)))
         (into {} (mapcat (fn [base] (map (fn [i] [(+ base i) (inc i)]) (range 12))) [0x2160 0x2170]))
         (into {} (mapcat (fn [base] [[(+ base 12) 50] [(+ base 13) 100] [(+ base 14) 500] [(+ base 15) 1000]]) [0x2160 0x2170]))
         (into {} (map (fn [i] [(+ 0x2460 i) (inc i)]) (range 20)))))

(defn- numeric-value [x]
  (let [cp (cp-of x)]
    (cond (digit? cp) (decimal-value cp)
          (latin-letter-value cp) (latin-letter-value cp)
          (contains? numeral-table cp) (get numeral-table cp)
          (numeric? cp) (throw (jvm/UnsupportedOperationException.
                                (str "Character.getNumericValue of U+" (str/upper-case (.toString cp 16))
                                     " (a non-decimal numeric character outside this engine's table) is deliberately not answered on this engine; see nbb.jvm.chars")))
          :else -1)))

(defn- digit-value [x radix]
  (if (or (< radix 2) (> radix 36)) -1
      (let [cp (cp-of x)
            v (cond (digit? cp) (decimal-value cp)
                    :else (latin-letter-value cp))]
        (if (and v (< v radix)) v -1))))

(defn- for-digit [d radix]
  (cond (or (< radix 2) (> radix 36) (neg? d) (>= d radix)) (unit-str 0)
        (< d 10) (unit-str (+ 48 d))
        :else (unit-str (+ 87 d))))

;; ---------------------------------------------------------------------------
;; simple case mappings
;; ---------------------------------------------------------------------------

(def ^:private simple-upper-exceptions
  ;; the characters whose full uppercase is several code points but whose
  ;; SIMPLE uppercase (the one Character uses) is a single titlecase letter
  (merge {0x1FB3 0x1FBC 0x1FC3 0x1FCC 0x1FF3 0x1FFC}
         (into {} (mapcat (fn [base] (map (fn [i] [(+ base i) (+ base i 8)]) (range 8))) [0x1F80 0x1F90 0x1FA0]))))

(defn- one-cp [s]
  (let [cp (.codePointAt s 0)]
    (when (= (count s) (if (> cp 0xFFFF) 2 1)) cp)))

(defn- simple-upper [cp]
  (or (get simple-upper-exceptions cp)
      (one-cp (.toUpperCase (cp-str cp)))
      cp))

(defn- simple-lower [cp]
  (if (= cp 0x130) 0x69
      (or (one-cp (.toLowerCase (cp-str cp))) cp)))

(defn- case-mapper [f]
  (fn [x] (if (string? x)
            (let [r (f (cp-of x))] (if (> r 0xFFFF) x (unit-str r)))
            (f (cp-of x)))))

;; ---------------------------------------------------------------------------
;; toChars, char[]
;; ---------------------------------------------------------------------------

(defn to-chars
  "Character/toChars: a char[] of one or two units."
  ([cp]
   (when-not (valid-cp? cp) (throw (invalid-cp cp)))
   (.split (js/String.fromCodePoint cp) ""))
  ([cp dst idx]
   (let [cs (to-chars cp)]
     (dotimes [i (alength cs)] (aset dst (+ idx i) (aget cs i)))
     (alength cs))))

(defn char*
  "clojure.core/char: range-checked as on the JVM ((char 0x1F600) throws
  instead of truncating to a different character)."
  [x]
  (cond (string? x) (if (= 1 (count x)) x (throw (js/TypeError. (str "class java.lang.String cannot be cast to class java.lang.Character"))))
        (number? x) (let [n (js/Math.trunc x)]
                      (if (<= 0 n 0xFFFF) (unit-str n)
                          (throw (jvm/IllegalArgumentException. (str "Value out of range for char: " x)))))
        :else (throw (js/TypeError. (str "Cannot coerce " (pr-str x) " to a char")))))

(defn- ->char-elem [x] (if (string? x) x (char* x)))

(defn char-array*
  "clojure.core/char-array: (char-array size-or-seq), (char-array size init)."
  ([size-or-seq]
   (cond (number? size-or-seq) (.fill (js/Array. size-or-seq) (unit-str 0))
         (nil? size-or-seq) #js []
         (string? size-or-seq) (.split size-or-seq "")
         :else (into-array (map ->char-elem size-or-seq))))
  ([size init]
   (let [a (.fill (js/Array. size) (unit-str 0))]
     (cond (string? init) (if (= 1 (count init))
                            (.fill a init)
                            (dotimes [i (min size (count init))] (aset a i (.charAt init i))))
           (number? init) (throw (jvm/IllegalArgumentException. "Don't know how to create ISeq from: java.lang.Long"))
           :else (loop [i 0 s (seq init)]
                   (when (and s (< i size))
                     (aset a i (->char-elem (first s)))
                     (recur (inc i) (next s)))))
     a)))

(defn aset-char* [a i c] (let [c (->char-elem c)] (aset a i c) c))

(defn char-array? [x] (and (array? x) (every? #(and (string? %) (= 1 (count %))) x)))

;; ---------------------------------------------------------------------------
;; the class
;; ---------------------------------------------------------------------------

(def Character
  (jvm/strict-statics
   "java.lang.Character"
   (js-obj
    "isDigit" digit?
    "isLetter" letter?
    "isLetterOrDigit" (fn [x] (or (letter? x) (digit? x)))
    "isAlphabetic" alphabetic?
    "isUpperCase" upper?
    "isLowerCase" lower?
    "isTitleCase" title?
    "isIdeographic" ideographic?
    "isWhitespace" whitespace?
    "isSpaceChar" space-char?
    "isDefined" defined?
    "isISOControl" (fn [x] (let [cp (cp-of x)] (or (<= 0 cp 0x1F) (<= 0x7F cp 0x9F))))
    "isSurrogate" (fn [x] (<= 0xD800 (cp-of x) 0xDFFF))
    "isHighSurrogate" (fn [x] (<= 0xD800 (cp-of x) 0xDBFF))
    "isLowSurrogate" (fn [x] (<= 0xDC00 (cp-of x) 0xDFFF))
    "isSurrogatePair" (fn [h l] (and (<= 0xD800 (cp-of h) 0xDBFF) (<= 0xDC00 (cp-of l) 0xDFFF)))
    "isValidCodePoint" (fn [cp] (valid-cp? cp))
    "isBmpCodePoint" (fn [cp] (<= 0 cp 0xFFFF))
    "isSupplementaryCodePoint" (fn [cp] (<= 0x10000 cp 0x10FFFF))
    "charCount" (fn [cp] (if (>= cp 0x10000) 2 1))
    "toCodePoint" (fn [h l] (+ (* (- (cp-of h) 0xD800) 0x400) (- (cp-of l) 0xDC00) 0x10000))
    "highSurrogate" (fn [cp] (unit-str (+ 0xD7C0 (bit-shift-right cp 10))))
    "lowSurrogate" (fn [cp] (unit-str (+ 0xDC00 (bit-and cp 0x3FF))))
    "codePointAt" (fn [s i] (let [s (if (array? s) (.join s "") (str s))]
                              (when-not (< -1 i (count s))
                                (throw (js/Error. (str "Index " i " out of bounds for length " (count s)))))
                              (.codePointAt s i)))
    "getType" get-type
    "getNumericValue" numeric-value
    "digit" digit-value
    "forDigit" for-digit
    "toUpperCase" (case-mapper simple-upper)
    "toLowerCase" (case-mapper simple-lower)
    "toChars" to-chars
    "toString" (fn [x] (if (string? x) x
                           (do (when-not (valid-cp? x) (throw (invalid-cp x)))
                               (js/String.fromCodePoint x))))
    "valueOf" (fn [x] (->char-elem x))
    "compare" (fn [a b] (- (cp-of a) (cp-of b)))
    "MIN_VALUE" (unit-str 0)
    "MAX_VALUE" (unit-str 0xFFFF)
    "MIN_RADIX" 2
    "MAX_RADIX" 36
    "MIN_CODE_POINT" 0
    "MAX_CODE_POINT" 0x10FFFF
    "MIN_SUPPLEMENTARY_CODE_POINT" 0x10000
    "MIN_HIGH_SURROGATE" (unit-str 0xD800)
    "MAX_HIGH_SURROGATE" (unit-str 0xDBFF)
    "MIN_LOW_SURROGATE" (unit-str 0xDC00)
    "MAX_LOW_SURROGATE" (unit-str 0xDFFF))
   ["TYPE" "SIZE" "BYTES"]))

(def ^:private to-char-array
  (js* "(function(){ return function toCharArray(){ return String(this).split(''); }; })()"))

(defn install! []
  (js/Object.defineProperty (.-prototype js/String) "toCharArray"
                            #js {"value" to-char-array "writable" true "configurable" true "enumerable" false}))

(defn classes []
  {'Character Character
   'java.lang.Character Character})
