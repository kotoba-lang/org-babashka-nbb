;; Regression for the JVM-compat scaffolding round 3: Class/forName and
;; clojure.core/class (src/nbb/jvm/lang.cljs), clojure.core/bigint and its
;; interplay with round 2's BigInteger (src/nbb/jvm/math.cljs).
;;   node cli.js test-scripts/jvm_class_bigint_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (2b61d53): "THREW Unable to resolve
;; symbol: Class/forName" -- the first failure of 6 of a 120-repository
;; sample, all at `(def ^:private byte-array-class (Class/forName "[B"))` in
;; kotoba-lang/amu's src/kotoba/compiler/cache.clj as pinned by those repos
;; -- "... class", and "... bigint" (kotoba-lang/inference's first failure,
;; `(bigint (Math/round scaled))`).
;; Expected values are JDK 21 / Clojure 1.12 answers (oracle 2026-09-24).
;; Boundary cases: a bare class name is ClassNotFoundException as on the JVM,
;; (bigint 1e23) is 10^23 (BigDecimal.valueOf's shortest decimal, not the
;; binary value), 2^53-1 stays a number and 2^53 becomes a BigInteger,
;; NaN / Infinity fail with BigDecimal's messages. The last cases pin the
;; documented refusals / deviations: (class 5) refuses, "[C" refuses,
;; pr-str of a bigint has no N.
(ns jvm-class-bigint-test)

(def cases
  [["the byte[] idiom: (= (Class/forName \"[B\") (class x))" "(= (Class/forName \"[B\") (class (byte-array 1)))" true]
   ["instance? of the byte[] class" "[(instance? (Class/forName \"[B\") (byte-array 1)) (instance? (Class/forName \"[B\") \"x\") (instance? (Class/forName \"[B\") (js/Uint8Array. 1))]" [true false false]]
   ["forName of a registered class is that class" "[(= String (Class/forName \"java.lang.String\")) (= java.io.File (Class/forName \"java.io.File\"))]" [true true]]
   ["ClassNotFoundException with the name, bare names included"
    "(mapv #(try (Class/forName %) (catch ClassNotFoundException e (ex-message e))) [\"no.such.Klass\" \"String\"])" ["no.such.Klass" "String"]]
   ["java.lang.ClassNotFoundException is an Exception" "(try (Class/forName \"x.Y\") (catch Exception e (instance? java.lang.ClassNotFoundException e)))" true]
   ["class of nil / a string / a boolean" "[(class nil) (= String (class \"abc\")) (= Boolean (class true))]" [nil true true]]
   ["class of a keyword and a map is their type" "[(= (type :a) (class :a)) (= (type {}) (class {}))]" [true true]]
   ["class of a record is the record" "(do (defrecord R [a]) (= R (class (->R 1))))" true]
   ;; bigint
   ["bigint within 2^53 is a number" "[(bigint 5) (bigint 5.7) (bigint -5.7) (= (bigint 5) 5) (+ (bigint 5) 1) (str (+ (bigint 5) 1))]" [5 5 -5 true 6 "6"]]
   ["bigint of a double: the shortest decimal, truncated"
    "(mapv #(str (bigint %)) [1e20 1e23 (Math/pow 2 60) 1.2345678901234567e25])"
    ["100000000000000000000" "100000000000000000000000" "1152921504606847000" "12345678901234566000000000"]]
   ["bigint of a string" "[(str (bigint \"123456789012345678901234567890\")) (try (bigint \"12x\") (catch NumberFormatException e (ex-message e)))]"
    ["123456789012345678901234567890" "For input string: \"12x\""]]
   ["bigint NaN / Infinity" "(mapv #(try (bigint %) (catch NumberFormatException e (ex-message e))) [##NaN ##Inf])"
    ["Character N is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark."
     "Character I is neither a decimal digit number, decimal point, nor \"e\" notation exponential mark."]]
   ["2^53-1 is still a number, 2^53 is a BigInteger (it is where doubles stop being exact)"
    "[(number? (bigint 9007199254740991)) (instance? java.math.BigInteger (bigint 9007199254740992)) (str (bigint \"9007199254740993\"))]" [true true "9007199254740993"]]
   ["bare BigInteger resolves with no :import (Clojure auto-imports it), also as a def tag"
    "(do (def ^BigInteger big-p (.subtract (.pow (biginteger 2) 255) (biginteger 19))) [(= BigInteger java.math.BigInteger) (str (.mod (.add big-p BigInteger/ONE) big-p))])"
    [true "1"]]
   ["biginteger <-> bigint" "[(str (biginteger (bigint 5))) (bigint (biginteger 7)) (str (biginteger 1e23)) (= (bigint \"123456789012345678901234567890\") (biginteger \"123456789012345678901234567890\"))]"
    ["5" 7 "100000000000000000000000" true]]
   ;; documented refusals / deviations, pinned
   ["REFUSED: (class 5) -- Long or Double cannot be told apart"
    "(try (class 5) (catch UnsupportedOperationException e (subs (ex-message e) 0 9)))" "(class 5)"]
   ["REFUSED: Class/forName of a non-byte array class"
    "(try (Class/forName \"[C\") (catch UnsupportedOperationException e (subs (ex-message e) 0 18)))" "Class/forName \"[C\""]
   ["DEVIATION: pr-str of a bigint prints no N" "(pr-str (bigint 5))" "5"]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
