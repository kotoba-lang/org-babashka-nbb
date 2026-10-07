(ns nbb.jvm.lang
  "JVM-compatibility SCAFFOLDING, round 3: Class/forName and clojure.core/class
  (see nbb.jvm for why this exists; it is not a target for new code).

  The JVM-era idiom this answers is the byte[] test
  `(= (Class/forName \"[B\") (class x))` / `(instance? (Class/forName \"[B\") x)`
  -- kotoba-lang/amu's compiler cache said it at load time, so every
  repository pinning an amu from before its portable branch stopped there.
  byte[] is a signed Int8Array on this engine (nbb.jvm.bytes), so \"[B\" names
  js/Int8Array and (class (byte-array 1)) answers js/Int8Array: both idioms
  hold as on the JVM.

  Class/forName of a fully qualified name answers the class this engine
  registered under that name (java.lang.String, java.io.File, ...) and
  throws java.lang.ClassNotFoundException with the name, as the JVM does,
  for anything else -- including bare names (\"String\").

  class: nil -> nil, a string -> String, a boolean -> Boolean, a byte[] ->
  the \"[B\" class, anything else -> its constructor (records and types as
  sci's `type` answers them).
  Deliberately NOT answered (throws naming it, instead of a silently
  different class): (class 5) / (class 5.0) -- a JS number cannot say Long
  or Double; Class/forName of the other array classes (\"[C\",
  \"[Ljava.lang.Object;\" ...) -- char[] and Object[] are both JS arrays here.
  Documented deviations: (class \\a) is String (a char is a one-character
  string on this engine); a class prints as its JS constructor, not
  \"class java.lang.String\"; (.getName c) is not available."
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [nbb.jvm :as jvm]
            [nbb.jvm.bytes :as jb]))

(def ClassNotFoundException (jvm/defclass "java.lang.ClassNotFoundException" jvm/Exception))

(defn- entry-class [v] (if (map? v) (:class v) v))

(defn class-for-name
  "Class/forName over `classes` (the engine's sci :classes map)."
  [classes n & _]
  (when (nil? n) (throw (js/TypeError. "Cannot invoke \"String.length()\" because \"className\" is null")))
  (let [n (str n)]
    (cond (= "[B" n) js/Int8Array
          (str/starts-with? n "[")
          (throw (jvm/UnsupportedOperationException.
                  (str "Class/forName \"" n "\": only byte[] (\"[B\") has its own class on this engine; see nbb.jvm.lang")))
          (and (str/includes? n ".") (contains? classes (symbol n)))
          (entry-class (get classes (symbol n)))
          :else (throw (ClassNotFoundException. n)))))

(defn class-object [classes]
  (let [c (js* "(function(){ var C = function Class(){}; Object.defineProperty(C, Symbol.hasInstance, {value: function(x){ return typeof x === 'function'; }}); return C; })()")]
    (gobj/set c "forName" (fn [n & more] (apply class-for-name classes n more)))
    c))

(defn class-fn
  "clojure.core/class; `sci-type` is sci's `type` (it knows sci records)."
  [sci-type]
  (fn [x]
    (cond (nil? x) nil
          (string? x) jb/JString
          (boolean? x) jvm/Boolean
          (number? x) (throw (jvm/UnsupportedOperationException.
                              (str "(class " x "): a JS number cannot say java.lang.Long or java.lang.Double; deliberately not answered on this engine, see nbb.jvm.lang")))
          (instance? js/Int8Array x) js/Int8Array
          :else (sci-type x))))

(defn classes []
  {'ClassNotFoundException ClassNotFoundException
   'java.lang.ClassNotFoundException ClassNotFoundException})
