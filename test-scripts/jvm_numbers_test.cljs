;; Regression for the JVM-compat scaffolding (src/nbb/jvm.cljs): Integer /
;; Long / Double / Boolean statics with the JVM's parse rules and messages.
;;   node cli.js test-scripts/jvm_numbers_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (8ea820f): each case "THREW Unable to
;; resolve symbol: Long/parseLong" (Integer/..., Double/...) -- the first
;; failure of 3 of 76 red JVM-era suites sampled on 2026-09-24
;; (Long/parseLong, Double/isFinite, java.lang.Integer).
;; Deliberately absent (semantics not matchable on doubles): Long/MAX_VALUE,
;; Long/MIN_VALUE, (instance? Long x) and friends -- the last cases pin that.
(ns jvm-numbers-test)

(def cases
  [["Long/parseLong" "(Long/parseLong \"-42\")" -42]
   ["Long/parseLong with +" "(Long/parseLong \"+7\")" 7]
   ["Long/parseLong radix 16" "(Long/parseLong \"ff\" 16)" 255]
   ["Long/parseLong rejects 12x with the JVM message"
    "(try (Long/parseLong \"12x\") (catch NumberFormatException e (ex-message e)))" "For input string: \"12x\""]
   ["Long/parseLong rejects surrounding space"
    "(try (Long/parseLong \" 1\") (catch NumberFormatException e (ex-message e)))" "For input string: \" 1\""]
   ["Long/parseLong rejects a decimal"
    "(try (Long/parseLong \"1.0\") (catch NumberFormatException _ :nfe))" :nfe]
   ["Long/parseLong \"\"" "(try (Long/parseLong \"\") (catch NumberFormatException e (ex-message e)))" "For input string: \"\""]
   ["Long/parseLong nil" "(try (Long/parseLong nil) (catch NumberFormatException e (ex-message e)))" "Cannot parse null string: null"]
   ["radix suffix in the message" "(try (Long/parseLong \"zz\" 16) (catch NumberFormatException e (ex-message e)))" "For input string: \"zz\" under radix 16"]
   ["NumberFormatException is caught as IllegalArgumentException" "(try (Long/parseLong \"x\") (catch IllegalArgumentException _ :iae))" :iae]
   ;; Integer range boundary, both sides
   ["Integer/parseInt at MAX_VALUE" "(Integer/parseInt \"2147483647\")" 2147483647]
   ["Integer/parseInt one past MAX_VALUE throws" "(try (Integer/parseInt \"2147483648\") (catch NumberFormatException _ :nfe))" :nfe]
   ["Integer/parseInt at MIN_VALUE" "(Integer/parseInt \"-2147483648\")" -2147483648]
   ["Long/parseLong past 2^63-1 throws" "(try (Long/parseLong \"9223372036854775808\") (catch NumberFormatException _ :nfe))" :nfe]
   ["Integer/MAX_VALUE MIN_VALUE" "[Integer/MAX_VALUE Integer/MIN_VALUE]" [2147483647 -2147483648]]
   ["Integer/valueOf of a string and of a number" "[(Integer/valueOf \"5\") (Integer/valueOf 6)]" [5 6]]
   ["Integer/toHexString -1 (32-bit two's complement)" "(Integer/toHexString -1)" "ffffffff"]
   ["Long/toHexString -1 (64-bit)" "(Long/toHexString -1)" "ffffffffffffffff"]
   ["Integer/toBinaryString" "(Integer/toBinaryString 5)" "101"]
   ["java.lang.Integer fully qualified" "(java.lang.Integer/parseInt \"3\")" 3]
   ;; Double
   ["Double/parseDouble" "(Double/parseDouble \"1.5\")" 1.5]
   ["Double/parseDouble trims and takes a d suffix and exponent" "(Double/parseDouble \" 1.5e3d \")" 1500]
   ["Double/parseDouble Infinity" "(Double/parseDouble \"-Infinity\")" js/Number.NEGATIVE_INFINITY]
   ["Double/parseDouble rejects 1.5x" "(try (Double/parseDouble \"1.5x\") (catch NumberFormatException e (ex-message e)))" "For input string: \"1.5x\""]
   ["Double/parseDouble of blank is empty String" "(try (Double/parseDouble \"  \") (catch NumberFormatException e (ex-message e)))" "empty String"]
   ["Double/isNaN isFinite isInfinite" "[(Double/isNaN ##NaN) (Double/isFinite 1.0) (Double/isFinite ##Inf) (Double/isInfinite ##-Inf)]" [true true false true]]
   ["Double constants" "[(= Double/MAX_VALUE js/Number.MAX_VALUE) (= Double/POSITIVE_INFINITY ##Inf)]" [true true]]
   ["Boolean/parseBoolean" "[(Boolean/parseBoolean \"TRUE\") (Boolean/parseBoolean \"yes\") (Boolean/parseBoolean nil)]" [true false false]]
   ;; the deliberate absences stay unresolved rather than approximate
   ["Long/MAX_VALUE is NOT shimmed (2^63-1 is not a double)"
    "(try (str \"read \" (load-string \"Long/MAX_VALUE\")) (catch :default e (ex-message e)))"
    "Unable to find static field: MAX_VALUE in class java.lang.Long (deliberately not shimmed on this engine; see nbb.jvm)"]
   ["Long/MIN_VALUE likewise"
    "(try (str \"read \" (load-string \"Long/MIN_VALUE\")) (catch :default e (ex-message e)))"
    "Unable to find static field: MIN_VALUE in class java.lang.Long (deliberately not shimmed on this engine; see nbb.jvm)"]
   ["a static method that is not shimmed throws (does not return nil)"
    "(try (str \"returned \" (load-string \"(Integer/bitCount 3)\")) (catch :default _ :threw))" :threw]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
