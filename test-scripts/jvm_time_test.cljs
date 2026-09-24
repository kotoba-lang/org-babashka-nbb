;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/time.cljs):
;; java.time LocalDate / Instant / Duration / ChronoUnit basics.
;;   node cli.js test-scripts/jvm_time_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): each case "THREW Unable to
;; resolve symbol: java.time.LocalDate/now" (Instant, ...) -- the first
;; failure of cloud-itonami-iso3166-usa-epa in the 120-repo sample after
;; round 1 (`(str (java.time.LocalDate/now java.time.ZoneOffset/UTC))`).
;; Expected values and messages are JDK 21 answers (oracle 2026-09-24),
;; including the boundaries: Feb 29 in a non-leap year, month-end clamping
;; of plusMonths, years outside 0000-9999, an Instant before the epoch,
;; Instant.parse needing seconds.
(ns jvm-time-test)

(try (load-string "(import '[java.time LocalDate Instant Duration ZoneOffset] '[java.time.temporal ChronoUnit])") (catch :default e (println "import:" (ex-message e))))

(def cases
  [["parse / plusYears clamps Feb 29" "(str (.plusYears (LocalDate/parse \"2024-02-29\") 1))" "2025-02-28"]
   ["plusMonths clamps to month end" "(str (.plusMonths (LocalDate/of 2024 1 31) 1))" "2024-02-29"]
   ["minusDays across March 1" "(str (.minusDays (LocalDate/of 2024 3 1) 1))" "2024-02-29"]
   ["day of week / year, epoch day" "(let [d (LocalDate/of 2026 9 24)] [(str (.getDayOfWeek d)) (.getValue (.getDayOfWeek d)) (.getDayOfYear d) (.toEpochDay d) (str (.getMonth d))])" ["THURSDAY" 4 267 20720 "SEPTEMBER"]]
   ["year formatting outside 0000-9999" "(mapv str [(LocalDate/of 10000 1 1) (LocalDate/of -5 1 1) (LocalDate/of 5 1 1)])" ["+10000-01-01" "-0005-01-01" "0005-01-01"]]
   ["ofEpochDay round-trips far dates" "(mapv #(str (LocalDate/ofEpochDay (.toEpochDay (LocalDate/parse %)))) [\"1970-01-01\" \"1969-12-31\" \"2000-02-29\" \"1600-03-01\" \"2400-12-31\"])" ["1970-01-01" "1969-12-31" "2000-02-29" "1600-03-01" "2400-12-31"]]
   ["= compare isBefore" "(let [a (LocalDate/of 2024 3 1) b (LocalDate/parse \"2024-02-01\")] [(= a (LocalDate/of 2024 3 1)) (compare a b) (.isBefore b a) (.isAfter b a)])" [true 1 true false]]
   ["now with ZoneOffset/UTC is today's UTC date" "(= (str (LocalDate/now ZoneOffset/UTC)) (subs (.toISOString (js/Date.)) 0 10))" true]
   ["parse: bad month width" "(try (LocalDate/parse \"2024-2-29\") (catch java.time.format.DateTimeParseException e (ex-message e)))" "Text '2024-2-29' could not be parsed at index 5"]
   ["parse: month 13" "(try (LocalDate/parse \"2024-13-01\") (catch java.time.format.DateTimeParseException e (ex-message e)))" "Text '2024-13-01' could not be parsed: Invalid value for MonthOfYear (valid values 1 - 12): 13"]
   ["parse: day 32" "(try (LocalDate/parse \"2024-01-32\") (catch java.time.format.DateTimeParseException e (ex-message e)))" "Text '2024-01-32' could not be parsed: Invalid value for DayOfMonth (valid values 1 - 28/31): 32"]
   ["parse: Feb 30 / Apr 31" "(mapv #(try (LocalDate/parse %) (catch java.time.format.DateTimeParseException e (ex-message e))) [\"2024-02-30\" \"2023-04-31\"])" ["Text '2024-02-30' could not be parsed: Invalid date 'FEBRUARY 30'" "Text '2023-04-31' could not be parsed: Invalid date 'APRIL 31'"]]
   ["parse: Feb 29 non-leap" "(try (LocalDate/parse \"2023-02-29\") (catch java.time.format.DateTimeParseException e (ex-message e)))" "Text '2023-02-29' could not be parsed: Invalid date 'February 29' as '2023' is not a leap year"]
   ["parse: trailing text" "(try (LocalDate/parse \"2023-02-01x\") (catch java.time.format.DateTimeParseException e (ex-message e)))" "Text '2023-02-01x' could not be parsed, unparsed text found at index 10"]
   ["of: DateTimeException (not a parse exception)" "(try (LocalDate/of 2023 2 29) (catch java.time.DateTimeException e [(ex-message e) (instance? java.time.format.DateTimeParseException e)]))" ["Invalid date 'February 29' as '2023' is not a leap year" false]]
   ["Instant toString groups the fraction by 3" "(mapv str [(Instant/ofEpochMilli 1727136000123) (Instant/ofEpochSecond 0) (Instant/ofEpochSecond 1 1000) (Instant/ofEpochSecond -1 1)])" ["2024-09-24T00:00:00.123Z" "1970-01-01T00:00:00Z" "1970-01-01T00:00:01.000001Z" "1969-12-31T23:59:59.000000001Z"]]
   ["Instant parse, offset, lower-case z" "[(.toEpochMilli (Instant/parse \"2024-09-24T01:02:03.5Z\")) (str (Instant/parse \"2024-09-24T01:02:03.5Z\")) (str (Instant/parse \"2024-09-24T10:02:03+09:00\")) (str (Instant/parse \"2024-09-24T10:02:03z\"))]" [1727139723500 "2024-09-24T01:02:03.500Z" "2024-09-24T01:02:03Z" "2024-09-24T10:02:03Z"]]
   ["Instant parse needs seconds / a time" "(mapv #(try (Instant/parse %) (catch java.time.format.DateTimeParseException e (ex-message e))) [\"2024-09-24T10:02Z\" \"2024-09-24\"])" ["Text '2024-09-24T10:02Z' could not be parsed at index 16" "Text '2024-09-24' could not be parsed at index 10"]]
   ["Instant before the epoch" "(let [i (Instant/ofEpochMilli -1)] [(.getEpochSecond i) (.getNano i) (str i)])" [-1 999000000 "1969-12-31T23:59:59.999Z"]]
   ["Instant plus" "(str (.plusNanos (.plusMillis (.plusSeconds Instant/EPOCH 86400) 5) 7))" "1970-01-02T00:00:00.005000007Z"]
   ["Instant years outside 0000-9999" "[(str (Instant/parse \"+10000-01-01T00:00:00Z\")) (str (Instant/ofEpochSecond (- -62167219200 1)))]" ["+10000-01-01T00:00:00Z" "-0001-12-31T23:59:59Z"]]
   ["Instant/now is within a second of Date.now" "(< (js/Math.abs (- (.toEpochMilli (Instant/now)) (js/Date.now))) 1000)" true]
   ["Duration between / toMillis / toString" "(let [d (Duration/between (Instant/ofEpochMilli 0) (Instant/ofEpochMilli 1500))] [(.toMillis d) (str d) (str (Duration/ofSeconds 3661)) (str Duration/ZERO) (str (Duration/ofMillis -1500))])" [1500 "PT1.5S" "PT1H1M1S" "PT0S" "PT-1.5S"]]
   ["ChronoUnit between" "[(.between ChronoUnit/DAYS (LocalDate/of 2024 1 1) (LocalDate/of 2024 3 1)) (.between ChronoUnit/MONTHS (LocalDate/of 2024 1 31) (LocalDate/of 2024 2 29)) (.between ChronoUnit/SECONDS (Instant/ofEpochMilli 0) (Instant/ofEpochMilli 2500))]" [60 0 2]]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
