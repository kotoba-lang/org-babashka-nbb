(ns nbb.bigint-hash
  "`cljs.core/hash` for a JavaScript BigInt.

  cljs.core has no IHash case for BigInt, so `hash` fell through to the
  `default` branch, `goog/getUid`, which stores a property on its argument --
  and a primitive bigint cannot hold one. Every hash of a BigInt threw
  `Cannot create property 'closure_uid_...' on bigint '5'`. Measured
  2026-09-25 on 95ff779: `(hash 5n)` throws, `(set (map js/BigInt (range 9)))`
  throws, `(set (map js/BigInt (range 8)))` works -- a PersistentArraySet (up
  to eight elements) compares with `=` and never hashes, the ninth element
  moves it to a hashed set. Any map with more than eight BigInt keys, or any
  BigInt inside a vector / map that is itself hashed, failed the same way.

  It is extended as the base type `bigint` (dispatch on `goog/typeOf`), not
  as `js/BigInt` (a property on BigInt.prototype, which the build warns
  against). A boxed `Object(5n)` is typeOf `object` and keeps the identity
  hash; nothing produces one.

  Sci refuses `(extend-type js/BigInt IHash ...)` from a script (\"can only
  be extended natively to types created with deftype or defrecord\"), and
  cljs.core's protocol property names are renamed by the advanced build, so
  the engine is the one place this can be fixed.

  Equality needs nothing: `=` tries `identical?` first, which is `===`, and
  `===` compares primitive bigints by value. `(= 5n 5)` stays false, so the
  hash is free to differ from a number's.

  The value is Clojure-on-the-JVM's `hash` of the same integer: Murmur3
  `hashLong` inside the signed 64-bit range (what a Long and a BigInt that
  fits one hash to), `java.math.BigInteger#hashCode` beyond it (what
  `clojure.lang.BigInt` hashes to there). A reader that yields a Long on the
  JVM and a BigInt here then builds sets and maps that iterate in the same
  order on both hosts. The expected values in
  test-scripts/bigint_hash_test.cljs are a JDK / Clojure 1.12 oracle run.")

(def ^:private zero (js/BigInt 0))
(def ^:private two32 (js/BigInt 4294967296))
(def ^:private min-i64 (js/BigInt "-9223372036854775808"))
(def ^:private max-i64 (js/BigInt "9223372036854775807"))

(defn- murmur3-hash-long
  "clojure.lang.Murmur3/hashLong, seed 0, of N in the signed 64-bit range."
  [n]
  (if (identical? n zero)
    0
    (let [u (js/BigInt.asUintN 64 n)
          low (js/Number (js/BigInt.asIntN 32 u))
          high (js/Number (js/BigInt.asIntN 32 (/ u two32)))
          h1 (m3-mix-H1 0 (m3-mix-K1 low))
          h1 (m3-mix-H1 h1 (m3-mix-K1 high))]
      (m3-fmix h1 8))))

(defn- hash-big
  "java.math.BigInteger#hashCode: the magnitude's 32-bit words, most
  significant first, folded as h = (int)(31*h + word), times the signum."
  [n]
  (let [negative? (< n zero)
        words (loop [m (if negative? (- n) n) acc ()]
                (if (identical? m zero)
                  acc
                  (recur (/ m two32)
                         (cons (js/Number (js/BigInt.asUintN 32 m)) acc))))
        h (reduce (fn [h word] (bit-or (+ (imul 31 h) word) 0)) 0 words)]
    (if negative? (imul h -1) h)))

(defn bigint-hash
  "The hash of the integer X (a BigInt)."
  [x]
  (let [n (js/BigInt x)]
    (if (and (<= min-i64 n) (<= n max-i64))
      (murmur3-hash-long n)
      (hash-big n))))

(extend-type bigint
  IHash
  (-hash [x] (bigint-hash x)))
