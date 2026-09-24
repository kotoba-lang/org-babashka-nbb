(ns nbb.jvm.uuid
  "JVM-compatibility SCAFFOLDING, round 3: java.util.UUID (see nbb.jvm for why
  this exists; it is not a target for new code).

  A java.util.UUID is the value ClojureScript already has, cljs.core/UUID --
  the one `#uuid \"...\"`, `random-uuid` and `parse-uuid` make -- so
  `(= (UUID/fromString s) #uuid \"...\")` and `(instance? UUID (random-uuid))`
  hold as on the JVM. UUID/randomUUID (node:crypto, version 4),
  UUID/fromString (JDK 21: the strict 36-character form, else the lenient
  `1-2-3-4-5` form with each field masked to its width, and the JDK's
  IllegalArgumentException / NumberFormatException messages),
  UUID/nameUUIDFromBytes (MD5, version 3). Instance methods .equals
  .hashCode (the JDK's) .compareTo (signed 64-bit halves, as the JDK
  compares) .version .variant, and .getMostSignificantBits /
  .getLeastSignificantBits when the half fits in a double exactly.

  Documented deviations: `compare` / `sort` over UUIDs is ClojureScript's
  string order (the JVM compares signed halves, so 8000... sorts before
  0000... there; `.compareTo` here does what the JVM does); `hash` is
  ClojureScript's. Deliberately NOT here: (UUID. msb lsb) and a half beyond
  2^53 from getMost/LeastSignificantBits (a JS number cannot hold a long;
  the latter throws naming it), timestamp / clockSequence / node."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]))

(def ^:private hex-re #"[0-9a-fA-F]")

(defn- hex-str [v width]
  (let [s (.toString (js/BigInt.asUintN (* 4 width) v) 16)]
    (str (apply str (repeat (- width (count s)) "0")) s)))

(defn- from-bits
  "msb/lsb (BigInt, any sign) -> cljs UUID in the JDK's lowercase form."
  [msb lsb]
  (uuid (str (hex-str (js* "(~{} >> 32n)" msb) 8) "-"
             (hex-str (js* "(~{} >> 16n)" msb) 4) "-"
             (hex-str msb 4) "-"
             (hex-str (js* "(~{} >> 48n)" lsb) 4) "-"
             (hex-str lsb 12))))

(defn- bits
  "cljs UUID -> [msb lsb] as signed 64-bit BigInts."
  [u]
  (let [h (str/replace (str u) "-" "")]
    [(js/BigInt.asIntN 64 (js/BigInt (str "0x" (subs h 0 16))))
     (js/BigInt.asIntN 64 (js/BigInt (str "0x" (subs h 16 32))))]))

(defn- parse-hex-long
  "Long.parseLong(s, begin, end, 16) with its JDK 21 messages; the value as a
  BigInt (it is masked by the caller)."
  [s b e]
  (let [len (- e b)
        seg (subs s b e)
        err (fn [i] (jvm/NumberFormatException. (str "Error at index " i " in: \"" seg "\"")))]
    (when (<= len 0) (throw (jvm/NumberFormatException. "")))
    (let [first-ch (.charAt seg 0)
          [neg start] (cond (= "-" first-ch) [true 1]
                            (= "+" first-ch) [false 1]
                            (re-matches hex-re first-ch) [false 0]
                            :else (throw (err 0)))]
      (when (and (= 1 start) (= 1 len)) (throw (err 1)))
      (let [limit (if neg (js/BigInt "9223372036854775808") (js/BigInt "9223372036854775807"))]
        (loop [i start acc (js/BigInt 0)]
          (if (< i len)
            (let [ch (.charAt seg i)]
              (when-not (re-matches hex-re ch) (throw (err i)))
              (let [acc (+ (* acc (js/BigInt 16)) (js/BigInt (js/parseInt ch 16)))]
                (when (> acc limit) (throw (err i)))
                (recur (inc i) acc)))
            (if neg (- acc) acc)))))))

(defn- mask [v bits*] (js/BigInt.asUintN bits* v))

(defn from-string
  "UUID.fromString (JDK 21)."
  [s]
  (when (nil? s) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"name\" is null")))
  (let [s (str s)
        len (count s)]
    (if (and (= 36 len)
             (= "-" (.charAt s 8) (.charAt s 13) (.charAt s 18) (.charAt s 23))
             (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" s))
      (uuid (str/lower-case s))
      (do
        (when (> len 36) (throw (jvm/IllegalArgumentException. "UUID string too large")))
        (let [d1 (.indexOf s "-" 0)
              d2 (.indexOf s "-" (inc d1))
              d3 (.indexOf s "-" (inc d2))
              d4 (.indexOf s "-" (inc d3))
              d5 (.indexOf s "-" (inc d4))]
          (when (or (neg? d4) (>= d5 0)) (throw (jvm/IllegalArgumentException. (str "Invalid UUID string: " s))))
          (let [f1 (mask (parse-hex-long s 0 d1) 32)
                f2 (mask (parse-hex-long s (inc d1) d2) 16)
                f3 (mask (parse-hex-long s (inc d2) d3) 16)
                f4 (mask (parse-hex-long s (inc d3) d4) 16)
                f5 (mask (parse-hex-long s (inc d4) len) 48)
                msb (js* "((~{} << 32n) | (~{} << 16n) | ~{})" f1 f2 f3)
                lsb (js* "((~{} << 48n) | ~{})" f4 f5)]
            (from-bits msb lsb)))))))

(defn random-uuid* [] (uuid (.randomUUID crypto)))

(defn name-uuid-from-bytes
  "UUID.nameUUIDFromBytes: MD5 of the bytes, version 3, IETF variant."
  [b]
  (let [h (.digest (doto (.createHash crypto "md5") (.update (js/Buffer.from (jb/u8 b)))))]
    (aset h 6 (bit-or (bit-and (aget h 6) 0x0f) 0x30))
    (aset h 8 (bit-or (bit-and (aget h 8) 0x3f) 0x80))
    (let [x (.toString h "hex")]
      (uuid (str (subs x 0 8) "-" (subs x 8 12) "-" (subs x 12 16) "-" (subs x 16 20) "-" (subs x 20 32))))))

(defn- as-number [v what]
  (if (<= (js/BigInt js/Number.MIN_SAFE_INTEGER) v (js/BigInt js/Number.MAX_SAFE_INTEGER))
    (js/Number v)
    (throw (jvm/UnsupportedOperationException.
            (str "UUID." what " = " v " does not fit in a JS number (a long beyond 2^53); deliberately not answered on this engine, see nbb.jvm.uuid")))))

(defn- cmp [a b] (cond (< a b) -1 (> a b) 1 :else 0))

(def ^:private uuid-methods
  {"equals" (fn [o] (this-as this (and (instance? UUID o) (= (str/lower-case (str this)) (str/lower-case (str o))))))
   "hashCode" (fn [] (this-as this (let [[m l] (bits this)
                                         hilo (bit-xor m l)]
                                     (js/Number (js/BigInt.asIntN 32 (bit-xor (js* "(~{} >> 32n)" hilo) hilo))))))
   "compareTo" (fn [o] (this-as this (let [[m1 l1] (bits this) [m2 l2] (bits o)]
                                       (let [c (cmp m1 m2)] (if (zero? c) (cmp l1 l2) c)))))
   "version" (fn [] (this-as this (let [[m _] (bits this)] (js/Number (bit-and (js* "(~{} >> 12n)" m) (js/BigInt 0x0f))))))
   "variant" (fn [] (this-as this (let [[_ l] (bits this)
                                        u (js/BigInt.asUintN 64 l)
                                        shift (- (js/BigInt 64) (js* "(~{} >> 62n)" u))]
                                    (js/Number (js/BigInt.asIntN 32 (bit-and (js* "(~{} >> ~{})" u shift) (js* "(~{} >> 63n)" l)))))))
   "getMostSignificantBits" (fn [] (this-as this (as-number (first (bits this)) "getMostSignificantBits")))
   "getLeastSignificantBits" (fn [] (this-as this (as-number (second (bits this)) "getLeastSignificantBits")))})

(def UUIDClass
  (let [c (js* "(function(U){ var C = function UUID(){ throw new Error('(UUID. msb lsb) is deliberately not available on this engine (a JS number cannot hold a long); use UUID/fromString'); }; Object.defineProperty(C, Symbol.hasInstance, {value: function(x){ return x instanceof U; }}); return C; })(~{})"
               UUID)]
    (gobj/set c "randomUUID" random-uuid*)
    (gobj/set c "fromString" from-string)
    (gobj/set c "nameUUIDFromBytes" name-uuid-from-bytes)
    c))

(defn install! []
  (let [p (.-prototype UUID)]
    (doseq [[k f] uuid-methods]
      (when-not (js-in k p)
        (js/Object.defineProperty p k #js {"value" f "writable" true "configurable" true "enumerable" false})))))

(defn classes []
  {'java.util.UUID UUIDClass})
