;; Regression for src/nbb/bigint_hash.cljs: cljs.core/hash of a JS BigInt.
;;   node cli.js test-scripts/bigint_hash_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (95ff779): every case that hashes a
;; BigInt answered "THREW Cannot create property 'closure_uid_866371168' on
;; bigint '0'" -- (hash 5n), a set of nine BigInts, a map of nine BigInt
;; keys, a vector holding a BigInt used as a set element. Eight elements
;; worked (a PersistentArraySet compares with `=` and never hashes), so the
;; 8 / 9 boundary is pinned both ways below.
;; Expected hashes are Clojure 1.12 on the JVM (oracle 2026-09-25):
;;   (mapv hash [0 1 5 -1 7 255 9007199254740991 -9007199254740991
;;               9223372036854775807 -9223372036854775808 4294967296
;;               9223372036854775808N -9223372036854775809N
;;               18446744073709551616N
;;               340282366920938463463374607431768211457N
;;               -340282366920938463463374607431768211457N])
(ns bigint-hash-test)

(def jvm-oracle
  [["0" 0] ["1" 1392991556] ["5" 1740791543] ["-1" 1651860712] ["7" -137604029]
   ["255" -52753993] ["9007199254740991" -1247966309]
   ["-9007199254740991" -19448363] ["9223372036854775807" -2106506049]
   ["-9223372036854775808" 1366273829] ["4294967296" 987256456]
   ["9223372036854775808" -2147483648] ["-9223372036854775809" 2147483647]
   ["18446744073709551616" 961]
   ["340282366920938463463374607431768211457" 923522]
   ["-340282366920938463463374607431768211457" -923522]])

(defn big [n] (js/BigInt n))

(defn keys-of [n] (mapv big (range n)))

(defn set-case [n]
  (let [ks (keys-of n)
        s (set ks)
        s2 (into #{} (concat ks ks))]
    [(count s) (count s2) (= s s2)
     (every? #(contains? s %) ks)
     (every? #(contains? s (big (str %))) ks)
     (contains? s (big n))
     (contains? s n)]))

(defn map-case [n]
  (let [ks (keys-of n)
        m (zipmap ks (range n))
        m2 (into {} (map (fn [k] [(big (str k)) (js/Number k)]) ks))]
    [(count m) (= m m2)
     (every? #(= (js/Number %) (get m (big (str %)))) ks)
     (get m (big n) :absent)]))

(def cases
  (concat
   [["(hash 5n) no longer throws and is an int"
     (fn [] (let [h (hash (big 5))] [(number? h) (= h (bit-or h 0))])) [true true]]
    ["equal BigInts from different sources hash equal"
     (fn [] (let [a (big 5) b (big "5") c (js/BigInt.asIntN 64 (big 5))
                  e (- (big 10) (big 5))]
              [(apply = (map hash [a b c e])) (= a b c e)]))
     [true true]]
    ["a BigInt hashes as Clojure-on-the-JVM hashes the same integer"
     (fn [] (mapv (fn [[s _]] [s (hash (big s))]) jvm-oracle)) jvm-oracle]
    ["BigInt 5 and number 5 stay unequal (equality was not changed)"
     (fn [] [(= (big 5) 5) (= 5 (big 5)) (count (set (conj (keys-of 9) 5)))]) [false false 10]]
    ["boundary: eight BigInts (array set) and nine (hash set) answer the same shape"
     (fn [] [(set-case 8) (set-case 9)])
     [[8 8 true true true false false] [9 9 true true true false false]]]]
   (for [n [9 100 10000]]
     [(str "set of " n " BigInts: count, dedup, equality, membership, absence")
      (fn [] (set-case n)) [n n true true true false false]])
   (for [n [9 100 10000]]
     [(str "map of " n " BigInt keys: count, equality, lookup, absence")
      (fn [] (map-case n)) [n true true :absent]])
   [["BigInt inside a vector is hashable as a set element (policy grants)"
     (fn [] (let [s (set (map (fn [i] [:cap/call (big i)]) (range 32)))]
              [(count s) (contains? s [:cap/call (big "31")]) (contains? s [:cap/call 31])]))
     [32 true false]]
    ["the same set built by 9 and by 10,000 steps is equal and hashes equal"
     (fn [] (let [a (set (keys-of 9))
                  b (reduce disj (set (keys-of 10000)) (map big (range 9 10000)))]
              [(= a b) (= (hash a) (hash b))]))
     [true true]]]))

(def results
  (for [[label f want] cases]
    (let [got (try (f) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
