(ns nbb.jvm.net
  "JVM-compatibility SCAFFOLDING, round 2: java.net.URI (RFC 2396 as
  java.net.URI parses it, not the WHATWG URL that js/URL implements) and
  java.net.URLEncoder / URLDecoder (see nbb.jvm for why this exists; it is
  not a target for new code).

  URI: create / new (1-arg and the (scheme host path fragment) form),
  getScheme getRawAuthority getAuthority getUserInfo getHost getPort
  getRawPath getPath getRawQuery getQuery getRawFragment getFragment
  getSchemeSpecificPart isAbsolute isOpaque resolve normalize relativize
  toString toASCIIString, equals / = (scheme and host case-insensitive).
  toString is the original string, as on the JVM (js/URL would rewrite
  \"HTTP://A.com\" and add a '/' path). Illegal characters and malformed
  escapes are refused with the JVM's URISyntaxException message (wrapped in
  IllegalArgumentException by URI/create). A registry-based authority
  (\"a_b.com\") has getHost nil, as on the JVM.

  Deliberately NOT here: toURL / URL (network access), IDN, parseServerAuthority."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]))

(def URISyntaxException (jvm/defclass "java.net.URISyntaxException" jvm/Exception))

(def ^:private re-uri #"^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#(.*))?$")

(defn- syntax-error [what idx s]
  (URISyntaxException. (str what " at index " idx ": " s)))

(def ^:private legal
  ;; unreserved | reserved | escaped | other (non-ASCII, non-control)
  #"[A-Za-z0-9\-_.!~*'();/?:@&=+$,\[\]%]")

(defn- check-chars
  "Illegal character / malformed escape check with the component the JVM
  names for the index."
  [s scheme-end auth-range path-end query-end]
  (let [n (count s)]
    (loop [i 0]
      (when (< i n)
        (let [c (.charAt s i)
              code (.charCodeAt s i)
              component (cond (and scheme-end (< i scheme-end)) "scheme name"
                              (and auth-range (<= (first auth-range) i) (< i (second auth-range))) "authority"
                              (< i path-end) "path"
                              (< i query-end) "query"
                              :else "fragment")]
          (cond (= "%" c)
                (if (and (< (+ i 2) (inc n)) (re-matches #"[0-9A-Fa-f]{2}" (subs s (inc i) (min n (+ i 3)))))
                  (recur (+ i 3))
                  (throw (syntax-error "Malformed escape pair" i s)))
                (= "#" c) (if (and (>= i query-end) (str/index-of s "#" (inc i)))
                            (throw (syntax-error "Illegal character in fragment" (str/index-of s "#" (inc i)) s))
                            (recur (inc i)))
                (and (contains? #{"[" "]"} c) (= "path" component))
                (throw (syntax-error "Illegal character in path" i s))
                (or (re-matches legal c)
                    (and (> code 0x7f) (not (<= 0x80 code 0x9f)) (not (.test (js/RegExp. "\\p{Z}" "u") c))))
                (recur (inc i))
                :else (throw (syntax-error (str "Illegal character in " component) i s))))))))

(defn- pct-decode [s]
  (when s
    (if-not (str/includes? s "%") s
            (let [parts (re-seq #"%[0-9A-Fa-f]{2}|[^%]+" s)
                  out (atom "")
                  pending (atom #js [])]
              (letfn [(flush! [] (when (pos? (alength @pending))
                                   (swap! out str (jb/decode (.from js/Uint8Array @pending) jb/UTF_8))
                                   (reset! pending #js [])))]
                (doseq [p parts]
                  (if (str/starts-with? p "%")
                    (.push @pending (js/parseInt (subs p 1) 16))
                    (do (flush!) (swap! out str p))))
                (flush!))
              @out))))

(def ^:private re-hostname #"^(?:[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?\.)*[A-Za-z0-9](?:[A-Za-z0-9\-]*[A-Za-z0-9])?\.?$")
(def ^:private re-ipv4 #"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")

(defn- parse-server
  "[user-info host port] of a server-based authority, or nil (registry-based)."
  [auth]
  (let [[ui hp] (if-let [i (str/last-index-of auth "@")] [(subs auth 0 i) (subs auth (inc i))] [nil auth])
        [host port] (cond (str/starts-with? hp "[")
                          (let [j (str/index-of hp "]")]
                            (when j [(subs hp 0 (inc j)) (let [r (subs hp (inc j))] (when (str/starts-with? r ":") (subs r 1)))]))
                          :else (if-let [k (str/last-index-of hp ":")] [(subs hp 0 k) (subs hp (inc k))] [hp nil]))]
    (when (and host
               (or (str/starts-with? host "[") (re-matches re-ipv4 host) (re-matches re-hostname host))
               (or (nil? port) (= "" port) (re-matches #"\d+" port)))
      [ui host (if (or (nil? port) (= "" port)) -1 (js/parseInt port 10))])))

(declare make-uri uri-instance? normalize-path)

(deftype URI [string scheme ssp authority user-info host port path query fragment]
  Object
  (getScheme [_] scheme)
  (getRawSchemeSpecificPart [_] ssp)
  (getSchemeSpecificPart [_] (pct-decode ssp))
  (getRawAuthority [_] authority)
  (getAuthority [_] (pct-decode authority))
  (getRawUserInfo [_] user-info)
  (getUserInfo [_] (pct-decode user-info))
  (getHost [_] host)
  (getPort [_] port)
  (getRawPath [_] path)
  (getPath [_] (pct-decode path))
  (getRawQuery [_] query)
  (getQuery [_] (pct-decode query))
  (getRawFragment [_] fragment)
  (getFragment [_] (pct-decode fragment))
  (isAbsolute [_] (some? scheme))
  (isOpaque [_] (nil? path))
  (toString [_] string)
  (toASCIIString [_] (str/replace string #"[^\x00-\x7F]+" (fn [m] (js/encodeURIComponent m))))
  (resolve [this child]
    (let [^js c (if (uri-instance? child) child (make-uri (str child) false))]
      (cond
        (or (.isOpaque c) (.isOpaque this)) c
        (and (nil? (.-scheme c)) (nil? (.-authority c)) (= "" (.-path c)) (some? (.-fragment c)) (nil? (.-query c)))
        (if (and fragment (= fragment (.-fragment c))) this
            (make-uri (str (when scheme (str scheme ":")) (when authority (str "//" authority)) path
                           (when query (str "?" query)) "#" (.-fragment c)) false))
        (some? (.-scheme c)) c
        :else
        (let [[auth p] (if (some? (.-authority c))
                         [(.-authority c) (.-path c)]
                         [authority
                          (let [cp (.-path c)]
                            (if (str/starts-with? cp "/") cp
                                (let [i (str/last-index-of path "/")
                                      base (if i (subs path 0 (inc i)) "")
                                      joined (if (= "" cp) base
                                                 (if (or i (nil? scheme)) (str base cp) (str "/" cp)))]
                                  (normalize-path joined))))])]
          (make-uri (str (when scheme (str scheme ":")) (when auth (str "//" auth)) p
                         (when (.-query c) (str "?" (.-query c))) (when (.-fragment c) (str "#" (.-fragment c))))
                    false)))))
  (normalize [this]
    (if (or (nil? path) (= path (normalize-path path))) this
        (make-uri (str (when scheme (str scheme ":")) (when authority (str "//" authority)) (normalize-path path)
                       (when query (str "?" query)) (when fragment (str "#" fragment))) false)))
  (relativize [this child]
    (let [^js c (if (uri-instance? child) child (make-uri (str child) false))]
      (if (or (.isOpaque c) (.isOpaque this)
              (not= (some-> scheme str/lower-case) (some-> (.-scheme c) str/lower-case))
              (not= authority (.-authority c)))
        c
        (let [bp (normalize-path path) cp (normalize-path (.-path c))
              bp (if (str/ends-with? bp "/") bp (str bp "/"))]
          (if (and (not= bp cp) (not (str/starts-with? cp bp))) c
              (make-uri (str (subs cp (min (count cp) (count bp)))
                             (when (.-query c) (str "?" (.-query c))) (when (.-fragment c) (str "#" (.-fragment c))))
                        false))))))
  (equals [this o] (-equiv this o))
  (hashCode [_] (hash (str (some-> scheme str/lower-case) "|" (some-> host str/lower-case) "|" string)))
  (compareTo [_ o] (compare string (.-string ^js o)))
  IEquiv
  (-equiv [_ o] (and (uri-instance? o)
                     (= (some-> scheme str/lower-case) (some-> (.-scheme ^js o) str/lower-case))
                     (= fragment (.-fragment ^js o))
                     (if (nil? path)
                       (= ssp (.-ssp ^js o))
                       (and (= path (.-path ^js o)) (= query (.-query ^js o))
                            (if host
                              (and (= user-info (.-user-info ^js o))
                                   (= (str/lower-case host) (some-> (.-host ^js o) str/lower-case))
                                   (= port (.-port ^js o)))
                              (= authority (.-authority ^js o)))))))
  IHash
  (-hash [_] (hash (str (some-> scheme str/lower-case) "|" (some-> host str/lower-case) "|" path "|" query "|" fragment)))
  IComparable
  (-compare [_ o] (compare string (.-string ^js o)))
  IPrintWithWriter
  (-pr-writer [_ w _] (-write w (str "#object[java.net.URI \"" string "\"]"))))

(defn uri-instance? [x] (instance? URI x))

(defn normalize-path
  "java.net.URI.normalize(String): drop '.' segments, fold 'seg/..' (never a
  leading '..'), keep a trailing '/'; a relative first segment with ':' gets
  './' prepended."
  [p]
  (if (or (nil? p) (= "" p)) p
      (let [abs? (str/starts-with? p "/")
            segs (str/split (if abs? (subs p 1) p) #"/" -1)
            trailing? (or (str/ends-with? p "/") (contains? #{"." ".."} (peek segs)))
            out (reduce (fn [acc s]
                          (cond (= "." s) acc
                                (= "" s) acc
                                (= ".." s) (if (and (seq acc) (not= ".." (peek acc))) (pop acc) (conj acc s))
                                :else (conj acc s)))
                        [] segs)
            body (str/join "/" out)
            body (if (and (not abs?) (seq out) (str/includes? (first out) ":")) (str "./" body) body)]
        (str (when abs? "/") body (when (and trailing? (seq out)) "/")))))

(defn make-uri
  "Parse s as java.net.URI; wrap? = URI/create (IllegalArgumentException
  around the URISyntaxException)."
  [s wrap?]
  (when (nil? s) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"str\" is null")))
  (try
    (let [s (str s)
          [_ scheme auth path query fragment] (re-matches re-uri s)
          scheme-end (when scheme (count scheme))
          auth-start (when (some? auth) (+ (if scheme (inc (count scheme)) 0) 2))
          path-start (+ (or auth-start (if scheme (inc (count scheme)) 0)) (count (or auth "")))
          path-end (+ path-start (count path))
          query-end (if (some? query) (+ path-end 1 (count query)) path-end)]
      (when (and scheme (not (re-matches #"[A-Za-z][A-Za-z0-9+\-.]*" scheme)))
        (throw (syntax-error "Illegal character in scheme name" (or (some #(when-not (re-matches #"[A-Za-z0-9+\-.]" (.charAt scheme %)) %) (range (count scheme))) 0) s)))
      (when (str/starts-with? s ":") (throw (syntax-error "Expected scheme name" 0 s)))
      (check-chars s scheme-end (when auth-start [auth-start (+ auth-start (count auth))]) path-end query-end)
      (let [opaque? (and scheme (nil? auth) (not (str/starts-with? path "/")))
            ssp (subs s (if scheme (inc (count scheme)) 0) (if (some? fragment) (- (count s) (count fragment) 1) (count s)))
            [ui host port] (when (and (some? auth) (not= "" auth)) (parse-server auth))]
        (when (and scheme (= "" ssp)) (throw (syntax-error "Expected scheme-specific part" (count s) s)))
        (URI. s scheme ssp (when (and (some? auth) (not= "" auth)) auth) ui host (or port -1)
              (if opaque? nil path) (if opaque? nil query) fragment)))
    (catch :default e
      (if (and wrap? (instance? URISyntaxException e))
        (throw (jvm/IllegalArgumentException. (.-message e) e))
        (throw e)))))

(defn- quote-component [s allowed]
  (str/join (map (fn [ch] (if (or (re-matches allowed ch) (> (.charCodeAt ch 0) 0x7f)) ch
                              (str/join (map #(str "%" (str/upper-case (.padStart (.toString (bit-and % 0xff) 16) 2 "0")))
                                             (jb/encode ch jb/UTF_8)))))
                 (map str (seq s)))))

(defn new-uri
  "(URI. s) (URI. scheme ssp fragment) (URI. scheme host path fragment)
  (URI. scheme user-info host port path query fragment)"
  ([s] (make-uri s false))
  ([scheme ssp fragment]
   (make-uri (str (when scheme (str scheme ":")) (quote-component (or ssp "") #"[A-Za-z0-9\-_.!~*'();/?:@&=+$,\[\]]")
                  (when fragment (str "#" (quote-component fragment #"[A-Za-z0-9\-_.!~*'();/?:@&=+$,\[\]]")))) false))
  ([scheme host path fragment] (new-uri scheme nil host -1 path nil fragment))
  ([scheme user-info host port path query fragment]
   (make-uri (str (when scheme (str scheme ":"))
                  (when host (str "//" (when user-info (str (quote-component user-info #"[A-Za-z0-9\-_.!~*'();:&=+$,]") "@"))
                                  host (when (not= -1 port) (str ":" port))))
                  (when path (quote-component path #"[A-Za-z0-9\-_.!~*'();/:@&=+$,]"))
                  (when query (str "?" (quote-component query #"[A-Za-z0-9\-_.!~*'();/?:@&=+$,\[\]]")))
                  (when fragment (str "#" (quote-component fragment #"[A-Za-z0-9\-_.!~*'();/?:@&=+$,\[\]]"))))
             false)))

(def URIClass
  (let [c (js* "(function(){ var U = function URI(){}; return U; })()")]
    (set! (.-prototype c) (.-prototype URI))
    (js/Object.defineProperty c js/Symbol.hasInstance #js {"value" (fn [x] (instance? URI x))})
    (gobj/set c "create" (fn [s] (make-uri s true)))
    c))

;; ---------------------------------------------------------------------------
;; URLEncoder / URLDecoder (application/x-www-form-urlencoded)
;; ---------------------------------------------------------------------------

(defn- ->cs [cs] (if (instance? jb/Charset cs) cs (jb/charset-for-name cs)))

(defn url-encode
  ([s] (url-encode s jb/UTF_8))
  ([s cs]
   (let [cs (->cs cs)]
     (str/join (map (fn [ch]
                      (cond (re-matches #"[A-Za-z0-9.\-*_]" ch) ch
                            (= " " ch) "+"
                            :else (str/join (map #(str "%" (str/upper-case (.padStart (.toString (bit-and % 0xff) 16) 2 "0")))
                                                 (jb/encode ch cs)))))
                    ;; code points, so a surrogate pair encodes as one character
                    (js/Array.from (str s)))))))

(defn url-decode
  ([s] (url-decode s jb/UTF_8))
  ([s cs]
   (let [cs (->cs cs)
         s (str s)
         n (count s)]
     (loop [i 0 out ""]
       (if (>= i n) out
           (let [c (.charAt s i)]
             (cond (= "+" c) (recur (inc i) (str out " "))
                   (= "%" c)
                   (let [bytes #js []
                         j (loop [j i]
                             (if (and (< j n) (= "%" (.charAt s j)))
                               (do (when (> (+ j 3) n)
                                     (throw (jvm/IllegalArgumentException. "URLDecoder: Incomplete trailing escape (%) pattern")))
                                   (let [h (subs s (inc j) (+ j 3))]
                                     (when-not (re-matches #"[0-9A-Fa-f]{2}" h)
                                       (throw (jvm/IllegalArgumentException.
                                               (str "URLDecoder: Illegal hex characters in escape (%) pattern - Error at index 0 in: \"" h "\""))))
                                     (.push bytes (js/parseInt h 16))
                                     (recur (+ j 3))))
                               j))]
                     (recur j (str out (jb/decode (.from js/Uint8Array bytes) cs))))
                   :else (recur (inc i) (str out c)))))))))

(defn classes []
  {'java.net.URI {:class URIClass
                  :constructor (with-meta 'java.net.URI {:sci.impl/constructor new-uri})}
   'java.net.URISyntaxException URISyntaxException
   'java.net.URLEncoder (js-obj "encode" url-encode)
   'java.net.URLDecoder (js-obj "decode" url-decode)})
