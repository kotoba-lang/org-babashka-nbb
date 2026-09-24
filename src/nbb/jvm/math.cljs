(ns nbb.jvm.math
  "JVM-compatibility SCAFFOLDING, round 2: java.lang.Math beyond js/Math, and
  java.math.BigInteger over JS BigInt (see nbb.jvm for why this exists; it is
  not a target for new code).

  Math: every js/Math member, plus signum floorDiv floorMod ceilDiv toRadians
  toDegrees rint ulp copySign getExponent nextUp nextDown IEEEremainder
  addExact subtractExact multiplyExact negateExact incrementExact
  decrementExact toIntExact absExact fma, and Math/round with the JVM's NaN ->
  0. The *Exact family checks the int range (the JVM picks int or long by
  the static type; a JS number cannot say which, so an argument outside the
  int range is checked against 2^53 instead -- documented deviation).

  BigInteger: exact (BigInt). (= a b) between BigIntegers is value equality
  and (= bi 5) is true as on the JVM, but (= 5 bi) is false here (the number
  side compares by identity); arithmetic through clojure.core (+ bi 1)
  answers a JS number when the result is within 2^53 and throws naming
  BigInteger otherwise (the JVM answers a clojure.lang.BigInt: same digits,
  printed with an N)."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]))

(def ArithmeticException (jvm/defclass "java.lang.ArithmeticException" jvm/RuntimeException))

;; ---------------------------------------------------------------------------
;; java.lang.Math
;; ---------------------------------------------------------------------------

(def ^:private int-min -2147483648)
(def ^:private int-max 2147483647)

(defn- exact2 [f]
  (fn [a b]
    (let [r (f a b)
          ints? (and (<= int-min a int-max) (<= int-min b int-max))]
      (cond (and ints? (or (< r int-min) (> r int-max))) (throw (ArithmeticException. "integer overflow"))
            (not (js/Number.isSafeInteger r)) (throw (ArithmeticException. "long overflow"))
            :else r))))

(defn- exact1 [f]
  (fn [a]
    (let [r (f a)]
      (cond (and (<= int-min a int-max) (or (< r int-min) (> r int-max))) (throw (ArithmeticException. "integer overflow"))
            (not (js/Number.isSafeInteger r)) (throw (ArithmeticException. "long overflow"))
            :else r))))

(defn- floor-div [a b]
  (when (zero? b) (throw (ArithmeticException. "/ by zero")))
  (js/Math.floor (/ a b)))

(defn- floor-mod [a b]
  (when (zero? b) (throw (ArithmeticException. "/ by zero")))
  (- a (* b (js/Math.floor (/ a b)))))

(defn- rint [x]
  ;; round half to even; a zero result keeps x's sign (rint(-0.4) is -0.0)
  (if (or (js/Number.isNaN x) (not (js/Number.isFinite x)) (zero? x)) x
      (let [f (js/Math.floor x) d (- x f)
            r (cond (< d 0.5) f
                    (> d 0.5) (inc f)
                    :else (if (even? f) f (inc f)))]
        (if (and (zero? r) (neg? x)) -0.0 r))))

(defn- ulp [x]
  (cond (js/Number.isNaN x) js/NaN
        (not (js/Number.isFinite x)) js/Number.POSITIVE_INFINITY
        :else (let [x (js/Math.abs x)]
                (if (= x js/Number.MAX_VALUE) (js/Math.pow 2 971)
                    (let [buf (js/Float64Array. 1) bits (js/BigInt64Array. (.-buffer buf))]
                      (aset buf 0 x)
                      (aset bits 0 (+ (aget bits 0) (js/BigInt 1)))
                      (- (aget buf 0) x))))))

(defn- next-after [x up?]
  (cond (js/Number.isNaN x) x
        (and up? (= x js/Number.POSITIVE_INFINITY)) x
        (and (not up?) (= x js/Number.NEGATIVE_INFINITY)) x
        (zero? x) (if up? js/Number.MIN_VALUE (- js/Number.MIN_VALUE))
        :else (let [buf (js/Float64Array. 1) bits (js/BigInt64Array. (.-buffer buf))]
                (aset buf 0 x)
                (aset bits 0 (+ (aget bits 0) (js/BigInt (if (= (pos? x) up?) 1 -1))))
                (aget buf 0))))

(defn- get-exponent [x]
  (cond (or (js/Number.isNaN x) (not (js/Number.isFinite x))) 1024
        (zero? x) -1023
        :else (let [buf (js/Float64Array. 1) u (js/Uint32Array. (.-buffer buf))]
                (aset buf 0 x)
                (- (bit-and (unsigned-bit-shift-right (aget u 1) 20) 0x7ff) 1023))))

(defn- ieee-remainder [a b]
  (if (or (js/Number.isNaN a) (js/Number.isNaN b) (not (js/Number.isFinite a)) (zero? b)) js/NaN
      (if (not (js/Number.isFinite b)) a
          (let [n (rint (/ a b))] (- a (* n b))))))

(defn- signum [x]
  (cond (js/Number.isNaN x) x
        (zero? x) x
        (pos? x) 1.0
        :else -1.0))

(def JMath
  (let [m (js/Object.create js/Math)
        extras {"signum" signum
                "round" (fn [x] (if (js/Number.isNaN x) 0 (js/Math.round x)))
                "rint" rint
                "floorDiv" floor-div
                "floorMod" floor-mod
                "ceilDiv" (fn [a b] (when (zero? b) (throw (ArithmeticException. "/ by zero"))) (js/Math.ceil (/ a b)))
                "toRadians" (fn [d] (* (/ d 180.0) js/Math.PI))
                "toDegrees" (fn [r] (* (/ r js/Math.PI) 180.0))
                "ulp" ulp
                "nextUp" (fn [x] (next-after x true))
                "nextDown" (fn [x] (next-after x false))
                "nextAfter" (fn [x d] (cond (or (js/Number.isNaN x) (js/Number.isNaN d)) js/NaN
                                            (= x d) d
                                            :else (next-after x (< x d))))
                "copySign" (fn [m s] (let [neg (or (neg? s) (and (zero? s) (neg? (/ 1 s))))]
                                       (if neg (- (js/Math.abs m)) (js/Math.abs m))))
                "getExponent" get-exponent
                "IEEEremainder" ieee-remainder
                "addExact" (exact2 +)
                "subtractExact" (exact2 -)
                "multiplyExact" (exact2 *)
                "negateExact" (exact1 -)
                "incrementExact" (exact1 inc)
                "decrementExact" (exact1 dec)
                "absExact" (exact1 #(js/Math.abs %))
                "toIntExact" (fn [x] (if (<= int-min x int-max) x (throw (ArithmeticException. "integer overflow"))))
                "fma" (fn [a b c] (+ (* a b) c))
                "TAU" (* 2 js/Math.PI)}]
    (doseq [[k v] extras] (gobj/set m k v))
    m))

;; ---------------------------------------------------------------------------
;; java.math.BigInteger
;; ---------------------------------------------------------------------------

(def ^:private big0 (js/BigInt 0))
(def ^:private big1 (js/BigInt 1))
(def ^:private max-safe (js/BigInt js/Number.MAX_SAFE_INTEGER))

(declare bi)

(defn- bi-abs [v] (if (< v big0) (- v) v))

(defn- bit-length [v]
  ;; ceil(log2(v < 0 ? -v : v+1)), as BigInteger.bitLength
  (let [x (if (< v big0) (- (- v) big1) v)]
    (if (= x big0) 0 (count (.toString x 2)))))

(defn- to-byte-array [v]
  (let [n (inc (quot (bit-length v) 8))
        a (js/Int8Array. n)
        mask (js/BigInt 255)]
    (loop [i (dec n) x v]
      (when (>= i 0)
        (aset a i (js/Number (js/BigInt.asIntN 8 (bit-and x mask))))
        (recur (dec i) (js* "(~{} >> 8n)" x))))
    a))

(defn- from-twos [^js bytes]
  (if (zero? (.-length (jb/u8 bytes)))
    (throw (jvm/NumberFormatException. "Zero length BigInteger"))
    (let [u (jb/u8 bytes)
          hex (.join (.map (js/Array.from u) (fn [b] (.padStart (.toString b 16) 2 "0"))) "")
          v (js/BigInt (str "0x" hex))]
      (js/BigInt.asIntN (* 8 (.-length u)) v))))

(defn- from-magnitude [signum ^js bytes]
  (when-not (<= -1 signum 1) (throw (jvm/NumberFormatException. "Invalid signum value")))
  (let [u (jb/u8 bytes)
        hex (.join (.map (js/Array.from u) (fn [b] (.padStart (.toString b 16) 2 "0"))) "")
        m (if (= "" hex) big0 (js/BigInt (str "0x" hex)))]
    (when (and (zero? signum) (not= m big0)) (throw (jvm/NumberFormatException. "signum-magnitude mismatch")))
    (if (neg? signum) (- m) m)))

(defn- parse-big [s radix]
  (when (nil? s) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"val\" is null")))
  (when (or (< radix 2) (> radix 36)) (throw (jvm/NumberFormatException. "Radix out of range")))
  (let [s (str s)
        len (count s)]
    (when (zero? len) (throw (jvm/NumberFormatException. "Zero length BigInteger")))
    (let [minus (str/last-index-of s "-")
          plus (str/last-index-of s "+")
          sign-at (cond (and minus (pos? minus)) minus (and plus (pos? plus)) plus)]
      (when sign-at (throw (jvm/NumberFormatException. "Illegal embedded sign character")))
      (let [neg (str/starts-with? s "-")
            digits (if (or neg (str/starts-with? s "+")) (subs s 1) s)]
        (when (zero? (count digits)) (throw (jvm/NumberFormatException. "Zero length BigInteger")))
        (let [big-r (js/BigInt radix)
              v (reduce (fn [acc ch]
                          (let [d (js/parseInt ch radix)]
                            (if (js/isNaN d)
                              (throw (jvm/NumberFormatException. (str "For input string: \"" s "\""
                                                                       (when (not= radix 10) (str " under radix " radix)))))
                              (+ (* acc big-r) (js/BigInt d)))))
                        big0 digits)]
          (if neg (- v) v))))))

(defn- arg [x] (cond (instance? js/BigInt x) x
                     (number? x) (js/BigInt (js/Math.trunc x))
                     (= "bigint" (goog/typeOf x)) x
                     :else (.-v ^js x)))

(defn- pos-mod [a m] (let [r (js-mod a m)] (if (< r big0) (+ r m) r)))

(defn- mod-pow [b e m]
  (when (<= m big0) (throw (ArithmeticException. "BigInteger: modulus not positive")))
  (if (< e big0)
    (throw (ArithmeticException. "BigInteger: negative exponent not supported on this engine"))
    (loop [r (js-mod big1 m) b (pos-mod b m) e e]
      (if (= e big0) r
          (recur (if (= big1 (bit-and e big1)) (js-mod (* r b) m) r)
                 (js-mod (* b b) m)
                 (js* "(~{} >> 1n)" e))))))

(defn- egcd [a b]
  (loop [old-r a r b old-s big1 s big0]
    (if (= r big0) [old-r old-s]
        (let [q (js* "(~{} / ~{})" old-r r)]
          (recur r (- old-r (* q r)) s (- old-s (* q s)))))))

(defn- isqrt [n]
  (when (< n big0) (throw (ArithmeticException. "Negative BigInteger")))
  (if (< n (js/BigInt 2)) n
      (loop [x n]
        (let [y (js* "((~{} + ~{} / ~{}) >> 1n)" x n x)]
          (if (>= y x) x (recur y))))))

(defn- java-hash
  "BigInteger.hashCode: 31*h + int-word over the magnitude's 32-bit words,
  times signum."
  [v]
  (let [m (bi-abs v)
        words (loop [x m acc '()]
                (if (= x big0) acc
                    (recur (js* "(~{} >> 32n)" x) (cons (js/Number (js/BigInt.asUintN 32 x)) acc))))
        h (reduce (fn [h w] (bit-or (+ (js/Math.imul 31 h) w) 0)) 0 words)]
    (bit-or (* h (cond (< v big0) -1 (> v big0) 1 :else 0)) 0)))

(deftype BigInteger [v]
  Object
  (add [_ o] (bi (+ v (arg o))))
  (subtract [_ o] (bi (- v (arg o))))
  (multiply [_ o] (bi (* v (arg o))))
  (divide [_ o] (let [d (arg o)] (when (= d big0) (throw (ArithmeticException. "BigInteger divide by zero"))) (bi (js* "(~{} / ~{})" v d))))
  (remainder [_ o] (let [d (arg o)] (when (= d big0) (throw (ArithmeticException. "BigInteger divide by zero"))) (bi (js-mod v d))))
  (divideAndRemainder [_ o] (let [d (arg o)] (when (= d big0) (throw (ArithmeticException. "BigInteger divide by zero")))
                              (array (bi (js* "(~{} / ~{})" v d)) (bi (js-mod v d)))))
  (mod [_ o] (let [m (arg o)] (when (<= m big0) (throw (ArithmeticException. "BigInteger: modulus not positive"))) (bi (pos-mod v m))))
  (modPow [_ e m] (bi (mod-pow v (arg e) (arg m))))
  (modInverse [_ m] (let [m (arg m)]
                      (when (<= m big0) (throw (ArithmeticException. "BigInteger: modulus not positive")))
                      (let [[g s] (egcd (pos-mod v m) m)]
                        (if (not= g big1)
                          (throw (ArithmeticException. "BigInteger not invertible."))
                          (bi (pos-mod s m))))))
  (gcd [_ o] (bi (bi-abs (first (egcd (bi-abs v) (bi-abs (arg o)))))))
  (pow [_ e] (when (neg? e) (throw (ArithmeticException. "Negative exponent"))) (bi (js* "(~{} ** ~{})" v (js/BigInt e))))
  (sqrt [_] (bi (isqrt v)))
  (negate [_] (bi (- v)))
  (abs [_] (bi (bi-abs v)))
  (signum [_] (cond (< v big0) -1 (> v big0) 1 :else 0))
  (compareTo [_ o] (let [w (arg o)] (cond (< v w) -1 (> v w) 1 :else 0)))
  (min [this o] (if (<= v (arg o)) this o))
  (max [this o] (if (>= v (arg o)) this o))
  (equals [_ o] (and (instance? BigInteger o) (= v (.-v ^js o))))
  (hashCode [_] (java-hash v))
  (shiftLeft [_ n] (bi (if (neg? n) (js* "(~{} >> ~{})" v (js/BigInt (- n))) (js* "(~{} << ~{})" v (js/BigInt n)))))
  (shiftRight [_ n] (bi (if (neg? n) (js* "(~{} << ~{})" v (js/BigInt (- n))) (js* "(~{} >> ~{})" v (js/BigInt n)))))
  (and [_ o] (bi (bit-and v (arg o))))
  (or [_ o] (bi (bit-or v (arg o))))
  (xor [_ o] (bi (bit-xor v (arg o))))
  (not [_] (bi (- (- v) big1)))
  (andNot [_ o] (bi (bit-and v (- (- (arg o)) big1))))
  (testBit [_ n] (when (neg? n) (throw (ArithmeticException. "Negative bit address")))
    (not= big0 (bit-and (js* "(~{} >> ~{})" v (js/BigInt n)) big1)))
  (setBit [_ n] (bi (bit-or v (js* "(1n << ~{})" (js/BigInt n)))))
  (clearBit [_ n] (bi (bit-and v (- (- (js* "(1n << ~{})" (js/BigInt n))) big1))))
  (flipBit [_ n] (bi (bit-xor v (js* "(1n << ~{})" (js/BigInt n)))))
  (bitLength [_] (bit-length v))
  (bitCount [_] (let [x (if (< v big0) (- (- v) big1) v)]
                  (count (filter #(= "1" %) (.toString x 2)))))
  (getLowestSetBit [_] (if (= v big0) -1
                           (loop [i 0] (if (= big0 (bit-and (js* "(~{} >> ~{})" v (js/BigInt i)) big1)) (recur (inc i)) i))))
  (isProbablePrime [_ _certainty]
    ;; deterministic Miller-Rabin for n < 3.3e24 with these bases; beyond
    ;; that the same bases give a probable-prime answer as the JVM's does
    (let [n (bi-abs v)]
      (cond (< n (js/BigInt 2)) false
            (< n (js/BigInt 4)) true
            (= big0 (bit-and n big1)) false
            :else
            (let [n-1 (- n big1)
                  [d s] (loop [d n-1 s 0] (if (= big0 (bit-and d big1)) (recur (js* "(~{} >> 1n)" d) (inc s)) [d s]))]
              (every? (fn [a]
                        (let [a (js/BigInt a)]
                          (or (>= a n)
                              (let [x (mod-pow a d n)]
                                (or (= x big1) (= x n-1)
                                    (loop [x x r 1]
                                      (cond (>= r s) false
                                            :else (let [x (js-mod (* x x) n)]
                                                    (if (= x n-1) true (recur x (inc r)))))))))))
                      [2 3 5 7 11 13 17 19 23 29 31 37 41])))))
  (toByteArray [_] (to-byte-array v))
  (longValue [_] (js/Number (js/BigInt.asIntN 64 v)))
  (intValue [_] (js/Number (js/BigInt.asIntN 32 v)))
  (shortValue [_] (js/Number (js/BigInt.asIntN 16 v)))
  (byteValue [_] (js/Number (js/BigInt.asIntN 8 v)))
  (doubleValue [_] (js/Number v))
  (floatValue [_] (js/Math.fround (js/Number v)))
  (longValueExact [_] (if (= v (js/BigInt.asIntN 64 v))
                        (if (<= (bi-abs v) max-safe) (js/Number v)
                            (throw (ArithmeticException. "BigInteger out of 2^53 range (not representable on this engine)")))
                        (throw (ArithmeticException. "BigInteger out of long range"))))
  (intValueExact [_] (if (= v (js/BigInt.asIntN 32 v)) (js/Number v) (throw (ArithmeticException. "BigInteger out of int range"))))
  (toString [_] (.toString v))
  (toString [_ radix] (.toString v (if (<= 2 radix 36) radix 10)))
  (valueOf [_] (if (<= (bi-abs v) max-safe) (js/Number v)
                   (throw (ArithmeticException. (str "java.math.BigInteger " v " used as a number: beyond 2^53 this engine's numbers cannot hold it (use its methods)")))))
  IEquiv
  (-equiv [_ o] (cond (instance? BigInteger o) (= v (.-v ^js o))
                      (and (number? o) (js/Number.isInteger o)) (= v (js/BigInt o))
                      :else false))
  IHash
  (-hash [_] (if (<= (bi-abs v) max-safe) (hash (js/Number v)) (hash (.toString v))))
  IComparable
  (-compare [_ o] (let [w (arg o)] (cond (< v w) -1 (> v w) 1 :else 0)))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (.toString v))))

(defn bi [v] (BigInteger. v))

(defn new-big-integer
  "(BigInteger. \"123\") (BigInteger. \"ff\" 16) (BigInteger. bytes)
  (BigInteger. signum magnitude)"
  ([x] (if (string? x) (bi (parse-big x 10)) (bi (from-twos x))))
  ([a b] (if (string? a) (bi (parse-big a b)) (bi (from-magnitude a b)))))

(defn biginteger
  "clojure.core/biginteger"
  [x]
  (cond (instance? BigInteger x) x
        (number? x) (bi (js/BigInt (js/Math.trunc x)))
        (string? x) (bi (parse-big x 10))
        :else (throw (jvm/IllegalArgumentException. (str "Cannot coerce " (pr-str x) " to a BigInteger on this engine")))))

(def BigIntegerClass
  (let [c (js* "(function(){ var B = function BigInteger(){}; return B; })()")]
    (set! (.-prototype c) (.-prototype BigInteger))
    (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" (fn [x] (instance? BigInteger x))})
    (gobj/set c "valueOf" (fn [n] (bi (js/BigInt (js/Math.trunc n)))))
    (gobj/set c "ZERO" (bi big0))
    (gobj/set c "ONE" (bi big1))
    (gobj/set c "TWO" (bi (js/BigInt 2)))
    (gobj/set c "TEN" (bi (js/BigInt 10)))
    (gobj/set c "probablePrime" (fn [& _] (throw (jvm/UnsupportedOperationException. "BigInteger/probablePrime is not available on this engine"))))
    c))

(defn classes []
  {'Math JMath
   'java.lang.Math JMath
   'StrictMath JMath
   'java.lang.StrictMath JMath
   'ArithmeticException ArithmeticException
   'java.lang.ArithmeticException ArithmeticException
   'java.math.BigInteger {:class BigIntegerClass
                          :constructor (with-meta 'java.math.BigInteger {:sci.impl/constructor new-big-integer})}})
