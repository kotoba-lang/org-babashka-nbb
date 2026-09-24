;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/bytes.cljs):
;; byte[] as a signed Int8Array, and the names that agree on it -- byte-array
;; bytes? byte unchecked-byte aset-byte, (String. bytes cs), (.getBytes s cs),
;; StandardCharsets / Charset, ByteArrayOutputStream / ByteArrayInputStream,
;; MessageDigest, java.util.Arrays.
;;   node cli.js test-scripts/jvm_bytes_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): each case "THREW Unable to
;; resolve classname: java.nio.charset.StandardCharsets" (byte-array,
;; String, java.io.ByteArrayOutputStream, java.security.MessageDigest ...), or
;; "Could not find instance method: getBytes" -- the first failure of 8 + 1 +
;; 1 of the 48 red suites in the 120-repo sample after round 1 (2026-09-24).
;; Expected values are JDK 21 / Clojure 1.12 answers (oracle run 2026-09-24),
;; including the boundaries: 255 wraps to -1 in byte-array but (byte 200) is
;; out of range, a lone surrogate encodes as '?' in UTF-8, malformed UTF-8
;; decodes to U+FFFD per maximal subpart, the UTF-8 BOM is kept.
(ns jvm-bytes-test)

(def cases
  [;; byte[]
   ["byte-array of a seq wraps like byteValue" "(vec (byte-array [1 255 -129 1.7 -1.2]))" [1 -1 127 1 -1]]
   ["byte-array of a size" "(vec (byte-array 3))" [0 0 0]]
   ["byte-array size + shorter seq" "(vec (byte-array 3 [1]))" [1 0 0]]
   ["byte-array size + longer seq" "(vec (byte-array 2 [1 2 3]))" [1 2]]
   ["byte-array nil" "(count (byte-array nil))" 0]
   ["bytes? of a byte-array / a vector" "[(bytes? (byte-array 0)) (bytes? [1])]" [true false]]
   ["aget of 0xFF is -1 (signed)" "(aget (byte-array [255]) 0)" -1]
   ["seq of an empty byte[] is nil" "(seq (byte-array 0))" nil]
   ["seq / count / nth / get" "(let [b (byte-array [5 6])] [(seq b) (count b) (nth b 1) (get b 1) (alength b)])" ['(5 6) 2 6 6 2]]
   ["= on two byte[] is identity, Arrays/equals compares" "(let [a (byte-array [1]) b (byte-array [1])] [(= a b) (java.util.Arrays/equals a b)])" [false true]]
   ["byte in range truncates" "[(byte -128) (byte 1.9)]" [-128 1]]
   ["byte out of range throws the JVM message" "(try (byte 200) (catch IllegalArgumentException e (ex-message e)))" "Value out of range for byte: 200"]
   ["byte of 127 / 128 boundary" "[(byte 127) (try (byte 128) (catch IllegalArgumentException _ :iae))]" [127 :iae]]
   ["unchecked-byte wraps" "[(unchecked-byte 200) (unchecked-byte 1.9)]" [-56 1]]
   ["aset-byte range-checks" "(let [b (byte-array 2)] (aset-byte b 0 -3) [(vec b) (try (aset-byte b 0 200) (catch IllegalArgumentException _ :iae))])" [[-3 0] :iae]]
   ;; String <-> bytes
   ["getBytes default is UTF-8" "(vec (.getBytes \"é\"))" [-61 -87]]
   ["getBytes by charset name" "(vec (.getBytes \"é\" \"ISO-8859-1\"))" [-23]]
   ["getBytes StandardCharsets/UTF_8" "(vec (.getBytes \"a\\uD83D\\uDE00\" java.nio.charset.StandardCharsets/UTF_8))" [97 -16 -97 -104 -128]]
   ["UTF-8 lone surrogate encodes as ?" "(vec (.getBytes \"a\\uD800b\" java.nio.charset.StandardCharsets/UTF_8))" [97 63 98]]
   ["US-ASCII unmappable is ?" "(vec (.getBytes \"aé€\" java.nio.charset.StandardCharsets/US_ASCII))" [97 63 63]]
   ["ISO-8859-1 keeps é, ? for €, one ? per pair" "[(vec (.getBytes \"aé€\" java.nio.charset.StandardCharsets/ISO_8859_1)) (vec (.getBytes \"\\uD83D\\uDE00\" java.nio.charset.StandardCharsets/ISO_8859_1))]" [[97 -23 63] [63]]]
   ["UTF-16 writes a BOM, big-endian; empty has none" "[(vec (.getBytes \"a€\" java.nio.charset.StandardCharsets/UTF_16)) (vec (.getBytes \"\" java.nio.charset.StandardCharsets/UTF_16))]" [[-2 -1 0 97 32 -84] []]]
   ["UTF-16LE" "(vec (.getBytes \"a€\" java.nio.charset.StandardCharsets/UTF_16LE))" [97 0 -84 32]]
   ["(String. bytes) is UTF-8" "(String. (byte-array [104 105]))" "hi"]
   ["(String. bytes \"UTF-8\")" "(String. (byte-array [-61 -87]) \"UTF-8\")" "é"]
   ["(String. bytes ISO_8859_1)" "(String. (byte-array [-61 -87]) java.nio.charset.StandardCharsets/ISO_8859_1)" "Ã©"]
   ["malformed UTF-8: maximal subpart -> U+FFFD" "(mapv int (map #(.charCodeAt % 0) (String. (byte-array [-16 -128 -128 -128]) java.nio.charset.StandardCharsets/UTF_8)))" [65533 65533 65533 65533]]
   ["truncated UTF-8 sequence is one U+FFFD" "(String. (byte-array [-30 -126]) java.nio.charset.StandardCharsets/UTF_8)" "�"]
   ["the UTF-8 BOM is kept" "(count (String. (byte-array [-17 -69 -65 65]) java.nio.charset.StandardCharsets/UTF_8))" 2]
   ["US-ASCII decode of a high byte is U+FFFD" "(String. (byte-array [65 -23]) java.nio.charset.StandardCharsets/US_ASCII)" "A�"]
   ["UTF-16 decode honours a LE BOM, odd byte -> U+FFFD" "[(String. (byte-array [-1 -2 97 0]) java.nio.charset.StandardCharsets/UTF_16) (String. (byte-array [0 97 0]) java.nio.charset.StandardCharsets/UTF_16)]" ["a" "a�"]]
   ["(String. bytes off len cs)" "(String. (.getBytes \"hello\") 1 3 java.nio.charset.StandardCharsets/US_ASCII)" "ell"]
   ["(String. bytes off len) out of range: JVM message" "(try (String. (.getBytes \"hello\") 3 3 java.nio.charset.StandardCharsets/US_ASCII) (catch IndexOutOfBoundsException e (ex-message e)))" "Range [3, 3 + 3) out of bounds for length 5"]
   ["unknown charset name: UnsupportedEncodingException" "(try (.getBytes \"x\" \"bogus\") (catch java.io.UnsupportedEncodingException e (ex-message e)))" "bogus"]
   ["Charset/forName unknown / illegal" "[(try (java.nio.charset.Charset/forName \"bogus\") (catch java.nio.charset.UnsupportedCharsetException e (ex-message e))) (try (java.nio.charset.Charset/forName \"a b\") (catch java.nio.charset.IllegalCharsetNameException e (ex-message e)))]" ["bogus" "a b"]]
   ["charset names" "[(str java.nio.charset.StandardCharsets/UTF_8) (.name java.nio.charset.StandardCharsets/UTF_16) (.name (java.nio.charset.Charset/forName \"latin1\"))]" ["UTF-8" "UTF-16" "ISO-8859-1"]]
   ["String statics / ctor / instance?" "[(String.) (String. \"abc\") (String/valueOf 12) (String/join \",\" [\"a\" \"b\"]) (String/format \"%d-%s\" (to-array [1 \"x\"])) (instance? String \"x\")]" ["" "abc" "12" "a,b" "1-x" true]]
   ;; ByteArrayOutputStream / ByteArrayInputStream
   ["ByteArrayOutputStream write int/bytes/range" "(let [o (java.io.ByteArrayOutputStream.)] (.write o 65) (.write o 300) (.write o (.getBytes \"hé\")) (.write o (.getBytes \"xyz\") 1 1) [(vec (.toByteArray o)) (.size o) (.toString o) (str o)])" [[65 44 104 -61 -87 121] 6 "A,héy" "A,héy"]]
   ["ByteArrayOutputStream write -1" "(let [o (java.io.ByteArrayOutputStream.)] (.write o -1) (vec (.toByteArray o)))" [-1]]
   ["ByteArrayOutputStream bad range: JVM message" "(let [o (java.io.ByteArrayOutputStream.)] (try (.write o (.getBytes \"abc\") 2 5) (catch IndexOutOfBoundsException e (ex-message e))))" "Range [2, 2 + 5) out of bounds for length 3"]
   ["ByteArrayInputStream read is unsigned, -1 at end" "(let [i (java.io.ByteArrayInputStream. (byte-array [1 -1 3]))] [(.read i) (.read i) (.available i) (.read i) (.read i)])" [1 255 1 3 -1]]
   ["slurp of a ByteArrayInputStream" "(slurp (java.io.ByteArrayInputStream. (.getBytes \"héllo\")))" "héllo"]
   ;; MessageDigest
   ["MessageDigest SHA-256 of abc" "(let [m (java.security.MessageDigest/getInstance \"SHA-256\")] (vec (.digest m (.getBytes \"abc\"))))" [-70 120 22 -65 -113 1 -49 -22 65 65 64 -34 93 -82 34 35 -80 3 97 -93 -106 23 122 -100 -76 16 -1 97 -14 0 21 -83]]
   ["MessageDigest keeps the given algorithm name" "(.getAlgorithm (java.security.MessageDigest/getInstance \"sha-256\"))" "sha-256"]
   ["MessageDigest unknown: NoSuchAlgorithmException" "(try (java.security.MessageDigest/getInstance \"SHA-999\") (catch java.security.NoSuchAlgorithmException e (ex-message e)))" "SHA-999 MessageDigest not available"]
   ["MessageDigest/isEqual" "[(java.security.MessageDigest/isEqual (byte-array [1 2]) (byte-array [1 2])) (java.security.MessageDigest/isEqual (byte-array [1 2]) (byte-array [1 3]))]" [true false]]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
