;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.deps.alpha
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.tools.deps.alpha.providers :as providers])
  (:import
    [clojure.lang PersistentQueue]))

(defn- choose-provider
  [coord providers]
  (get providers (:type coord)))

(defn- expand-deps
  [deps default-deps override-deps providers verbose]
  (loop [q (into (PersistentQueue/EMPTY) (map vector deps)) ;; queue of dep paths
         tree {} ;; map of [lib coord] to child map of same, leaf values are nil
         seen #{}] ;; set of [lib coord]
    (if-let [path (peek q)] ;; path from root dep to dep being expanded
      (let [q' (pop q)
            [lib coord :as dep] (peek path)
            override-coord (get override-deps lib)
            use-coord (cond override-coord override-coord
                            coord coord
                            :else (get default-deps lib))]
        (if (seen dep)
          (recur q' tree seen)
          (let [children (providers/expand-dep lib use-coord (choose-provider use-coord providers))
                child-paths (map #(conj path %) children)]
            (when verbose (println "Expanding" lib use-coord))
            (recur (into q' child-paths) (update-in tree path merge nil) (conj seen dep)))))
      (do
        (when verbose (println) (println "Expanded tree:") (pprint tree))
        tree))))

(defn- cut-exclusions
  ([deps-tree]
    (cut-exclusions deps-tree #{}))
  ([deps-tree parent-exclusions]
   (let [node (->> deps-tree
                (remove (fn [[[lib coord] _children]] (contains? parent-exclusions lib)))
                (map (fn [[[lib {:keys [exclusions] :as coord}] children]]
                       [[lib (dissoc coord :exclusions)]
                        (cut-exclusions children (into parent-exclusions exclusions))])))]
     (if (empty? node)
       nil
       (into {} node)))))

(defn- choose-coord
  [coord1 coord2 top-coord]
  (cond
    top-coord top-coord
    (and coord1 (not coord2)) coord1
    (and coord2 (not coord1)) coord2
    :else (if (pos? (providers/compare-versions coord1 coord2)) coord1 coord2)))

(defn- resolve-versions
  [deps-tree providers verbose]
  (let [top-deps (into {} (keys deps-tree))]
    (loop [q (into (PersistentQueue/EMPTY) deps-tree)
           lib-map {}]
      (if-let [[[lib coord] child-deps] (peek q)]
        (recur
          (into (pop q) (map #(update-in % [0 1 :dependents] (fnil conj []) lib) child-deps))
          (assoc lib-map lib (choose-coord (lib-map lib) coord (get top-deps lib))))
        (do
          (when verbose
            (println)
            (println "Resolved libs:")
            (pprint lib-map))
          lib-map)))))

(defn- download-deps
  [lib-map providers]
  (reduce (fn [ret [lib coord]]
            (assoc ret lib (providers/download-dep lib coord (choose-provider coord providers))))
    {} lib-map))

(defn resolve-deps
  [{:keys [deps resolve-args providers] :as deps-map} args-map]
  (let [{:keys [extra-deps default-deps override-deps verbose]} (merge resolve-args args-map)
        deps (merge (:deps deps-map) extra-deps)]

    (when verbose
      (println "Initial deps to expand:")
      (pprint deps))

    (-> deps
      (expand-deps default-deps override-deps providers verbose)
      (cut-exclusions)
      (resolve-versions providers verbose)
      (download-deps providers))))

(defn make-classpath
  [lib-map lib-paths]
  (str/join ":" (map :path (vals (merge-with (fn [coord path] (assoc coord :path path)) lib-map lib-paths)))))

(comment
  (require
    '[clojure.tools.deps.alpha.providers.maven :as mvn]
    '[clojure.tools.deps.alpha.providers.file])

  (expand-deps {'org.clojure/clojure {:type :mvn :version "1.9.0-alpha17"}} nil nil
    {:mvn {:repos mvn/standard-repos}} true)

  (expand-deps {'org.clojure/clojure {:type :mvn :version "1.9.0-alpha17"}
                'org.clojure/core.memoize {:type :mvn :version "0.5.8"}} nil nil
    {:mvn {:repos mvn/standard-repos}} true)

  (clojure.pprint/pprint
    (resolve-deps {:deps {'org.clojure/clojure {:type :mvn :version "1.8.0"}
                          'org.clojure/core.memoize {:type :mvn :version "0.5.8"}}
                    :providers {:mvn {:repos mvn/standard-repos}}} nil))

  (make-classpath
    (resolve-deps {:deps {'org.clojure/clojure {:type :mvn :version "1.9.0-alpha17"}
                          'org.clojure/core.memoize {:type :mvn :version "0.5.8"}}
                    :providers {:mvn {:repos mvn/standard-repos}}} nil) nil)

  (clojure.pprint/pprint
    (resolve-deps {:deps {'org.clojure/tools.analyzer.jvm {:type :mvn :version "0.6.9"}}
                   :providers {:mvn {:repos mvn/standard-repos}}} nil))

  (clojure.pprint/pprint
    (resolve-deps {:deps {'cheshire {:type :mvn :version "5.7.0"}}
                   :providers {:mvn {:repos mvn/standard-repos}}} nil))

  ;; top deps win
  (clojure.pprint/pprint
    (resolve-deps {:deps {'org.clojure/clojure {:type :mvn :version "1.2.0"}
                          'cheshire/cheshire {:type :mvn :version "5.8.0"}}
                   :providers {:mvn {:repos mvn/standard-repos}}} nil))

  ;; override to local
  (make-classpath
    (resolve-deps
      {:deps {'org.clojure/core.memoize {:type :mvn :version "0.5.8"}}
       :providers {:mvn {:repos mvn/standard-repos}}}
      {:override-deps {'org.clojure/clojure {:type :file :path "/Users/alex/code/clojure/classes"}}
        :verbose true}) nil)

  (make-classpath
    (resolve-deps
      {:deps {'org.clojure/tools.deps.alpha {:type :mvn :version "0.1.40"}
              'org.clojure/clojure {:type :mvn :version "1.9.0-alpha17"}}
       :providers {:mvn {:repos mvn/standard-repos}}} nil) nil)

  (make-classpath
    (resolve-deps
      {:deps {'cheshire {:type :mvn}} ;; omit version - should default to latest
       :providers {:mvn {:repos mvn/standard-repos}}}
      {:verbose true}) nil)

  (make-classpath
    (resolve-deps
      {:deps {'org.clojure/clojure {:type :mvn :version "1.8.0"}}
       :providers {:mvn {:repos mvn/standard-repos}}}
      {:verbose true})
    {'org.clojure/clojure "/Users/alex/code/clojure/target/classes"})

  ;; err case
  (resolve-deps {:deps {'bogus {:type :mvn :version "1.2.3"}}
                 :providers {:mvn {:repos mvn/standard-repos}}} nil)
  )
