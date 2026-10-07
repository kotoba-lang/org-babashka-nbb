;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/security.cljs):
;; Ed25519 KeyPairGenerator / KeyFactory / Signature, HMAC, SecureRandom.
;;   node cli.js test-scripts/jvm_security_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): each case "THREW Unable to
;; resolve classname: java.security.KeyPairGenerator" (KeyFactory, Signature,
;; javax.crypto.Mac ...). Once bytes/Base64/StandardCharsets resolved, these
;; became the first failure of 11 of the 45 red suites in the 120-repo
;; sample (KeyFactory 6, Signature 3, KeyPairGenerator 1, SecureRandom 1;
;; denrei / goyoukiki / teian: `(Signature/getInstance "Ed25519")`).
;; Expected values are JDK 21 answers (oracle 2026-09-24): the RFC 8032
;; test-1 key signs "" and "abc" to the same 64 bytes the JDK produces, the
;; key encodings are 44-byte X.509 / 48-byte PKCS#8 with the JDK's prefixes,
;; unknown algorithms and bad specs are refused with the JDK's messages.
(ns jvm-security-test)

(try (load-string "(import '[java.security KeyPairGenerator KeyFactory Signature SecureRandom] '[java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec] '[javax.crypto Mac] '[javax.crypto.spec SecretKeySpec])")
     (catch :default e (println "import:" (ex-message e))))

(def hex "(fn [b] (apply str (map #(.padStart (.toString (bit-and % 0xff) 16) 2 \"0\") b)))")
(def rfc-key
  ;; PKCS#8 v1 prefix + RFC 8032 test 1 secret
  "(.generatePrivate (KeyFactory/getInstance \"Ed25519\") (PKCS8EncodedKeySpec. (byte-array (map #(js/parseInt (apply str %) 16) (partition 2 \"302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60\")))))")

(def cases
  [["RFC 8032 test 1: sign empty message"
    (str "(let [s (Signature/getInstance \"Ed25519\")] (.initSign s " rfc-key ") (" hex " (.sign s)))")
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"]
   ["sign abc"
    (str "(let [s (Signature/getInstance \"Ed25519\")] (.initSign s " rfc-key ") (.update s (.getBytes \"abc\")) (" hex " (.sign s)))")
    "80d724b01e7ca260f4cc7f8de7c95f73cfac615bab1f762b6435b6ec26c8cf6d2c758dae2f87399a8eeda1cbcd2835ac5ba66d6ecaa3aba5e567a751053dc207"]
   ["generated key pair: encodings as the JDK's"
    "(let [kp (.generateKeyPair (KeyPairGenerator/getInstance \"Ed25519\")) pub (.getPublic kp) priv (.getPrivate kp)] [(count (.getEncoded pub)) (.getFormat pub) (.getAlgorithm pub) (vec (take 12 (.getEncoded pub))) (count (.getEncoded priv)) (.getFormat priv) (vec (take 16 (.getEncoded priv)))])"
    [44 "X.509" "EdDSA" [48 42 48 5 6 3 43 101 112 3 33 0] 48 "PKCS#8" [48 46 2 1 0 48 5 6 3 43 101 112 4 34 4 32]]]
   ["sign / verify round trip through encoded keys, tamper fails"
    "(let [kp (.generateKeyPair (KeyPairGenerator/getInstance \"Ed25519\")) kf (KeyFactory/getInstance \"Ed25519\") priv (.generatePrivate kf (PKCS8EncodedKeySpec. (.getEncoded (.getPrivate kp)))) pub (.generatePublic kf (X509EncodedKeySpec. (.getEncoded (.getPublic kp)))) s (Signature/getInstance \"Ed25519\") _ (.initSign s priv) _ (.update s (.getBytes \"msg\")) sig (.sign s) v (Signature/getInstance \"Ed25519\") ok (do (.initVerify v pub) (.update v (.getBytes \"msg\")) (.verify v sig)) bad (do (.initVerify v pub) (.update v (.getBytes \"msh\")) (.verify v sig))] [ok bad])"
    [true false]]
   ["verify of a wrong-length signature: SignatureException"
    "(let [v (Signature/getInstance \"Ed25519\")] (.initVerify v (.getPublic (.generateKeyPair (KeyPairGenerator/getInstance \"Ed25519\")))) (try (.verify v (byte-array 3)) (catch java.security.SignatureException e (ex-message e))))"
    "signature length invalid"]
   ["sign before initSign" "(try (.sign (Signature/getInstance \"Ed25519\")) (catch java.security.SignatureException e (ex-message e)))" "object not initialized for signing"]
   ["unknown algorithms: NoSuchAlgorithmException messages"
    "(mapv #(try (%) (catch java.security.NoSuchAlgorithmException e (ex-message e))) [#(Signature/getInstance \"Foo\") #(KeyPairGenerator/getInstance \"Foo\") #(KeyFactory/getInstance \"Foo\") #(Mac/getInstance \"HmacFoo\")])"
    ["Foo Signature not available" "Foo KeyPairGenerator not available" "Foo KeyFactory not available" "Algorithm HmacFoo not available"]]
   ["bad PKCS#8 spec: InvalidKeySpecException" "(try (.generatePrivate (KeyFactory/getInstance \"Ed25519\") (PKCS8EncodedKeySpec. (byte-array [1 2 3]))) (catch java.security.spec.InvalidKeySpecException e (ex-message e)))"
    "java.security.InvalidKeyException: Unable to decode key"]
   ["HmacSHA256" (str "(let [m (Mac/getInstance \"HmacSHA256\")] (.init m (SecretKeySpec. (.getBytes \"key\") \"HmacSHA256\")) (" hex " (.doFinal m (.getBytes \"The quick brown fox jumps over the lazy dog\"))))")
    "f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8"]
   ["SecureRandom nextBytes fills, nextInt bound" "(let [r (SecureRandom.) b (byte-array 32)] (.nextBytes r b) [(some #(not= 0 %) b) (<= 0 (.nextInt r 10) 9) (try (.nextInt r 0) (catch IllegalArgumentException e (ex-message e)))])"
    [true true "bound must be positive"]]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
