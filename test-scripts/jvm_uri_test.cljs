;; Regression for the JVM-compat scaffolding round 2 (src/nbb/jvm/net.cljs):
;; java.net.URI as java.net.URI parses it (RFC 2396), URLEncoder / URLDecoder.
;;   node cli.js test-scripts/jvm_uri_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (84f9b41): each case "THREW Unable to
;; resolve classname: java.net.URI" -- the first failure of
;; network-awai/actor-minidrama in the 120-repo sample after round 1.
;; Expected values are JDK 21 answers (oracle 2026-09-24), including where
;; js/URL would answer differently: toString keeps "HTTP://A.com/x" as given,
;; "http://a.com" has path "" and port -1, a host with '_' is nil, and the
;; RFC 2396 resolve table (base http://a/b/c/d;p?q) with Java's own quirks
;; ("" -> http://a/b/c/, "../../../g" -> http://a/../g).
(ns jvm-uri-test)

(try (load-string "(import '[java.net URI URLEncoder URLDecoder])") (catch :default e (println "import:" (ex-message e))))

(def base "http://a/b/c/d;p?q")

(def resolve-table
  [["g" "http://a/b/c/g"] ["./g" "http://a/b/c/g"] ["g/" "http://a/b/c/g/"] ["/g" "http://a/g"]
   ["//g" "http://g"] ["?y" "http://a/b/c/?y"] ["g?y" "http://a/b/c/g?y"] ["#s" "http://a/b/c/d;p?q#s"]
   ["g#s" "http://a/b/c/g#s"] [";x" "http://a/b/c/;x"] ["" "http://a/b/c/"] ["." "http://a/b/c/"]
   [".." "http://a/b/"] ["../g" "http://a/b/g"] ["../.." "http://a/"] ["../../g" "http://a/g"]
   ["../../../g" "http://a/../g"] ["/./g" "http://a/./g"] ["g." "http://a/b/c/g."] ["..g" "http://a/b/c/..g"]
   ["./../g" "http://a/b/g"] ["g/./h" "http://a/b/c/g/h"] ["g/../h" "http://a/b/c/h"] ["http:g" "http:g"]])

(def cases
  (into
   [["components" "(let [u (URI/create \"http://user:pw@Example.com:8080/a%20b/c?x=1%202&y#frag%21\")] [(.getScheme u) (.getHost u) (.getPort u) (.getPath u) (.getRawPath u) (.getQuery u) (.getRawQuery u) (.getFragment u) (.getUserInfo u) (.getAuthority u) (str u)])"
     ["http" "Example.com" 8080 "/a b/c" "/a%20b/c" "x=1 2&y" "x=1%202&y" "frag!" "user:pw" "user:pw@Example.com:8080" "http://user:pw@Example.com:8080/a%20b/c?x=1%202&y#frag%21"]]
    ["no path, no port" "(let [u (URI/create \"http://a.com\")] [(.getPath u) (.getPort u) (.getQuery u)])" ["" -1 nil]]
    ["opaque" "(let [u (URI/create \"mailto:a@b.com\")] [(.isOpaque u) (.getPath u) (.getSchemeSpecificPart u) (.getHost u)])" [true nil "a@b.com" nil]]
    ["relative" "(let [u (URI/create \"../x/y?q\")] [(.getScheme u) (.getPath u) (.getQuery u) (.isAbsolute u)])" [nil "../x/y" "q" false]]
    ["toString is the original text" "(str (URI/create \"HTTP://A.com/x\"))" "HTTP://A.com/x"]
    ["= ignores scheme/host case" "(= (URI/create \"HTTP://A.com/x\") (URI/create \"http://a.com/x\"))" true]
    ["registry authority: host nil" "(.getHost (URI/create \"http://a_b.com/x\"))" nil]
    ["IPv6 host keeps brackets" "(let [u (URI/create \"http://[::1]:9/\")] [(.getHost u) (.getPort u)])" ["[::1]" 9]]
    ["illegal char: URI/create wraps URISyntaxException in IAE" "(try (URI/create \"http://a.com/a b\") (catch IllegalArgumentException e [(ex-message e) (instance? java.net.URISyntaxException (.getCause e))]))"
     ["Illegal character in path at index 14: http://a.com/a b" true]]
    ["illegal char: (URI. s) throws URISyntaxException" "(try (URI. \"http://a.com/a b\") (catch java.net.URISyntaxException e (ex-message e)))" "Illegal character in path at index 14: http://a.com/a b"]
    ["illegal char in query" "(try (URI/create \"http://a.com/?q=a b\") (catch IllegalArgumentException e (ex-message e)))" "Illegal character in query at index 17: http://a.com/?q=a b"]
    ["malformed escape" "(try (URI/create \"http://a.com/%zz\") (catch IllegalArgumentException e (ex-message e)))" "Malformed escape pair at index 13: http://a.com/%zz"]
    ["normalize" "(str (.normalize (URI/create \"http://a/b/../c/./d\")))" "http://a/c/d"]
    ["resolve against a base without a path" "(str (.resolve (URI/create \"http://a.com\") \"x\"))" "http://a.com/x"]
    ["multi-arg constructor quotes" "(str (URI. \"http\" \"a.com\" \"/x y\" nil))" "http://a.com/x%20y"]
    ["URLEncoder / URLDecoder" "[(URLEncoder/encode \"a b&c=é*~\" java.nio.charset.StandardCharsets/UTF_8) (URLDecoder/decode \"a+b%26c%3D%C3%A9\" java.nio.charset.StandardCharsets/UTF_8) (URLEncoder/encode \"x y\" \"UTF-8\")]"
     ["a+b%26c%3D%C3%A9*%7E" "a b&c=é" "x+y"]]]
   (for [[r want] resolve-table]
     [(str "resolve " (pr-str r)) (str "(str (.resolve (URI/create \"" base "\") \"" r "\"))") want])))

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
