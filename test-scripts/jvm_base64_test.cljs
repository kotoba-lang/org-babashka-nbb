;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/bytes.cljs):
;; java.util.Base64 encoders and decoders over byte[] (signed Int8Array).
;;   node cli.js test-scripts/jvm_base64_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): each case "THREW Unable to
;; resolve classname: java.util.Base64" -- the first failure of 3 of the 48
;; red suites in the 120-repo sample after round 1 (denrei, goyoukiki, teian:
;; `(.decode (Base64/getDecoder) cacao)`).
;; Expected values and messages are JDK 21 answers (oracle run 2026-09-24):
;; the basic decoder takes missing padding but refuses '-' (url alphabet),
;; a dangling single char, "xx=" without the second '=', text after the
;; padding; the MIME decoder skips non-alphabet bytes.
(ns jvm-base64-test)

(def cases
  [["encodeToString" "(.encodeToString (java.util.Base64/getEncoder) (byte-array [-5 -1]))" "+/8="]
   ["encodeToString of empty" "(.encodeToString (java.util.Base64/getEncoder) (byte-array 0))" ""]
   ["url encoder without padding" "(.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) (byte-array [-5 -1]))" "-_8"]
   ["encode answers ASCII bytes" "(vec (.encode (java.util.Base64/getEncoder) (.getBytes \"hi\")))" [97 71 107 61]]
   ["MIME encoder wraps at 76 with CRLF" "(.encodeToString (java.util.Base64/getMimeEncoder) (byte-array 60))" (str (apply str (repeat 76 "A")) "\r\n" "AAAA")]
   ["decode without padding" "(vec (.decode (java.util.Base64/getDecoder) \"+/8\"))" [-5 -1]]
   ["decode with padding" "(vec (.decode (java.util.Base64/getDecoder) \"+/8=\"))" [-5 -1]]
   ["decode of bytes" "(vec (.decode (java.util.Base64/getDecoder) (.getBytes \"aGk=\")))" [104 105]]
   ["decode empty" "(vec (.decode (java.util.Base64/getDecoder) \"\"))" []]
   ["round trip of every byte value"
    "(let [b (byte-array (range -128 128))] (= (vec b) (vec (.decode (java.util.Base64/getDecoder) (.encodeToString (java.util.Base64/getEncoder) b)))))" true]
   ["url alphabet refused by the basic decoder" "(try (.decode (java.util.Base64/getDecoder) \"-_8=\") (catch IllegalArgumentException e (ex-message e)))" "Illegal base64 character 2d"]
   ["basic alphabet refused by the url decoder" "(try (.decode (java.util.Base64/getUrlDecoder) \"+/8\") (catch IllegalArgumentException e (ex-message e)))" "Illegal base64 character 2b"]
   ["url decoder" "(vec (.decode (java.util.Base64/getUrlDecoder) \"-_8\"))" [-5 -1]]
   ["space is illegal" "(try (.decode (java.util.Base64/getDecoder) \"AA AA\") (catch IllegalArgumentException e (ex-message e)))" "Illegal base64 character 20"]
   ["é is read as the ISO-8859-1 byte 0xE9, printed as signed hex" "(try (.decode (java.util.Base64/getDecoder) \"AAé=\") (catch IllegalArgumentException e (ex-message e)))" "Illegal base64 character -17"]
   ["one char" "(try (.decode (java.util.Base64/getDecoder) \"A\") (catch IllegalArgumentException e (ex-message e)))" "Input byte[] should at least have 2 bytes for base64 bytes"]
   ["dangling single char" "(try (.decode (java.util.Base64/getDecoder) \"AAAAA\") (catch IllegalArgumentException e (ex-message e)))" "Last unit does not have enough valid bits"]
   ["x= ending" "(try (.decode (java.util.Base64/getDecoder) \"AAAAA=\") (catch IllegalArgumentException e (ex-message e)))" "Last unit does not have enough valid bits"]
   ["xx= ending" "(try (.decode (java.util.Base64/getDecoder) \"AA=\") (catch IllegalArgumentException e (ex-message e)))" "Input byte array has wrong 4-byte ending unit"]
   ["xx=y ending" "(try (.decode (java.util.Base64/getDecoder) \"AA=A\") (catch IllegalArgumentException e (ex-message e)))" "Input byte array has wrong 4-byte ending unit"]
   ["= after a full unit" "(try (.decode (java.util.Base64/getDecoder) \"AAAA=\") (catch IllegalArgumentException e (ex-message e)))" "Input byte array has wrong 4-byte ending unit"]
   ["text after padding" "(try (.decode (java.util.Base64/getDecoder) \"AA==AA\") (catch IllegalArgumentException e (ex-message e)))" "Input byte array has incorrect ending byte at 4"]
   ["MIME decoder skips CRLF and junk" "(vec (.decode (java.util.Base64/getMimeDecoder) \"+/\\r\\n8=!\"))" [-5 -1]]
   ["MIME decoder dangling char" "(try (.decode (java.util.Base64/getMimeDecoder) \"A\") (catch IllegalArgumentException e (ex-message e)))" "Last unit does not have enough valid bits"]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
