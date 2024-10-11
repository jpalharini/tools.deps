(ns clojure.tools.deps.extensions.test-deps
  (:require [clojure.test :refer :all]
            [clojure.tools.deps.extensions :as ext]
            [clojure.tools.deps.extensions.deps :as d]))

(def test-deps-map {:paths   ["src"]
                    :deps    {'org.clojure/clojure {:mvn/version "some-version"}}
                    :aliases {:prod {:extra-paths ["target/classes"]
                                     :extra-deps  {'some/dep {:mvn/version "some-version"}}}}})

(deftest drops-extra-args
  (testing "when aliases specify args other than :extra-deps, these are dropped"
    (is (= (#'d/aliases-args test-deps-map [:prod])
           {:extra-deps {'some/dep {:mvn/version "some-version"}}}))))

(deftest coord-deps
  (testing "returns a seq of root deps when no alias is provided"
    (with-redefs-fn {#'d/deps-map (constantly test-deps-map)}
      #(are [result
             coord] (= result
                       (ext/coord-deps nil coord :deps nil))

                    '([org.clojure/clojure {:mvn/version "some-version"}])
                    {:deps/root "some-dir"}

                    '([org.clojure/clojure {:mvn/version "some-version"}]
                      [some/dep {:mvn/version "some-version"}])
                    {:deps/root "some-dir" :deps/aliases [:prod]}))))