;; Regression for the JVM-compat scaffolding (src/nbb/jvm.cljs): the
;; java.lang exception classes, clojure.lang.ExceptionInfo / cljs.core/
;; ExceptionInfo, Throwable's methods, ex-cause on any Throwable.
;;   node cli.js test-scripts/jvm_exceptions_test.cljs
;; exit 0 and "OK n/n" = present; exit 1 names each case that failed.
;; Measured on the build before it (8ea820f): "THREW Unable to resolve symbol:
;; Exception" / "...: clojure.lang.ExceptionInfo" / "...: cljs.core/
;; ExceptionInfo" / "Unable to resolve classname: ..." -- together the first
;; failure of 11 of 76 red JVM-era suites sampled on 2026-09-24.
(ns jvm-exceptions-test
  (:require [clojure.test]))

(def cases
  [;; catch Exception catches every host error, as the JVM's catches every
   ;; RuntimeException (a nil call is an NPE there, a TypeError here)
   ["catch Exception: ex-info" "(try (throw (ex-info \"m\" {})) (catch Exception _ :caught))" :caught]
   ["catch Exception: host TypeError" "(try (.foo nil) (catch Exception _ :caught))" :caught]
   ["catch Exception: new Exception" "(try (throw (Exception. \"m\")) (catch Exception e (ex-message e)))" "m"]
   ["catch Throwable: host Error" "(try (throw (js/Error. \"m\")) (catch Throwable _ :caught))" :caught]
   ["catch RuntimeException: ex-info (ExceptionInfo extends RuntimeException)"
    "(try (throw (ex-info \"m\" {})) (catch RuntimeException _ :caught))" :caught]
   ;; boundaries: the hierarchy is the JVM's, not "everything"
   ["RuntimeException does NOT catch a checked Exception"
    "(try (throw (Exception. \"m\")) (catch RuntimeException _ :wrong) (catch Exception _ :right))" :right]
   ["IllegalArgumentException does NOT catch IllegalStateException"
    "(try (throw (IllegalStateException. \"m\")) (catch IllegalArgumentException _ :wrong) (catch IllegalStateException _ :right))" :right]
   ["IllegalArgumentException does NOT catch ex-info"
    "(try (throw (ex-info \"m\" {})) (catch IllegalArgumentException _ :wrong) (catch Exception _ :right))" :right]
   ["Exception does NOT catch a thrown non-Error value (the JVM cannot throw one)"
    "(try (try (throw \"s\") (catch Exception _ :wrong)) (catch :default _ :right))" :right]
   ["NumberFormatException is an IllegalArgumentException"
    "(instance? IllegalArgumentException (NumberFormatException. \"x\"))" true]
   ["java.lang.* fully qualified names" "(try (throw (java.lang.IllegalStateException. \"q\")) (catch java.lang.RuntimeException e (.getMessage e)))" "q"]
   ["thrown? with a JVM class" "(instance? IllegalArgumentException (cljs.test/is (thrown? IllegalArgumentException (throw (IllegalArgumentException. \"x\")))))" true]
   ;; ExceptionInfo under its JVM and cljs names
   ["catch clojure.lang.ExceptionInfo" "(try (throw (ex-info \"m\" {:a 1})) (catch clojure.lang.ExceptionInfo e (ex-data e)))" {:a 1}]
   ["catch cljs.core/ExceptionInfo" "(try (throw (ex-info \"m\" {:a 1})) (catch cljs.core/ExceptionInfo e (ex-data e)))" {:a 1}]
   ["instance? clojure.lang.ExceptionInfo" "(instance? clojure.lang.ExceptionInfo (ex-info \"m\" {}))" true]
   ["clojure.lang.ExceptionInfo does NOT catch a plain Exception"
    "(try (throw (Exception. \"m\")) (catch clojure.lang.ExceptionInfo _ :wrong) (catch Exception _ :right))" :right]
   ;; Throwable's methods and printing
   [".getMessage on ex-info" "(.getMessage (ex-info \"m\" {}))" "m"]
   [".getMessage on a host error" "(.getMessage (js/Error. \"h\"))" "h"]
   [".getData on ex-info" "(.getData (ex-info \"m\" {:k 2}))" {:k 2}]
   ["no-arg constructor: message is nil" "(.getMessage (Exception.))" nil]
   ["toString is the JVM's" "(str (IllegalArgumentException. \"bad\"))" "java.lang.IllegalArgumentException: bad"]
   ["toString without a message is the class name" "(str (RuntimeException.))" "java.lang.RuntimeException"]
   ["(Exception. msg cause): getCause" "(let [c (Exception. \"c\")] (identical? c (.getCause (Exception. \"m\" c))))" true]
   ["(Exception. cause): message is cause.toString()" "(.getMessage (RuntimeException. (IllegalStateException. \"c\")))" "java.lang.IllegalStateException: c"]
   ["ex-cause on a non-ExceptionInfo Throwable (JVM ex-cause)" "(let [c (Exception. \"c\")] (identical? c (ex-cause (Exception. \"m\" c))))" true]
   ["ex-cause on ex-info still works" "(ex-message (ex-cause (ex-info \"m\" {} (ex-info \"inner\" {}))))" "inner"]])

(def results
  (for [[label src want] cases]
    (let [got (try (load-string src) (catch :default e (str "THREW " (ex-message e))))]
      {:label label :ok (= want got) :got got})))

(doseq [{:keys [label ok got]} results :when (not ok)]
  (println "FAIL" label "=>" (pr-str got)))

(let [n (count (filter :ok results))]
  (println (str (if (= n (count cases)) "OK " "FAILED ") n "/" (count cases)))
  (when (< n (count cases)) (js/process.exit 1)))
