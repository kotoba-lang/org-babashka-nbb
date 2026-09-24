(ns nbb.jvm.security
  "JVM-compatibility SCAFFOLDING, round 2: the java.security / javax.crypto
  subset JVM-era suites use, over node:crypto (see nbb.jvm for why this
  exists; it is not a target for new code).

  Ed25519 only, because Ed25519 is deterministic and its JCA encodings are
  fixed DER: KeyPairGenerator/getInstance \"Ed25519\" (.generateKeyPair
  .getPublic .getPrivate), keys with getEncoded (X.509 SPKI, 44 bytes /
  PKCS#8 v1, 48 bytes -- the same bytes JDK 21 produces) getFormat
  getAlgorithm (\"EdDSA\"), KeyFactory/getInstance \"Ed25519\" with
  X509EncodedKeySpec / PKCS8EncodedKeySpec, Signature/getInstance
  \"Ed25519\" (initSign initVerify update sign verify; RFC 8032 signatures,
  checked against JDK 21). javax.crypto.Mac HmacSHA1/256/384/512 with
  SecretKeySpec. SecureRandom (nextBytes nextInt nextLong) over
  crypto.randomFillSync. Any other algorithm name is refused with the JDK's
  NoSuchAlgorithmException message, not approximated.

  Deliberately NOT here: EC / RSA / X25519 key types, KeyStore, Cipher,
  providers, seeded (deterministic) SecureRandom."
  (:require ["node:crypto" :as crypto]
            [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]))

(def GeneralSecurityException (jvm/defclass "java.security.GeneralSecurityException" jvm/Exception))
(def SignatureException (jvm/defclass "java.security.SignatureException" GeneralSecurityException))
(def InvalidKeyException (jvm/defclass "java.security.InvalidKeyException" GeneralSecurityException))
(def InvalidKeySpecException (jvm/defclass "java.security.spec.InvalidKeySpecException" GeneralSecurityException))

(defn- no-such [msg] (jb/NoSuchAlgorithmException. msg))

(defn- ed25519? [algo] (contains? #{"ED25519" "EDDSA"} (str/upper-case (str algo))))

(deftype JKey [^js key-object kind]
  Object
  (getAlgorithm [_] "EdDSA")
  (getFormat [_] (if (= kind :public) "X.509" "PKCS#8"))
  (getEncoded [_] (jb/->bytes (.export key-object #js {"format" "der" "type" (if (= kind :public) "spki" "pkcs8")})))
  (toString [_] (str "nbb.jvm Ed25519 " (name kind) " key")))

(deftype JKeyPair [pub priv]
  Object
  (getPublic [_] pub)
  (getPrivate [_] priv))

(deftype EncodedKeySpec [bytes fmt]
  Object
  (getEncoded [_] (.slice bytes))
  (getFormat [_] fmt))

(defn- decode-key [^js spec kind]
  (try
    (let [buf (js/Buffer.from (jb/u8 (.-bytes spec)))
          ko (if (= kind :public)
               (crypto/createPublicKey #js {"key" buf "format" "der" "type" "spki"})
               (crypto/createPrivateKey #js {"key" buf "format" "der" "type" "pkcs8"}))]
      (when-not (= "ed25519" (.-asymmetricKeyType ko)) (throw (js/Error. "not ed25519")))
      (JKey. ko kind))
    (catch :default _
      (throw (InvalidKeySpecException. "java.security.InvalidKeyException: Unable to decode key")))))

(deftype Signature [algo ^:mutable mode ^:mutable ^js key ^:mutable parts]
  Object
  (getAlgorithm [_] algo)
  (initSign [_ k] (when-not (and (instance? JKey k) (= :private (.-kind ^js k))) (throw (InvalidKeyException. "Unsupported key type")))
    (set! mode :sign) (set! key k) (set! parts #js []) nil)
  (initVerify [_ k] (when-not (and (instance? JKey k) (= :public (.-kind ^js k))) (throw (InvalidKeyException. "Unsupported key type")))
    (set! mode :verify) (set! key k) (set! parts #js []) nil)
  (update [_ x]
    (when-not mode (throw (SignatureException. "object not initialized for signature or verification")))
    (.push parts (js/Buffer.from (if (number? x) #js [(bit-and x 0xff)] (jb/u8 x)))) nil)
  (update [this b off n] (.update this (.subarray (jb/u8 b) off (+ off n))))
  (sign [_]
    (when-not (= mode :sign) (throw (SignatureException. "object not initialized for signing")))
    (let [msg (js/Buffer.concat parts)]
      (set! parts #js [])
      (jb/->bytes (crypto/sign nil msg (.-key-object key)))))
  (verify [_ sig]
    (when-not (= mode :verify) (throw (SignatureException. "object not initialized for verification")))
    (let [s (jb/u8 sig) msg (js/Buffer.concat parts)]
      (set! parts #js [])
      (when-not (= 64 (.-length s)) (throw (SignatureException. "signature length invalid")))
      (crypto/verify nil msg (.-key-object key) (js/Buffer.from s)))))

(def ^:private hmac-names {"HMACSHA1" "sha1" "HMACSHA256" "sha256" "HMACSHA384" "sha384" "HMACSHA512" "sha512"})

(deftype SecretKeySpec [bytes algo]
  Object
  (getEncoded [_] (.slice bytes))
  (getAlgorithm [_] algo)
  (getFormat [_] "RAW"))

(deftype Mac [algo node-name ^:mutable ^js key ^:mutable parts]
  Object
  (getAlgorithm [_] algo)
  (init [_ k] (set! key k) (set! parts #js []) nil)
  (update [_ x]
    (when-not key (throw (jvm/IllegalStateException. "MAC not initialized")))
    (.push parts (js/Buffer.from (if (number? x) #js [(bit-and x 0xff)] (jb/u8 x)))) nil)
  (doFinal [this] (when-not key (throw (jvm/IllegalStateException. "MAC not initialized")))
    (let [h (crypto/createHmac node-name (js/Buffer.from (jb/u8 (.-bytes key))))]
      (doseq [p parts] (.update h p))
      (set! parts #js [])
      (jb/->bytes (.digest h))))
  (doFinal [this b] (.update this b) (.doFinal this))
  (reset [_] (set! parts #js []) nil)
  (getMacLength [_] (.-length (.digest (crypto/createHmac node-name "")))))

(deftype SecureRandom []
  Object
  (nextBytes [_ b] (crypto/randomFillSync b) nil)
  (nextInt [_] (.readInt32BE (crypto/randomBytes 4) 0))
  (nextInt [_ bound] (when-not (pos? bound) (throw (jvm/IllegalArgumentException. "bound must be positive")))
    (crypto/randomInt bound))
  (nextLong [_] (js/Number (.readBigInt64BE (crypto/randomBytes 8) 0)))
  (nextDouble [_] (/ (js/Number (js* "(~{} >> 11n)" (.readBigUInt64BE (crypto/randomBytes 8) 0))) 9007199254740992))
  (nextBoolean [_] (odd? (aget (crypto/randomBytes 1) 0)))
  (generateSeed [_ n] (jb/->bytes (crypto/randomBytes n))))

(defn- ctor-class [jname cls-name ctor statics]
  (let [c (js* "(function(){ return function(){}; })()")]
    (set! (.-prototype c) (.-prototype cls-name))
    (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" (fn [x] (instance? cls-name x))})
    (doseq [[k v] statics] (gobj/set c k v))
    {:class c :constructor (with-meta (symbol jname) {:sci.impl/constructor ctor})}))

(defn classes []
  (let [kpg (js-obj "getInstance"
                    (fn [algo & _]
                      (if (ed25519? algo)
                        #js {"initialize" (fn [& _] nil)
                             "generateKeyPair" (fn [] (let [^js kp (crypto/generateKeyPairSync "ed25519")]
                                                        (JKeyPair. (JKey. (.-publicKey kp) :public) (JKey. (.-privateKey kp) :private))))
                             "genKeyPair" (fn [] (let [^js kp (crypto/generateKeyPairSync "ed25519")]
                                                   (JKeyPair. (JKey. (.-publicKey kp) :public) (JKey. (.-privateKey kp) :private))))
                             "getAlgorithm" (fn [] algo)}
                        (throw (no-such (str algo " KeyPairGenerator not available"))))))
        kf (js-obj "getInstance"
                   (fn [algo & _]
                     (if (ed25519? algo)
                       #js {"generatePublic" (fn [spec] (decode-key spec :public))
                            "generatePrivate" (fn [spec] (decode-key spec :private))
                            "getAlgorithm" (fn [] algo)}
                       (throw (no-such (str algo " KeyFactory not available"))))))
        sig (js-obj "getInstance"
                    (fn [algo & _]
                      (if (ed25519? algo)
                        (Signature. algo nil nil #js [])
                        (throw (no-such (str algo " Signature not available"))))))
        mac (js-obj "getInstance"
                    (fn [algo & _]
                      (if-let [n (get hmac-names (str/upper-case (str algo)))]
                        (Mac. algo n nil #js [])
                        (throw (no-such (str "Algorithm " algo " not available"))))))]
    {'java.security.KeyPairGenerator kpg
     'java.security.KeyFactory kf
     'java.security.Signature sig
     'java.security.KeyPair JKeyPair
     'java.security.PublicKey JKey
     'java.security.PrivateKey JKey
     'java.security.SecureRandom (ctor-class "java.security.SecureRandom" SecureRandom (fn [& _] (SecureRandom.)) {"getInstanceStrong" (fn [] (SecureRandom.))
                                                                                      "getInstance" (fn [& _] (SecureRandom.))})
     'java.security.spec.X509EncodedKeySpec (ctor-class "java.security.spec.X509EncodedKeySpec" EncodedKeySpec (fn [b] (EncodedKeySpec. (jb/->bytes b) "X.509")) {})
     'java.security.spec.PKCS8EncodedKeySpec (ctor-class "java.security.spec.PKCS8EncodedKeySpec" EncodedKeySpec (fn [b] (EncodedKeySpec. (jb/->bytes b) "PKCS#8")) {})
     'javax.crypto.Mac mac
     'javax.crypto.spec.SecretKeySpec (ctor-class "javax.crypto.spec.SecretKeySpec" SecretKeySpec (fn [b algo] (SecretKeySpec. (jb/->bytes b) algo)) {})
     'java.security.GeneralSecurityException GeneralSecurityException
     'java.security.SignatureException SignatureException
     'java.security.InvalidKeyException InvalidKeyException
     'java.security.spec.InvalidKeySpecException InvalidKeySpecException}))
