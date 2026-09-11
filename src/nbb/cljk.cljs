(ns nbb.cljk
  "Opt-in canonical source resolution; never rewrites source bytes."
  (:require ["node:fs" :as fs]
            ["node:path" :as path]
            [clojure.string :as str]
            [edamame.core :as reader]))

(defn refuse [reason data]
  (throw (ex-info (str "cljk: " (name reason) " " (pr-str data)) (assoc data :reason reason))))

(defn inside? [root file]
  (let [rel (path/relative root file)]
    (and (not (path/isAbsolute rel))
         (not= ".." rel) (not (str/starts-with? rel "../")))))

(defn registry [roots]
  (when-not (and (vector? roots) (every? string? roots))
    (refuse :invalid-manifest {:detail :roots-must-be-vector}))
  (mapv
   (fn [root]
     (when-not (path/isAbsolute root)
       (refuse :invalid-manifest {:root root :detail :absolute-root-required}))
     (let [root (fs/realpathSync root)
           manifest (path/join root "cljk-origin.edn")
           _ (when-not (inside? root (fs/realpathSync manifest))
               (refuse :invalid-manifest {:path manifest}))
           forms (try (reader/parse-string-all (fs/readFileSync manifest "utf8"))
                      (catch :default _ (refuse :invalid-manifest {:path manifest})))
           m (first forms)]
       (when-not (and (= 1 (count forms)) (= :kotoba.cljk-origin/v1 (:format m))
                      (map? (:origins m)))
         (refuse :invalid-manifest {:path manifest}))
       {:root root
        :entries
        (mapv (fn [[rel origin]]
                (when-not (and (string? rel) (str/ends-with? rel ".cljk")
                               (not (path/isAbsolute rel))
                               (inside? root (path/resolve root rel))
                               (contains? #{".cljs" ".cljc" ".clj"} origin))
                  (refuse :invalid-origin {:path rel :origin origin}))
                (let [file (path/resolve root rel)
                      stem (subs file 0 (- (count file) 5))
                      suffix (re-find #"\.(cljs|cljc|clj)$" stem)]
                  (when (and suffix (not= (first suffix) origin))
                    (refuse :invalid-origin {:path rel :origin origin}))
                  (when-not (and (fs/existsSync file) (inside? root (fs/realpathSync file)))
                    (refuse :invalid-manifest {:path file :detail :missing-or-escaping-source}))
                  {:path file :origin origin
                   :stem (if suffix (subs stem 0 (- (count stem) (count origin))) stem)}))
              (:origins m))})) roots))

(defn resolve-source [roots dirs munged]
  ;; No cache: a reload must see the current manifest and source identity.
  (let [repos (registry roots)
        stem (str/replace (str munged) "." "/")]
    (some
     (fn [dir]
       (let [dir (if (fs/existsSync dir) (fs/realpathSync dir) (path/resolve dir))
             base (path/resolve dir stem)
             owners (filter #(inside? (:root %) dir) repos)
             _ (when (> (count owners) 1) (refuse :ambiguous-source {:path dir}))
             entries (filter #(= base (:stem %)) (:entries (first owners)))
             renamed (filter fs/existsSync
                             (map #(str base %) [".cljk" ".cljs.cljk" ".cljc.cljk" ".clj.cljk"]))
             _ (doseq [f renamed]
                 (when-not (some #(= f (:path %)) entries)
                   (refuse :invalid-origin {:path f})))
             legacy (keep (fn [ext] (let [p (str base ext)]
                                      (when (fs/existsSync p) {:path p :origin ext})))
                          [".cljs" ".cljc" ".clj"])
             candidates (concat entries legacy)]
         (if (seq entries)
           (do
             (doseq [[_ group] (group-by :origin candidates)]
               (when (> (count group) 1)
                 (refuse :ambiguous-source {:paths (mapv :path group)})))
             (or (some #(when (= ".cljs" (:origin %)) (:path %)) candidates)
                 (some #(when (= ".cljc" (:origin %)) (:path %)) candidates)
                 (refuse :target-incompatible {:namespace munged})))
           (:path (first legacy))))) dirs)))

(defn validate-entry! [roots file]
  (when (str/ends-with? file ".cljk")
    (let [file (fs/realpathSync file)
          entries (filter #(= file (:path %)) (mapcat :entries (registry roots)))]
      (when-not (= 1 (count entries)) (refuse :invalid-origin {:path file}))
      (when (= ".clj" (:origin (first entries)))
        (refuse :target-incompatible {:path file})))))
