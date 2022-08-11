(ns pattern.util-test
  (:require [clojure.zip :as zip]
            [pattern.util :refer :all]
            [clojure.test :refer [deftest testing is are]]))

(deftest test-diff
  (is (= [[:order 1 3 0 2] [:r 2 1]]
        (diff '(a b (c d) (e f)) '(b (e f) (c x) a))))

  (let [d (diff '(a b (c d) (e f)) '(z z z z b (e f) (c x) a))]
    (is (= {:+ [0 1 2 3 6 7], :- [0 2]}
          (diff-indices d)))))


(defn test-walk [a b]
  (walk-diff a b
    (fn same [z orig]
      (cond
        (sequential? (zip/node z))
        z
        (int? (zip/node z))
        (zip/edit z -)
        :else
        (zip/edit z str)))
    (fn changed [type z orig]
      (if (sequential? (zip/node z))
        z
        (case type
          :- (zip/insert-left z {:- orig})
          :+ (zip/edit z (constantly {:+ (zip/node z)}))
          :r (zip/edit z (constantly {:r [orig (zip/node z)]})))))))

(deftest walking-recursive
  (is (= [":a" [-1 -3] -5]
        (test-walk
          [:a [1 3] 5]
          [:a [1 3] 5])))

  (is (= [":a" [-1 {:r [3 4]}] -5]
        (test-walk
          [:a [1 3] 5]
          [:a [1 4] 5])))

  (is (= [":a" [-1 {:r [3 4]}] -5]
        (test-walk
          [:a [1 3] 5]
          [:a [1 4] 5]))))

(deftest walk-through-map
  (is (= {":a" -1 ":b" -2}
        (test-walk
          {:a 1 :b 2}
          {:a 1 :b 2})))

  (is (= {":a" [-1 -2] ":b" -2}
        (test-walk
          {:a '(1 2) :b 2}
          {:a '(1 2) :b 2})))

  (is (= {":a" '({:- 1} -2 {:+ 1}) ":b" -2}
        (test-walk
          {:a '(1 2) :b 2}
          {:a '(2 1) :b 2}))))

(deftest walking-diffs
  ;; The test-walk fn makes these transformations:
  ;; values unchanged in the diff get negated (or stringified if not int)
  ;; replacements become {:r [old new])
  ;; removals become {:- removed}
  ;; additions become {:+ added}
  (is (= [":a" -1 -3 -5]
        (test-walk
          [:a 1 3 5]
          [:a 1 3 5])))

  (is (= [{:r [:a :b]} -1 -3 -5]
        (test-walk
          [:a 1 3 5]
          [:b 1 3 5])))

  (is (= [":a" {:r [0 1]} -3 -5]
        (test-walk
          [:a 0 3 5]
          [:a 1 3 5])))

  (is (= [":a" -1 -3 {:r [4 5]}]
        (test-walk
          [:a 1 3 4]
          [:a 1 3 5])))

  (is (= [{:+ :x} {:+ 2} {:+ 2}
          {:- :a} {:- 1}
          -3
          {:+ 4}
          -5
          {:+ 6}]
        (test-walk
          [:a   1 3   5]
          [:x 2 2 3 4 5 6])))

  (is (= [{:- :x} {:- 2} {:- 2}
          {:+ :a} {:+ 1}
          -3
          {:- 4}
          -5]
        ;; NOTE: removals at the end are not captured because the result zipper has lost the context.
        ;; I don't need this functionality so not going to bother coming up with a solution.
        (test-walk
          [:x 2 2 3 4 5 6]
          [:a   1 3   5]))))

(deftest merge-meta
  (is (= '{a (b d f)}
        (deep-merge-meta2
          '{a ^:hi (^:x b ^:y c ^:z f)}
          '{a (b d f)})))

  (is (:x (meta
            (find-in
              '[a 0]
              (deep-merge-meta2
                '{a ^:hi (^:x b ^:y c ^:z f)}
                '{a (b d f)})))))

  (is (nil? (meta
              (find-in
                '[a 1]
                (deep-merge-meta2
                  '{a ^:hi (^:x b ^:y c ^:z f)}
                  '{a (b d f)})))))

  (is (:z (meta
            (find-in
              '[a 2]
              (deep-merge-meta2
                '{a ^:hi (^:x b ^:y c ^:z f)}
                '{a (b d f)})))))

  (is (:hi (meta
             ((deep-merge-meta2
                '{a ^:hi (^:x b ^:y c ^:z f)}
                '{a (b d f)})
              'a)))))
