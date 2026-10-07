;; Regression for the JVM-compat scaffolding round 3 (src/nbb/jvm/chars.cljs):
;; java.lang.Character statics and char[] (char-array, aset-char,
;; .toCharArray, Character/toChars, (String. chars off n)), and the
;; range-checked `char`.
;;   node cli.js test-scripts/jvm_chars_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (2b61d53): "THREW Unable to resolve
;; symbol: Character/toChars" (kotoba-lang/comfyui's first failure,
;; `(String. (Character/toChars (int codepoint)))` in its CLIP tokenizer),
;; "... char-array", "... aset-char", "Could not find instance method:
;; toCharArray", and (char 0x1F600) silently answering a different character.
;; Expected values are JDK 21 / Clojure 1.12 answers (oracle 2026-09-24); a
;; char there is a one-unit string here, so [\a \b] is ["a" "b"].
;; Boundary cases: a supplementary code point is TWO chars, 0x110000 and -1
;; are refused with the JDK message, the no-break spaces are not whitespace,
;; the simple case mappings (ß stays ß, İ -> i, ᾳ -> ᾼ), (char 65535) is
;; fine and (char 65536) throws. The last case pins the documented refusal
;; of an unlisted non-decimal numeral.
(ns jvm-chars-test)

(def cases
  [["toChars BMP" "(vec (Character/toChars 65))" ["A"]]
   ["toChars supplementary is a surrogate pair" "(mapv #(.charCodeAt % 0) (Character/toChars 0x1F600))" [0xD83D 0xDE00]]
   ["(String. (Character/toChars cp))" "(= (String. (Character/toChars 0x1F600)) (js/String.fromCodePoint 0x1F600))" true]
   ["toChars -1 / 0x110000 refused"
    "(mapv #(try (Character/toChars %) (catch IllegalArgumentException e (ex-message e))) [-1 0x110000])"
    ["Not a valid Unicode code point: 0xFFFFFFFF" "Not a valid Unicode code point: 0x110000"]]
   ["isDigit (char and int)" "(mapv #(Character/isDigit %) [\"0\" \"9\" \"a\" (char 0x0660) (char 0xBD) (char 0x2167) 48 0x1D7CE 97])"
    [true true false true false false true true false]]
   ["isLetter" "(mapv #(Character/isLetter %) [\"a\" \"Z\" (char 0xE9) (char 0x4E00) \"1\" \"_\" (char 0x02B0) 0x1D400 0x1F600])"
    [true true true true false false true true false]]
   ["isLetterOrDigit / isAlphabetic" "[(mapv #(Character/isLetterOrDigit %) [\"a\" \"1\" \"-\" (char 0x0660)]) (mapv #(Character/isAlphabetic %) [97 49 0x2160 0x0345 0x4E00])]"
    [[true true false true] [true false true true true]]]
   ["isWhitespace: separators but not the no-break spaces"
    "(mapv #(Character/isWhitespace (char %)) [32 9 10 13 11 12 0x1C 0x1F 0xA0 0x2007 0x202F 0x2003 0x3000 0x2028 0x85 0x180E 0x200B 0xFEFF 97])"
    [true true true true true true true true false false false true true true false false false false false]]
   ["isSpaceChar" "(mapv #(Character/isSpaceChar (char %)) [32 9 0xA0 0x2028 0x2029 0x3000 0x180E])" [true false true true true true false]]
   ["isUpperCase / isLowerCase (Other_Uppercase / Other_Lowercase, titlecase is neither)"
    "[(mapv #(Character/isUpperCase (char %)) [65 97 49 0xC0 0x2160 0x24B6 0x01C5]) (mapv #(Character/isLowerCase (char %)) [65 97 49 0xDF 0x2170 0x24D0 0xAA 0x02B0 0x01C5 0x0345])]"
    [[true false false true true true false] [false true false true true true true true false true]]]
   ["getNumericValue" "(mapv #(Character/getNumericValue %) [\"0\" \"7\" \"a\" \"Z\" \"z\" (char 0x0660) (char 0xBD) (char 0x2167) \"-\" (char 0xFF21) (char 0xFF41) (char 0xE9) (char 0x4E00) (char 0x216C) (char 0xB2) (char 0x2460) (char 0x3007) 0x1D7CE 0x1D7D8 0x1D7FF 57])"
    [0 7 10 35 35 0 -2 8 -1 10 10 -1 -1 50 2 1 0 0 0 9 9]]
   ["digit / forDigit" "[(mapv #(Character/digit % 16) [\"0\" \"a\" \"F\" \"g\" (char 0x0660) (char 0xFF21) (char 0xFF46) (char 0xFF47) (char 0xB2)]) (Character/digit 97 10) (Character/digit 55 10) (Character/digit \"1\" 37) (mapv #(.charCodeAt (Character/forDigit % 16) 0) [0 9 10 15 16 -1]) (.charCodeAt (Character/forDigit 5 40) 0)]"
    [[0 10 15 -1 0 10 15 -1 -1] -1 7 -1 [48 57 97 102 0 0] 0]]
   ["toUpperCase / toLowerCase: simple mappings, char in char out, int in int out"
    "[(mapv #(.charCodeAt (Character/toUpperCase (char %)) 0) [97 65 49 0xDF 0xFF 0x01C6 0x1FB3 0x1F80]) (Character/toUpperCase 98) (Character/toUpperCase 0xDF) (mapv #(.charCodeAt (Character/toLowerCase (char %)) 0) [65 97 0x130 0x01C4]) (Character/toLowerCase 66)]"
    [[65 65 49 0xDF 0x178 0x01C4 0x1FBC 0x1F88] 66 0xDF [97 97 105 0x01C6] 98]]
   ["toString valueOf compare getType isTitleCase isIdeographic"
    "[(Character/toString \"a\") (= (Character/toString 0x1F600) (js/String.fromCodePoint 0x1F600)) (Character/valueOf \"x\") (Character/compare \"a\" \"b\") (Character/getType \"a\") (Character/getType \"A\") (Character/getType \"1\") (Character/isTitleCase (char 0x01C5)) (Character/isIdeographic 0x4E00)]"
    ["a" true "x" -1 2 1 9 true true]]
   ["surrogates and code points"
    "[(Character/isSurrogate (char 0xD800)) (Character/isHighSurrogate (char 0xD83D)) (Character/isLowSurrogate (char 0xDE00)) (Character/charCount 0x1F600) (Character/charCount 65) (Character/toCodePoint (char 0xD83D) (char 0xDE00)) (.charCodeAt (Character/highSurrogate 0x1F600) 0) (.charCodeAt (Character/lowSurrogate 0x1F600) 0) (Character/isValidCodePoint 0x110000) (Character/isBmpCodePoint 0x10000) (Character/codePointAt (str \"a\" (js/String.fromCodePoint 0x1F600)) 1)]"
    [true true true 2 1 0x1F600 0xD83D 0xDE00 false false 0x1F600]]
   ["isISOControl and the constants"
    "[(Character/isISOControl (char 0)) (Character/isISOControl (char 0x9F)) (Character/isISOControl (char 0xA0)) (.charCodeAt Character/MIN_VALUE 0) (.charCodeAt Character/MAX_VALUE 0) Character/MIN_RADIX Character/MAX_RADIX Character/MAX_CODE_POINT Character/MIN_CODE_POINT]"
    [true true false 0 65535 2 36 0x10FFFF 0]]
   ["java.lang.Character fully qualified" "(java.lang.Character/isDigit \"5\")" true]
   ;; char[]
   ["char-array from a string, a size, size + fill, a seq, size + short seq"
    "[(vec (char-array \"abc\")) (mapv #(.charCodeAt % 0) (char-array 3)) (vec (char-array 2 \"x\")) (vec (char-array [\"a\" \"b\"])) (mapv #(.charCodeAt % 0) (char-array 3 [\"a\"]))]"
    [["a" "b" "c"] [0 0 0] ["x" "x"] ["a" "b"] [97 0 0]]]
   ["(char-array 2 97) refuses the Long fill, as the JVM does"
    "(try (char-array 2 97) (catch IllegalArgumentException e (ex-message e)))" "Don't know how to create ISeq from: java.lang.Long"]
   ["(String. chars) / (String. chars off n) / its range check"
    "[(String. (char-array \"hi\")) (String. (char-array \"hello\") 1 3) (try (String. (char-array \"hello\") 4 3) (catch StringIndexOutOfBoundsException e (ex-message e)))]"
    ["hi" "ell" "Range [4, 4 + 3) out of bounds for length 5"]]
   ["aset-char / aset" "[(let [a (char-array 2)] (aset-char a 0 \"z\") (.charCodeAt (aget a 1) 0)) (let [a (char-array 2 \"_\")] (aset-char a 0 \"z\") (vec a)) (let [a (char-array 2 \" \")] (aset a 1 \"q\") (String. a))]"
    [0 ["z" "_"] " q"]]
   [".toCharArray / count / apply str / String/valueOf"
    "[(vec (.toCharArray \"hello\")) (count (char-array \"abc\")) (apply str (char-array \"abc\")) (String/valueOf (char-array \"abc\")) (String/valueOf (char-array \"abcde\") 1 2) (count (char-array (js/String.fromCodePoint 97 0x1F600))) (seq (char-array 0)) (alength (char-array 4)) (count (char-array nil))]"
    [["h" "e" "l" "l" "o"] 3 "abc" "abc" "bc" 3 nil 4 0]]
   ["char: range-checked" "[(char 65) (.charCodeAt (char 65535) 0) (try (char 0x1F600) (catch IllegalArgumentException e (ex-message e)))]"
    ["A" 65535 "Value out of range for char: 128512"]]
   ;; documented refusal, pinned
   ["REFUSED: getNumericValue of a numeral outside the table (JDK: -2 for U+0F33)"
    "(try (Character/getNumericValue (char 0x0F33)) (catch UnsupportedOperationException e (subs (ex-message e) 0 36)))"
    "Character.getNumericValue of U+F33 ("]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
