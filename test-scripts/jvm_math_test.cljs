;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/math.cljs):
;; java.lang.Math beyond js/Math, and java.math.BigInteger over BigInt.
;;   node cli.js test-scripts/jvm_math_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): the Math cases "THREW
;; Math.signum is not a function" / "... floorMod ..." (Math was js/Math) --
;; com-nvidia-isaac-sim's only failing test (`(Math/signum (v3/dot ...))`);
;; the BigInteger cases "THREW Unable to resolve classname:
;; java.math.BigInteger" -- kotoba-lang/inference's first failure.
;; Expected values are JDK 21 / Clojure 1.12 answers (oracle 2026-09-24):
;; Math/round NaN -> 0, rint half-even, floorMod sign of the divisor,
;; BigInteger mod vs remainder on negatives, toByteArray two's complement
;; minimal (255 -> [0 -1], -128 -> [-128]), the constructors' messages.
;; The last cases pin the documented deviations: (= 5 bi) is false here
;; (true on the JVM) and a BigInteger beyond 2^53 refuses to become a number.
(ns jvm-math-test)

(try (load-string "(import (quote [java.math BigInteger]))") (catch :default e (println "import:" (ex-message e))))

(def cases
  [["Math/signum" "[(Math/signum 3.2) (Math/signum -2.0) (Math/signum 0.0)]" [1 -1 0]]
   ["Math/signum -0.0 keeps the sign, NaN stays NaN" "[(js/Object.is (Math/signum -0.0) -0.0) (js/Number.isNaN (Math/signum ##NaN))]" [true true]]
   ["Math/round NaN is 0, half up" "[(Math/round ##NaN) (Math/round -2.5) (Math/round 2.5) (Math/round 0.49999999999999994)]" [0 -2 3 0]]
   ["Math/rint rounds half to even" "[(Math/rint 2.5) (Math/rint 3.5) (Math/rint -2.5)]" [2 4 -2]]
   ["Math/rint of -0.4 is -0.0" "(js/Object.is (Math/rint -0.4) -0.0)" true]
   ["Math/floorDiv floorMod" "[(Math/floorDiv -7 2) (Math/floorMod -7 2) (Math/floorMod 7 -2)]" [-4 1 -1]]
   ["Math/floorDiv by zero: ArithmeticException" "(try (Math/floorDiv 1 0) (catch ArithmeticException e (ex-message e)))" "/ by zero"]
   ["Math/toRadians toDegrees" "[(Math/toRadians 180) (Math/toDegrees Math/PI)]" [js/Math.PI 180]]
   ["Math/cbrt log10 hypot (js/Math members still there)" "[(Math/cbrt 27) (Math/log10 1000) (Math/hypot 3 4) (Math/abs -2) (Math/sqrt 16)]" [3 3 5 2 4]]
   ["Math/ulp nextUp copySign getExponent IEEEremainder"
    "[(Math/ulp 1.0) (Math/nextUp 1.0) (Math/copySign 3.0 -0.0) (Math/getExponent 8.0) (Math/IEEEremainder 5 3)]"
    [2.220446049250313E-16 1.0000000000000002 -3 3 -1]]
   ["Math/addExact int overflow" "(try (Math/addExact 2147483647 1) (catch ArithmeticException e (ex-message e)))" "integer overflow"]
   ["Math/addExact within int" "(Math/addExact 2147483646 1)" 2147483647]
   ["java.lang.Math fully qualified" "(java.lang.Math/signum -5.0)" -1]
   ;; BigInteger
   ["BigInteger from string, toString radix" "[(str (BigInteger. \"123456789012345678901234567890\")) (.toString (BigInteger. \"-255\") 16)]"
    ["123456789012345678901234567890" "-ff"]]
   ["toByteArray is minimal two's complement"
    "(mapv #(vec (.toByteArray (BigInteger/valueOf %))) [255 -129 0 -1 -128])"
    [[0 -1] [-1 127] [0] [-1] [-128]]]
   ["(BigInteger. bytes) is two's complement" "(str (BigInteger. (byte-array [-1 0])))" "-256"]
   ["(BigInteger. signum magnitude)" "[(str (BigInteger. 1 (byte-array [-1 0]))) (str (BigInteger. -1 (byte-array [-1])))]" ["65280" "-255"]]
   ["signum-magnitude mismatch" "(try (BigInteger. 0 (byte-array [1])) (catch NumberFormatException e (ex-message e)))" "signum-magnitude mismatch"]
   ["mod vs remainder vs divide on negatives"
    "(let [a (BigInteger/valueOf -7) b (BigInteger/valueOf 3)] [(str (.mod a b)) (str (.remainder a b)) (str (.divide a (BigInteger/valueOf 2)))])"
    ["2" "-1" "-3"]]
   ["divideAndRemainder" "(mapv str (.divideAndRemainder (BigInteger/valueOf -7) (BigInteger/valueOf 2)))" ["-3" "-1"]]
   ["divide by zero" "(try (.divide BigInteger/ONE BigInteger/ZERO) (catch ArithmeticException e (ex-message e)))" "BigInteger divide by zero"]
   ["mod by a non-positive modulus" "[(try (.mod BigInteger/ONE BigInteger/ZERO) (catch ArithmeticException e (ex-message e))) (try (.mod BigInteger/ONE (BigInteger/valueOf -2)) (catch ArithmeticException e (ex-message e)))]"
    ["BigInteger: modulus not positive" "BigInteger: modulus not positive"]]
   ["pow / negative exponent" "[(str (.pow BigInteger/TWO 100)) (try (.pow BigInteger/TWO -1) (catch ArithmeticException e (ex-message e)))]"
    ["1267650600228229401496703205376" "Negative exponent"]]
   ["parse errors" "(mapv #(try (BigInteger. %) (catch NumberFormatException e (ex-message e))) [\"12x\" \"\" \"-\" \"1-2\"])"
    ["For input string: \"12x\"" "Zero length BigInteger" "Zero length BigInteger" "Illegal embedded sign character"]]
   ["parse + and radix" "[(str (BigInteger. \"+12\")) (str (BigInteger. \"-ff\" 16))]" ["12" "-255"]]
   ["longValue / intValue wrap" "[(.longValue (.add (.pow BigInteger/TWO 64) (BigInteger/valueOf 5))) (.intValue (BigInteger/valueOf 4294967295))]" [5 -1]]
   ["bitLength shiftRight modPow modInverse gcd not bitCount"
    "[(.bitLength (BigInteger/valueOf -128)) (.bitLength (BigInteger/valueOf 255)) (str (.shiftRight (BigInteger/valueOf -5) 1)) (str (.modPow (BigInteger/valueOf 4) (BigInteger/valueOf 13) (BigInteger/valueOf 497))) (str (.modInverse (BigInteger/valueOf 3) (BigInteger/valueOf 11))) (str (.gcd (BigInteger/valueOf -12) (BigInteger/valueOf 18))) (str (.not (BigInteger/valueOf 5))) (.bitCount (BigInteger/valueOf -8))]"
    [7 8 "-3" "445" "4" "6" "-6" 3]]
   ["modInverse not invertible" "(try (.modInverse (BigInteger/valueOf 2) (BigInteger/valueOf 4)) (catch ArithmeticException e (ex-message e)))" "BigInteger not invertible."]
   ["sqrt getLowestSetBit testBit" "[(str (.sqrt (BigInteger/valueOf 99))) (.getLowestSetBit (BigInteger/valueOf 8)) (.getLowestSetBit BigInteger/ZERO) (.testBit (BigInteger/valueOf -2) 0) (.testBit (BigInteger/valueOf -2) 100)]"
    ["9" 3 -1 false true]]
   ["hashCode is the JDK's" "[(.hashCode (BigInteger/valueOf 123456789)) (.hashCode (BigInteger/valueOf -1)) (.hashCode (.pow BigInteger/TWO 70))]" [123456789 -1 61504]]
   ["doubleValue" "(.doubleValue (.pow BigInteger/TWO 70))" 1.1805916207174113E21]
   ["isProbablePrime" "[(.isProbablePrime (BigInteger/valueOf 97) 10) (.isProbablePrime (BigInteger/valueOf 91) 10) (.isProbablePrime (BigInteger. \"170141183460469231731687303715884105727\") 10)]" [true false true]]
   ["= compare sort between BigIntegers, (= bi 5), biginteger" "[(= (biginteger 5) (BigInteger/valueOf 5)) (= (biginteger 5) 5) (compare (biginteger 5) (biginteger 7)) (str (biginteger 5.7)) (mapv str (sort [(biginteger 3) (biginteger 1)]))]"
    [true true -1 "5" ["1" "3"]]]
   ["(+ bi 1) within 2^53 is a number" "(+ (biginteger 5) 1)" 6]
   ["instance?" "[(instance? java.math.BigInteger (biginteger 1)) (instance? java.math.BigInteger 1)]" [true false]]
   ;; documented deviations, pinned
   ["DEVIATION: (= 5 bi) is false here (true on the JVM)" "(= 5 (biginteger 5))" false]
   ["DEVIATION: beyond 2^53 a BigInteger refuses to become a number"
    "(try (+ (.pow BigInteger/TWO 60) 1) (catch ArithmeticException e (subs (ex-message e) 0 42)))"
    "java.math.BigInteger 1152921504606846976 u"]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
