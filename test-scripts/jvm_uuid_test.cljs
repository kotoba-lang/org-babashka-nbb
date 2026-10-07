;; Regression for the JVM-compat scaffolding round 3 (src/nbb/jvm/uuid.cljs):
;; java.util.UUID over cljs.core/UUID.
;;   node cli.js test-scripts/jvm_uuid_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (2b61d53): "import: Unable to resolve
;; classname: java.util.UUID" and every case "THREW Unable to resolve
;; symbol: UUID/fromString" -- cloud-itonami/app-local-ai's first failure
;; (`(:import [java.util UUID])` in ai.gftd.local.store).
;; Expected values are JDK 21 / Clojure 1.12 answers (oracle 2026-09-24).
;; Boundary cases: the lenient fromString masks each field to its width
;; ("123456789-1-1-1-1" drops the 1), the 36-character fast path with a
;; non-hex digit falls back to the lenient path and its "Error at index"
;; message, an empty field is NumberFormatException "", 37 characters is
;; "too large", compareTo on the sign bit (8000... is LESS than 0000...).
;; The last cases pin the documented deviations: `compare` is string
;; order here, and a half beyond 2^53 refuses to become a number.
(ns jvm-uuid-test)

(try (load-string "(import (quote [java.util UUID]))") (catch :default e (println "import:" (ex-message e))))

(def cases
  [["fromString lowercases, equals the #uuid literal"
    "[(str (UUID/fromString \"ABCDEF01-2345-6789-ABCD-EF0123456789\")) (= (UUID/fromString \"ABCDEF01-2345-6789-ABCD-EF0123456789\") #uuid \"abcdef01-2345-6789-abcd-ef0123456789\")]"
    ["abcdef01-2345-6789-abcd-ef0123456789" true]]
   ["lenient form, masked fields"
    "(mapv #(str (UUID/fromString %)) [\"1-2-3-4-5\" \"123456789-1-1-1-1\" \"1-12345-1-1-1\" \"1-1-1-12345-1234567890123\" \"+1-2-3-4-5\" \"0000000-00000-0000-0000-000000000001\" \"7fffffffffffffff-1-1-1-1\"])"
    ["00000001-0002-0003-0004-000000000005" "23456789-0001-0001-0001-000000000001" "00000001-2345-0001-0001-000000000001"
     "00000001-0001-0001-2345-234567890123" "00000001-0002-0003-0004-000000000005" "00000000-0000-0000-0000-000000000001"
     "ffffffff-0001-0001-0001-000000000001"]]
   ["fromString errors: class and message"
    "(mapv #(try (UUID/fromString %) (catch NumberFormatException e [:nfe (ex-message e)]) (catch IllegalArgumentException e [:iae (ex-message e)])) [\"1-2-3-4\" \"1-2-3-4-5-6\" \"x-2-3-4-5\" \"-2-3-4-5\" \"1234567890abcdef1234567890abcdef12345\" \"g2345678-1234-1234-1234-123456789012\" \"12345678+1234-1234-1234-123456789012\" \"\" \"-1-2-3-4-5\" \"00000000-0000-0000-0000-00000000000+\" \"fffffffffffffffff-1-1-1-1\" \"8000000000000000-1-1-1-1\" \"+-1-1-1-1\" \"1-1-1-1-\"])"
    [[:iae "Invalid UUID string: 1-2-3-4"] [:iae "Invalid UUID string: 1-2-3-4-5-6"] [:nfe "Error at index 0 in: \"x\""] [:nfe ""]
     [:iae "UUID string too large"] [:nfe "Error at index 0 in: \"g2345678\""] [:iae "Invalid UUID string: 12345678+1234-1234-1234-123456789012"]
     [:iae "Invalid UUID string: "] [:iae "Invalid UUID string: -1-2-3-4-5"] [:nfe "Error at index 11 in: \"00000000000+\""]
     [:nfe "Error at index 15 in: \"fffffffffffffffff\""] [:nfe "Error at index 15 in: \"8000000000000000\""] [:nfe "Error at index 1 in: \"+\""] [:nfe ""]]]
   ["hashCode is the JDK's" "[(.hashCode (UUID/fromString \"abcdef01-2345-6789-abcd-ef0123456789\")) (.hashCode (UUID/fromString \"00000000-0000-0001-0000-000000000002\"))]" [0 3]]
   ["compareTo: signed halves"
    "[(.compareTo (UUID/fromString \"80000000-0000-0000-0000-000000000000\") (UUID/fromString \"00000000-0000-0000-0000-000000000000\")) (.compareTo (UUID/fromString \"00000000-0000-0000-0000-000000000001\") (UUID/fromString \"00000000-0000-0000-8000-000000000000\")) (.compareTo (UUID/fromString \"00000000-0000-0000-0000-000000000001\") (UUID/fromString \"00000000-0000-0000-0000-000000000001\"))]"
    [-1 1 0]]
   ["version / variant" "[(.version (UUID/fromString \"abcdef01-2345-6789-abcd-ef0123456789\")) (mapv #(.variant (UUID/fromString (str \"abcdef01-2345-6789-\" % \"bcd-ef0123456789\"))) [\"0\" \"a\" \"c\" \"e\"])]"
    [6 [0 2 6 7]]]
   ["nameUUIDFromBytes (MD5, version 3)" "[(str (UUID/nameUUIDFromBytes (.getBytes \"hello\" \"UTF-8\"))) (str (UUID/nameUUIDFromBytes (byte-array 0)))]"
    ["5d41402a-bc4b-3a76-b971-9d911017c592" "d41d8cd9-8f00-3204-a980-0998ecf8427e"]]
   ["randomUUID: version 4, IETF variant, a UUID" "(let [u (UUID/randomUUID)] [(.version u) (.variant u) (instance? UUID u) (uuid? u)])" [4 2 true true]]
   ["equals" "[(.equals (UUID/fromString \"1-2-3-4-5\") (UUID/fromString \"00000001-0002-0003-0004-000000000005\")) (.equals (UUID/fromString \"1-2-3-4-5\") \"00000001-0002-0003-0004-000000000005\")]" [true false]]
   ["instance? on the #uuid literal and random-uuid" "[(instance? UUID #uuid \"00000001-0002-0003-0004-000000000005\") (instance? java.util.UUID (random-uuid)) (instance? UUID \"00000001-0002-0003-0004-000000000005\")]" [true true false]]
   ["getMostSignificantBits within 2^53" "(.getMostSignificantBits (UUID/fromString \"00000001-0002-0003-0004-000000000005\"))" 4295098371]
   ["fromString nil" "(try (UUID/fromString nil) (catch :default e (ex-message e)))" "Cannot invoke \"String.length()\" because \"name\" is null"]
   ;; documented deviations, pinned
   ["DEVIATION: compare/sort is string order here (the JVM sorts 8000... first)"
    "(mapv str (sort [(UUID/fromString \"80000000-0000-0000-0000-000000000000\") (UUID/fromString \"00000000-0000-0000-0000-000000000001\")]))"
    ["00000000-0000-0000-0000-000000000001" "80000000-0000-0000-0000-000000000000"]]
   ["DEVIATION: a half beyond 2^53 refuses to become a number"
    "(try (.getLeastSignificantBits (UUID/fromString \"00000000-0000-0000-7fff-ffffffffffff\")) (catch UnsupportedOperationException e (subs (ex-message e) 0 27)))"
    "UUID.getLeastSignificantBit"]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
