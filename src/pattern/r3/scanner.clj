(ns pattern.r3.scanner
  (:require
   [pattern.types :refer [spliceable-pattern recombine]]
   [pattern.r3.core :refer [success success:env rebuild-rule]]
   [pattern.r3.rule :refer [unwrap unwrap-env]]
   [pattern.r3.combinators :refer [rule-list iterated rule-zipper]]
   [clojure.zip :as zip]))

(defmulti scanner* (fn [opts the-rule] (get-in (meta the-rule) [:rule :rule-type])))

(defn scanner
  ([the-rule] (scanner {} the-rule))
  ([opts the-rule] (scanner* opts the-rule)))


(defn- make-rule
  ([opts r pattern handler]
   (make-rule opts r nil [pattern] [handler]))
  ([opts r markers patterns handlers]
   (when (seq patterns)
     (let [before (gensym 'before)
           segment (gensym 'segment)
           one? (= 1 (count patterns))
           after (gensym 'after)
           pattern (list
                     (symbol (str (if (:lazy opts) "??!" "??") before))
                     (list '?:as* segment
                       (if one?
                         (first patterns)
                         (list* '| (map (fn [m p] (list '?:as* m p)) markers patterns))))
                     (symbol (str "??" after)))
           handlers (if one?
                      (first handlers)
                      `(cond ~@(mapcat vector markers handlers)))]
       (->
         (rebuild-rule r
           pattern
           `(when (seq ~segment)
              (when-let [body# ~handlers]
                (let [env# (unwrap-env ~'%env body#)
                      body# (unwrap ::none body#)]
                  (if (= ::none body#)
                    (success:env env#)
                    (let [body# (if (sequential? body#) body# [body#])]
                      (success
                        (if (seq ~before)
                          (if (seq body#)
                            (into ~before (concat body# ~after))
                            (into ~before ~after))
                          (if (seq ~after)
                            (if (vector? body#)
                              (into body# ~after)
                              (vec (concat body# ~after)))
                            (vec body#)))
                        env#)))))))
         (vary-meta assoc-in [:rule :scanner] true))))))


(defmethod scanner* :pattern/rule [{:keys [iterate lazy] :or {iterate true} :as opts} the-rule]
  ;; single rule approach:
  ;;'[??before rule1-body ??after]
  (let [m (:rule (meta the-rule))
        pattern (spliceable-pattern (:match m))]
    (if pattern
      (cond-> (make-rule opts the-rule pattern (:src m))
        true (vary-meta assoc-in [:rule :scanner] true)
        iterate iterated)
      the-rule)))

 
(defmethod scanner* :pattern.r3.combinators/rule-list [{:keys [iterate] :or {iterate true} :as opts} the-rule]
  ;; rule-list approach
  ;; '[??before (| (?:as* rule1 rule1-body) (?:as* rule2 rule2-body) ... (?:as* rule-n rule-n-body)) ??after]
  (loop [[child & children] (zip/children (rule-zipper the-rule))
         r nil
         markers []
         patterns []
         handlers []
         rules []]
    (if child
      (let [m (:rule (meta child))]
        (if (= :pattern/rule (:rule-type m))
          (if-let [p (spliceable-pattern (:match m))]
            (recur children
              (or r child)
              (conj markers (gensym 'rule))
              (conj patterns p)
              (conj handlers (:src m))
              rules))
          (recur children nil [] [] []
            (-> rules
              (conj (make-rule opts r markers patterns handlers))
              (conj (scanner opts child))))))
      (cond-> (rule-list (remove nil? (conj rules (make-rule opts r markers patterns handlers))))
        iterate iterated))))

(defmethod scanner* :default [opts the-rule]
  ;; all other rules can be inverted:
  ;; (scanner (in-order rule1 rule2)) #_-> (in-order (scanner rule1) (scanner rule2))
  ;; (scanner (guard f rule)) #_-> (guard f (scanner rule))

  ;;      (scanner (rule-list rule0 rule1 (in-order rule2 rule3)))
  ;; #_-> (rule-list (rule rule0+rule1) (in-order (scanner rule2) (scanner rule3)))

  (loop [z (zip/down (rule-zipper the-rule))
         rules []]
    (if z
      (recur (zip/right z) (conj rules (scanner* opts (zip/node z))))
      (recombine the-rule rules))))
