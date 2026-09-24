;; Regression for the JVM-compat scaffolding (src/nbb/jvm.cljs): clojure.core
;; format / printf over java.util.Formatter's rules.
;;   node cli.js test-scripts/jvm_format_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (8ea820f): each case "THREW Unable to
;; resolve symbol: format" (e.g. network-awai/cloud-murakumo-app
;; fleet_metrics.cljk). Expected values are what the JVM's String.format
;; returns; the HALF_UP cases are where JS toFixed differs ((.toFixed 1.005 2)
;; is "1.00", the JVM gives "1.01").
(ns jvm-format-test)

(def cases
  [["%s" "(format \"%s-%s\" \"a\" :k)" "a-:k"]
   ["%s nil is null" "(format \"%s\" nil)" "null"]
   ["%s of a map/vector is its str" "(format \"%s %s\" {:a 1} [1 2])" "{:a 1} [1 2]"]
   ["%5s %-5s|" "(format \"%5s|%-5s|\" \"ab\" \"cd\")" "   ab|cd   |"]
   ["%.2s truncates" "(format \"%.2s\" \"abcdef\")" "ab"]
   ["%S" "(format \"%S\" \"ab\")" "AB"]
   ["%d" "(format \"%d\" 42)" "42"]
   ["%5d %-5d| %05d" "(format \"%5d|%-5d|%05d\" 42 42 -42)" "   42|42   |-0042"]
   ["%,d %+d" "(format \"%,d %+d\" 1234567 5)" "1,234,567 +5"]
   ["%d of a fraction: IllegalFormatConversionException"
    "(try (format \"%d\" 1.5) (catch java.util.IllegalFormatConversionException e (ex-message e)))" "d != java.lang.Double"]
   ["%x %X %o" "(format \"%x %X %o\" 255 255 8)" "ff FF 10"]
   ["%x of -1 is 64-bit two's complement (a Long)" "(format \"%x\" -1)" "ffffffffffffffff"]
   ["%08x" "(format \"%08x\" 255)" "000000ff"]
   ["%f default precision 6" "(format \"%f\" 3.14159)" "3.141590"]
   ["%.2f" "(format \"%.2f\" 2.5)" "2.50"]
   ["%.2f HALF_UP from the decimal (1.005 -> 1.01)" "(format \"%.2f\" 1.005)" "1.01"]
   ["%.1f HALF_UP 0.25 -> 0.3" "(format \"%.1f\" 0.25)" "0.3"]
   ["%.0f 0.5 -> 1, 2.5 -> 3" "(format \"%.0f %.0f\" 0.5 2.5)" "1 3"]
   ["%.2f boundary just below half: 0.124 -> 0.12" "(format \"%.2f\" 0.124)" "0.12"]
   ["%.2f 0.004 -> 0.00, 0.005 -> 0.01" "(format \"%.2f %.2f\" 0.004 0.005)" "0.00 0.01"]
   ["%.1f carries 9.96 -> 10.0" "(format \"%.1f\" 9.96)" "10.0"]
   ["%.3f negative with zero pad" "(format \"%08.3f\" -3.14159)" "-003.142"]
   ["%.1f keeps the sign of a negative rounding to zero" "(format \"%.1f\" -0.04)" "-0.0"]
   ["%,.2f" "(format \"%,.2f\" 1234567.891)" "1,234,567.89"]
   ["%.2f of a large and a tiny number" "(format \"%.2f|%.3f\" 1e21 1e-7)" "1000000000000000000000.00|0.000"]
   ["%e" "(format \"%e\" 12345.678)" "1.234568e+04"]
   ["%.2e of a small number" "(format \"%.2e\" 0.000123456)" "1.23e-04"]
   ["%E" "(format \"%.1E\" 9.96)" "1.0E+01"]
   ["%f NaN / Infinity" "(format \"%f %f\" ##NaN ##-Inf)" "NaN -Infinity"]
   ["%b" "(format \"%b %b %b %b\" nil false true \"x\")" "false false true true"]
   ["%c" "(format \"%c%c\" \\a 98)" "ab"]
   ["%n %%" "(format \"a%nb%%\")" "a\nb%"]
   ["n$ argument index" "(format \"%2$s %1$s\" \"a\" \"b\")" "b a"]
   ["missing argument: MissingFormatArgumentException"
    "(try (format \"%s %s\" 1) (catch java.util.MissingFormatArgumentException e (ex-message e)))" "Format specifier '%s'"]
   ["unknown conversion: UnknownFormatConversionException, an IllegalArgumentException"
    "(try (format \"%q\" 1) (catch IllegalArgumentException e (ex-message e)))" "Conversion = 'q'"]
   ["printf prints through *out*" "(with-out-str (printf \"%d-%s\" 1 \"x\"))" "1-x"]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
