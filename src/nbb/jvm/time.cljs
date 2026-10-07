(ns nbb.jvm.time
  "JVM-compatibility SCAFFOLDING, round 2: the java.time basics JVM-era
  suites reach for (see nbb.jvm for why this exists; it is not a target for
  new code).

  LocalDate (proleptic Gregorian over epoch days, the JDK's own algorithms):
  now of parse ofEpochDay, toString (\"+10000-01-01\", \"-0005-01-01\"),
  getYear getMonthValue getMonth getDayOfMonth getDayOfWeek getDayOfYear
  lengthOfMonth lengthOfYear isLeapYear, plus/minus Days Weeks Months Years
  (month-end clamping), withDayOfMonth withMonth withYear, isBefore isAfter
  isEqual compareTo toEpochDay, = / hash / compare, and parse / of errors
  with the JDK's DateTimeParseException / DateTimeException messages.
  Instant (epoch second + nano; now has millisecond resolution here, the JDK
  micro): now parse ofEpochMilli ofEpochSecond, ISO toString (fraction in
  groups of 3), plus/minus Seconds Millis Nanos and a Duration,
  toEpochMilli getEpochSecond getNano isBefore isAfter truncatedTo.
  Duration: of* between toMillis toSeconds getSeconds getNano toMinutes
  toHours toDays plus minus negated isNegative isZero, ISO toString.
  ChronoUnit/X .between for LocalDate and Instant; DayOfWeek / Month enums;
  ZoneOffset/UTC, ZoneId/of and systemDefault (for LocalDate/now).

  Deliberately NOT here: LocalDateTime / ZonedDateTime / OffsetDateTime /
  DateTimeFormatter patterns / Clock / TemporalAdjusters (not needed by the
  measured suites; each is a large semantic surface)."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]))

(def DateTimeException (jvm/defclass "java.time.DateTimeException" jvm/RuntimeException))
(def DateTimeParseException (jvm/defclass "java.time.format.DateTimeParseException" DateTimeException))
(def ZoneRulesException (jvm/defclass "java.time.zone.ZoneRulesException" DateTimeException))
(def UnsupportedTemporalTypeException (jvm/defclass "java.time.temporal.UnsupportedTemporalTypeException" DateTimeException))

(defn- floor-div [a b] (js/Math.floor (/ a b)))
(defn- floor-mod [a b] (- a (* b (floor-div a b))))

;; ---------------------------------------------------------------------------
;; enums
;; ---------------------------------------------------------------------------

(deftype EnumValue [cls nm ordinal]
  Object
  (toString [_] nm)
  (name [_] nm)
  (ordinal [_] ordinal)
  (getValue [_] (inc ordinal))
  (compareTo [_ o] (compare ordinal (.-ordinal ^js o)))
  IComparable
  (-compare [_ o] (compare ordinal (.-ordinal ^js o)))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (str "#object[" cls " \"" nm "\"]"))))

(def ^:private dow-names ["MONDAY" "TUESDAY" "WEDNESDAY" "THURSDAY" "FRIDAY" "SATURDAY" "SUNDAY"])
(def ^:private month-names ["JANUARY" "FEBRUARY" "MARCH" "APRIL" "MAY" "JUNE" "JULY" "AUGUST" "SEPTEMBER" "OCTOBER" "NOVEMBER" "DECEMBER"])

(def ^:private dows (mapv #(EnumValue. "java.time.DayOfWeek" %1 %2) dow-names (range)))
(def ^:private months (mapv #(EnumValue. "java.time.Month" %1 %2) month-names (range)))

(defn- enum-class [cls vals]
  (let [o (js-obj)]
    (doseq [^js v vals] (gobj/set o (.-nm v) v))
    (gobj/set o "values" (fn [] (into-array vals)))
    (gobj/set o "of" (fn [n] (if (<= 1 n (count vals)) (nth vals (dec n))
                                 (throw (DateTimeException. (str "Invalid value for " (last (str/split cls #"\.")) ": " n))))))
    (gobj/set o "valueOf" (fn [n] (or (gobj/get o n) (throw (jvm/IllegalArgumentException. (str "No enum constant " cls "." n))))))
    o))

;; ---------------------------------------------------------------------------
;; LocalDate
;; ---------------------------------------------------------------------------

(defn- leap? [y] (and (zero? (floor-mod y 4)) (or (not (zero? (floor-mod y 100))) (zero? (floor-mod y 400)))))

(defn- month-length [y m]
  (case m 2 (if (leap? y) 29 28) (4 6 9 11) 30 31))

(def ^:private days-0000-to-1970 719528)
(def ^:private days-per-cycle 146097)

(defn- to-epoch-day [y m d]
  (let [total (* 365 y)
        total (if (>= y 0)
                (+ total (- (+ (quot (+ y 3) 4) (quot (+ y 399) 400)) (quot (+ y 99) 100)))
                (- total (+ (- (quot y -4) (quot y -100)) (quot y -400))))
        total (+ total (quot (- (* 367 m) 362) 12))
        total (+ total (dec d))
        total (if (> m 2) (- total (if (leap? y) 1 2)) total)]
    (- total days-0000-to-1970)))

(defn- from-epoch-day [epoch-day]
  (let [zero-day (- (+ epoch-day days-0000-to-1970) 60)
        [zero-day adjust] (if (neg? zero-day)
                            (let [cycles (dec (quot (inc zero-day) days-per-cycle))]
                              [(+ zero-day (* (- cycles) days-per-cycle)) (* cycles 400)])
                            [zero-day 0])
        year-est (quot (+ (* 400 zero-day) 591) days-per-cycle)
        doy (fn [ye] (- zero-day (+ (* 365 ye) (quot ye 4) (- (quot ye 100)) (quot ye 400))))
        [year-est doy-est] (let [d (doy year-est)] (if (neg? d) [(dec year-est) (doy (dec year-est))] [year-est d]))
        year-est (+ year-est adjust)
        march-month0 (quot (+ (* doy-est 5) 2) 153)
        month (inc (mod (+ march-month0 2) 12))
        dom (inc (- doy-est (quot (+ (* march-month0 306) 5) 10)))
        year (+ year-est (quot march-month0 10))]
    [year month dom]))

(defn- year-string [y]
  (let [a (js/Math.abs y)]
    (cond (< a 1000) (str (when (neg? y) "-") (.padStart (str a) 4 "0"))
          (> y 9999) (str "+" y)
          :else (str y))))

(defn- two [n] (.padStart (str n) 2 "0"))

(defn- check-field [field lo hi v hi-text]
  (when-not (<= lo v hi)
    (throw (DateTimeException. (str "Invalid value for " field " (valid values " lo " - " (or hi-text hi) "): " v)))))

(defn- check-date [y m d]
  (check-field "Year" -999999999 999999999 y nil)
  (check-field "MonthOfYear" 1 12 m nil)
  (check-field "DayOfMonth" 1 31 d "28/31")
  (when (> d (month-length y m))
    (throw (DateTimeException.
            (if (and (= 2 m) (= 29 d))
              (str "Invalid date 'February 29' as '" y "' is not a leap year")
              (str "Invalid date '" (nth month-names (dec m)) " " d "'"))))))

(declare local-date)

(deftype LocalDate [y m d]
  Object
  (getYear [_] y)
  (getMonthValue [_] m)
  (getMonth [_] (nth months (dec m)))
  (getDayOfMonth [_] d)
  (getDayOfYear [_] (inc (- (to-epoch-day y m d) (to-epoch-day y 1 1))))
  (getDayOfWeek [_] (nth dows (floor-mod (+ (to-epoch-day y m d) 3) 7)))
  (lengthOfMonth [_] (month-length y m))
  (lengthOfYear [_] (if (leap? y) 366 365))
  (isLeapYear [_] (leap? y))
  (toEpochDay [_] (to-epoch-day y m d))
  (plusDays [_ n] (apply local-date (from-epoch-day (+ (to-epoch-day y m d) n))))
  (minusDays [this n] (.plusDays this (- n)))
  (plusWeeks [this n] (.plusDays this (* 7 n)))
  (minusWeeks [this n] (.plusDays this (* -7 n)))
  (plusMonths [_ n] (let [c (+ (* y 12) (dec m) n)
                          ny (floor-div c 12) nm (inc (floor-mod c 12))]
                      (local-date ny nm (min d (month-length ny nm)))))
  (minusMonths [this n] (.plusMonths this (- n)))
  (plusYears [_ n] (let [ny (+ y n)] (local-date ny m (min d (month-length ny m)))))
  (minusYears [this n] (.plusYears this (- n)))
  (withDayOfMonth [_ n] (check-date y m n) (local-date y m n))
  (withMonth [_ n] (check-field "MonthOfYear" 1 12 n nil) (local-date y n (min d (month-length y n))))
  (withYear [_ n] (local-date n m (min d (month-length n m))))
  (isBefore [this o] (neg? (.compareTo this o)))
  (isAfter [this o] (pos? (.compareTo this o)))
  (isEqual [this o] (zero? (.compareTo this o)))
  (compareTo [_ o] (compare [y m d] [(.-y ^js o) (.-m ^js o) (.-d ^js o)]))
  (equals [this o] (-equiv this o))
  (hashCode [_] (bit-xor (bit-and y 0xFFFFF800) (+ (bit-shift-left y 11) (bit-shift-left m 6) d)))
  (toString [_] (str (year-string y) "-" (two m) "-" (two d)))
  IEquiv
  (-equiv [_ o] (and (instance? LocalDate o) (= y (.-y ^js o)) (= m (.-m ^js o)) (= d (.-d ^js o))))
  IHash
  (-hash [_] (hash [y m d]))
  IComparable
  (-compare [this o] (.compareTo this o))
  IPrintWithWriter
  (-pr-writer [this w _] (-write w (str "#object[java.time.LocalDate \"" (.toString this) "\"]"))))

(defn local-date [y m d] (LocalDate. y m d))

(defn- parse-error
  ([text idx] (DateTimeParseException. (str "Text '" text "' could not be parsed at index " idx)))
  ([text cause-msg _] (DateTimeParseException. (str "Text '" text "' could not be parsed: " cause-msg))))

(defn- parse-date-at
  "ISO_LOCAL_DATE at pos -> [y m d next-pos], throwing the JDK's index."
  [text pos]
  (let [n (count text)
        sign (when (and (< pos n) (contains? #{"+" "-"} (.charAt text pos))) (.charAt text pos))
        p (if sign (inc pos) pos)
        digits (count (take-while #(re-matches #"\d" %) (map str (subs text p (min n (+ p 10))))))]
    (when (or (< digits 4) (and (not sign) (> digits 4))) (throw (parse-error text pos)))
    (let [y (* (if (= "-" sign) -1 1) (js/parseInt (subs text p (+ p digits)) 10))
          p (+ p digits)
          expect (fn [p ch] (when-not (and (< p n) (= ch (.charAt text p))) (throw (parse-error text p))))
          two-at (fn [p] (if (and (<= (+ p 2) n) (re-matches #"\d\d" (subs text p (+ p 2))))
                           (js/parseInt (subs text p (+ p 2)) 10)
                           (throw (parse-error text p))))]
      (expect p "-")
      (let [m (two-at (inc p))]
        (expect (+ p 3) "-")
        (let [d (two-at (+ p 4))]
          [y m d (+ p 6)])))))

(defn- validate [text f]
  (try (f) (catch :default e
             (if (and (instance? DateTimeException e) (not (instance? DateTimeParseException e)))
               (throw (parse-error text (.-message e) :cause))
               (throw e)))))

(defn parse-local-date [text]
  (when (nil? text) (throw (js/TypeError. "text")))
  (let [text (str text)
        [y m d p] (parse-date-at text 0)]
    (when (< p (count text))
      (throw (DateTimeParseException. (str "Text '" text "' could not be parsed, unparsed text found at index " p))))
    (validate text #(check-date y m d))
    (LocalDate. y m d)))

(defn- of-local-date [y m d]
  (check-date y m d)
  (LocalDate. y m d))

;; ---------------------------------------------------------------------------
;; zones (only what LocalDate/now needs)
;; ---------------------------------------------------------------------------

(deftype Zone [id offset-seconds]
  Object
  (getId [_] id)
  (toString [_] id)
  (getTotalSeconds [_] offset-seconds)
  (normalized [this] this)
  IEquiv
  (-equiv [_ o] (and (instance? Zone o) (= id (.-id ^js o))))
  IHash
  (-hash [_] (hash id))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (str "#object[java.time.ZoneRegion \"" id "\"]"))))

(def UTC (Zone. "Z" 0))

(defn- system-zone-id [] (.-timeZone (.resolvedOptions (js/Intl.DateTimeFormat.))))

(defn- zone-of [id]
  (let [id (str id)]
    (cond (contains? #{"Z" "UTC" "GMT" "UT"} id) (if (= "Z" id) UTC (Zone. id 0))
          (re-matches #"[+-]\d{2}:\d{2}" id) (let [sign (if (str/starts-with? id "-") -1 1)
                                                  [h mm] (map #(js/parseInt % 10) (str/split (subs id 1) #":"))]
                                              (Zone. id (* sign (+ (* 3600 h) (* 60 mm)))))
          :else (do (try (js/Intl.DateTimeFormat. "en-US" #js {"timeZone" id})
                         (catch :default _ (throw (ZoneRulesException. (str "Unknown time-zone ID: " id)))))
                    (Zone. id nil)))))

(defn- ymd-in-zone [ms ^js zone]
  (cond (nil? zone) (let [dt (js/Date. ms)] [(.getFullYear dt) (inc (.getMonth dt)) (.getDate dt)])
        (some? (.-offset-seconds zone)) (let [dt (js/Date. (+ ms (* 1000 (.-offset-seconds zone))))]
                                          [(.getUTCFullYear dt) (inc (.getUTCMonth dt)) (.getUTCDate dt)])
        :else (let [parts (.formatToParts (js/Intl.DateTimeFormat. "en-US" #js {"timeZone" (.-id zone) "year" "numeric" "month" "numeric" "day" "numeric"})
                                          (js/Date. ms))
                    part (fn [t] (js/parseInt (.-value (.find parts #(= t (.-type %)))) 10))]
                [(part "year") (part "month") (part "day")])))

;; ---------------------------------------------------------------------------
;; Duration
;; ---------------------------------------------------------------------------

(declare duration instant)

(deftype Duration [secs nanos]
  Object
  (getSeconds [_] secs)
  (toSeconds [_] secs)
  (getNano [_] nanos)
  (toMillis [_] (+ (* secs 1000) (quot nanos 1000000)))
  (toNanos [_] (+ (* secs 1e9) nanos))
  (toMinutes [_] (quot secs 60))
  (toHours [_] (quot secs 3600))
  (toDays [_] (quot secs 86400))
  (isNegative [_] (neg? secs))
  (isZero [_] (and (zero? secs) (zero? nanos)))
  (negated [_] (duration (- secs) (- nanos)))
  (abs [this] (if (neg? secs) (.negated this) this))
  (plus [_ ^js o] (duration (+ secs (.-secs o)) (+ nanos (.-nanos o))))
  (minus [_ ^js o] (duration (- secs (.-secs o)) (- nanos (.-nanos o))))
  (plusSeconds [_ n] (duration (+ secs n) nanos))
  (plusMillis [_ n] (duration secs (+ nanos (* n 1000000))))
  (multipliedBy [_ n] (duration (* secs n) (* nanos n)))
  (compareTo [_ ^js o] (compare [secs nanos] [(.-secs o) (.-nanos o)]))
  (toString [_]
    (if (and (zero? secs) (zero? nanos)) "PT0S"
        (let [eff (if (and (neg? secs) (pos? nanos)) (inc secs) secs)
              hours (quot eff 3600)
              minutes (quot (rem eff 3600) 60)
              s (rem eff 60)
              head (str "PT" (when-not (zero? hours) (str hours "H")) (when-not (zero? minutes) (str minutes "M")))]
          (if (and (zero? s) (zero? nanos) (> (count head) 2)) head
              (str head
                   (if (and (neg? secs) (pos? nanos)) (if (zero? s) "-0" s) s)
                   (when (pos? nanos)
                     (let [frac (str (if (neg? secs) (- 2e9 nanos) (+ nanos 1e9)))]
                       (str "." (str/replace (subs frac 1) #"0+$" ""))))
                   "S")))))
  IEquiv
  (-equiv [_ o] (and (instance? Duration o) (= secs (.-secs ^js o)) (= nanos (.-nanos ^js o))))
  IHash
  (-hash [_] (hash [secs nanos]))
  IComparable
  (-compare [this o] (.compareTo this o))
  IPrintWithWriter
  (-pr-writer [this w _] (-write w (str "#object[java.time.Duration \"" (.toString this) "\"]"))))

(defn duration [secs nanos]
  (Duration. (+ secs (floor-div nanos 1000000000)) (floor-mod nanos 1000000000)))

;; ---------------------------------------------------------------------------
;; Instant
;; ---------------------------------------------------------------------------

(deftype Instant [secs nanos]
  Object
  (getEpochSecond [_] secs)
  (getNano [_] nanos)
  (toEpochMilli [_] (+ (* secs 1000) (quot nanos 1000000)))
  (plusSeconds [_ n] (instant (+ secs n) nanos))
  (minusSeconds [_ n] (instant (- secs n) nanos))
  (plusMillis [_ n] (instant secs (+ nanos (* n 1000000))))
  (minusMillis [_ n] (instant secs (- nanos (* n 1000000))))
  (plusNanos [_ n] (instant secs (+ nanos n)))
  (minusNanos [_ n] (instant secs (- nanos n)))
  (plus [_ ^js dur] (instant (+ secs (.-secs dur)) (+ nanos (.-nanos dur))))
  (minus [_ ^js dur] (instant (- secs (.-secs dur)) (- nanos (.-nanos dur))))
  (isBefore [this o] (neg? (.compareTo this o)))
  (isAfter [this o] (pos? (.compareTo this o)))
  (compareTo [_ ^js o] (compare [secs nanos] [(.-secs o) (.-nanos o)]))
  (equals [this o] (-equiv this o))
  (truncatedTo [_ ^js unit]
    (let [n (.-nanos-per unit)]
      (cond (nil? n) (throw (UnsupportedTemporalTypeException. "Unit is too large to be used for truncation"))
            (>= n 1e9) (instant (* (floor-div secs (/ n 1e9)) (/ n 1e9)) 0)
            :else (instant secs (* (floor-div nanos n) n)))))
  (toString [_]
    (let [[y m d] (from-epoch-day (floor-div secs 86400))
          sod (floor-mod secs 86400)
          frac (cond (zero? nanos) ""
                     (zero? (mod nanos 1000000)) (str "." (.padStart (str (quot nanos 1000000)) 3 "0"))
                     (zero? (mod nanos 1000)) (str "." (.padStart (str (quot nanos 1000)) 6 "0"))
                     :else (str "." (.padStart (str nanos) 9 "0")))]
      (str (year-string y) "-" (two m) "-" (two d) "T" (two (quot sod 3600)) ":" (two (quot (mod sod 3600) 60)) ":" (two (mod sod 60)) frac "Z")))
  IEquiv
  (-equiv [_ o] (and (instance? Instant o) (= secs (.-secs ^js o)) (= nanos (.-nanos ^js o))))
  IHash
  (-hash [_] (hash [secs nanos]))
  IComparable
  (-compare [this o] (.compareTo this o))
  IPrintWithWriter
  (-pr-writer [this w _] (-write w (str "#object[java.time.Instant \"" (.toString this) "\"]"))))

(defn instant [secs nanos]
  (Instant. (+ secs (floor-div nanos 1000000000)) (floor-mod nanos 1000000000)))

(defn parse-instant
  "DateTimeFormatter.ISO_INSTANT: date 'T' HH:mm:ss[.fraction] then Z or an
  offset (JDK 12+)."
  [text]
  (let [text (str text)
        n (count text)
        [y m d p] (parse-date-at text 0)
        expect (fn [p ch] (when-not (and (< p n) (= (str/upper-case ch) (str/upper-case (.charAt text p)))) (throw (parse-error text p))))
        two-at (fn [p] (if (and (<= (+ p 2) n) (re-matches #"\d\d" (subs text p (+ p 2))))
                         (js/parseInt (subs text p (+ p 2)) 10)
                         (throw (parse-error text p))))
        _ (expect p "T")
        hh (two-at (+ p 1))
        _ (expect (+ p 3) ":")
        mi (two-at (+ p 4))
        _ (expect (+ p 6) ":")
        ss (two-at (+ p 7))
        p (+ p 9)
        [nano p] (if (and (< p n) (= "." (.charAt text p)))
                   (let [ds (apply str (take-while #(re-matches #"\d" %) (map str (subs text (inc p) (min n (+ p 10))))))]
                     (when (zero? (count ds)) (throw (parse-error text (inc p))))
                     [(js/parseInt (.padEnd ds 9 "0") 10) (+ p 1 (count ds))])
                   [0 p])
        [offset p] (cond (and (< p n) (contains? #{"Z" "z"} (.charAt text p))) [0 (inc p)]
                         (and (< p n) (contains? #{"+" "-"} (.charAt text p)))
                         (let [sign (if (= "-" (.charAt text p)) -1 1)
                               oh (two-at (inc p))
                               _ (expect (+ p 3) ":")
                               om (two-at (+ p 4))]
                           [(* sign (+ (* 3600 oh) (* 60 om))) (+ p 6)])
                         :else (throw (parse-error text p)))]
    (when (< p n)
      (throw (DateTimeParseException. (str "Text '" text "' could not be parsed, unparsed text found at index " p))))
    (validate text (fn []
                     (check-date y m d)
                     (check-field "HourOfDay" 0 23 hh nil)
                     (check-field "MinuteOfHour" 0 59 mi nil)
                     (check-field "SecondOfMinute" 0 59 ss nil)))
    (instant (- (+ (* 86400 (to-epoch-day y m d)) (* 3600 hh) (* 60 mi) ss) offset) nano)))

;; ---------------------------------------------------------------------------
;; ChronoUnit
;; ---------------------------------------------------------------------------

(deftype Unit [nm nanos-per]
  Object
  (toString [_] (str (first nm) (str/lower-case (subs nm 1))))
  (name [_] nm)
  (between [_ a b]
    (cond (instance? LocalDate a)
          (let [^js a a ^js b b
                days (- (.toEpochDay b) (.toEpochDay a))
                months (fn [] (let [pm (fn [^js x] (+ (* (.-y x) 12) (dec (.-m x))))
                                    total (- (pm b) (pm a))]
                                (cond (and (pos? total) (< (.-d b) (.-d a))) (dec total)
                                      (and (neg? total) (> (.-d b) (.-d a))) (inc total)
                                      :else total)))]
            (case nm "DAYS" days "WEEKS" (quot days 7) "MONTHS" (months) "YEARS" (quot (months) 12)
                  (throw (UnsupportedTemporalTypeException. (str "Unsupported unit: " (first nm) (str/lower-case (subs nm 1)))))))
          (instance? Instant a)
          (let [^js a a ^js b b]
            (if (nil? nanos-per)
              (throw (UnsupportedTemporalTypeException. (str "Unsupported unit: " (first nm) (str/lower-case (subs nm 1)))))
              (let [total-nanos (+ (* (- (.-secs b) (.-secs a)) 1e9) (- (.-nanos b) (.-nanos a)))]
                (js/Math.trunc (/ total-nanos nanos-per)))))
          :else (throw (UnsupportedTemporalTypeException. "Unsupported temporal type on this engine"))))
  IPrintWithWriter
  (-pr-writer [this w _] (-write w (str "#object[java.time.temporal.ChronoUnit \"" (.toString this) "\"]"))))

(def ChronoUnit
  (let [o (js-obj)]
    (doseq [[k n] [["NANOS" 1] ["MICROS" 1e3] ["MILLIS" 1e6] ["SECONDS" 1e9] ["MINUTES" 6e10]
                   ["HOURS" 3.6e12] ["HALF_DAYS" 4.32e13] ["DAYS" 8.64e13] ["WEEKS" nil] ["MONTHS" nil] ["YEARS" nil]]]
      (gobj/set o k (Unit. k n)))
    o))

;; ---------------------------------------------------------------------------
;; classes
;; ---------------------------------------------------------------------------

(defn- class-with [proto statics]
  (let [c (js* "(function(){ return function(){}; })()")]
    (set! (.-prototype c) proto)
    (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" (fn [x] (js/Object.prototype.isPrototypeOf.call proto x))})
    (doseq [[k v] statics] (gobj/set c k v))
    c))

(defn classes []
  (let [ld (class-with (.-prototype LocalDate)
                       {"now" (fn ([] (apply local-date (ymd-in-zone (js/Date.now) nil)))
                                ([zone] (apply local-date (ymd-in-zone (js/Date.now) zone))))
                        "of" (fn [y m d] (of-local-date y (if (instance? EnumValue m) (.getValue ^js m) m) d))
                        "parse" parse-local-date
                        "ofEpochDay" (fn [n] (apply local-date (from-epoch-day n)))
                        "EPOCH" (LocalDate. 1970 1 1)
                        "MIN" (LocalDate. -999999999 1 1)
                        "MAX" (LocalDate. 999999999 12 31)})
        inst (class-with (.-prototype Instant)
                         {"now" (fn [] (let [ms (js/Date.now)] (instant (floor-div ms 1000) (* 1000000 (floor-mod ms 1000)))))
                          "parse" parse-instant
                          "ofEpochMilli" (fn [ms] (instant (floor-div ms 1000) (* 1000000 (floor-mod ms 1000))))
                          "ofEpochSecond" (fn ([s] (instant s 0)) ([s adj] (instant s adj)))
                          "EPOCH" (Instant. 0 0)})
        dur (class-with (.-prototype Duration)
                        {"ofSeconds" (fn ([s] (duration s 0)) ([s adj] (duration s adj)))
                         "ofMillis" (fn [ms] (duration (floor-div ms 1000) (* 1000000 (floor-mod ms 1000))))
                         "ofNanos" (fn [n] (duration 0 n))
                         "ofMinutes" (fn [n] (duration (* 60 n) 0))
                         "ofHours" (fn [n] (duration (* 3600 n) 0))
                         "ofDays" (fn [n] (duration (* 86400 n) 0))
                         "between" (fn [^js a ^js b] (duration (- (.-secs b) (.-secs a)) (- (.-nanos b) (.-nanos a))))
                         "ZERO" (Duration. 0 0)})
        zone-id (js-obj "of" zone-of "systemDefault" (fn [] (Zone. (system-zone-id) nil)))
        zone-offset (js-obj "UTC" UTC "of" zone-of
                            "ofHours" (fn [h] (if (zero? h) UTC (zone-of (str (if (neg? h) "-" "+") (two (js/Math.abs h)) ":00")))))]
    {'java.time.LocalDate ld
     'java.time.Instant inst
     'java.time.Duration dur
     'java.time.ZoneId zone-id
     'java.time.ZoneOffset zone-offset
     'java.time.DayOfWeek (enum-class "java.time.DayOfWeek" dows)
     'java.time.Month (enum-class "java.time.Month" months)
     'java.time.temporal.ChronoUnit ChronoUnit
     'java.time.DateTimeException DateTimeException
     'java.time.format.DateTimeParseException DateTimeParseException
     'java.time.zone.ZoneRulesException ZoneRulesException
     'java.time.temporal.UnsupportedTemporalTypeException UnsupportedTemporalTypeException}))
