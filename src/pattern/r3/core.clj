(ns pattern.r3.core
  "This namespace bootstraps the core rule macro and some utility functions."
  (:require [pattern.match.core :refer [pattern-names compile-pattern]]
            pattern.matchers
            [genera.trampoline :refer [bounce?]]
            [pattern.substitute :refer [substitute]]
            [pattern.match.predicator :refer [*pattern-replace* apply-replacements]]
            [clojure.walk :as walk]
            [pattern.types :refer [->SuccessUnmodified ->Success ->SuccessEnv]]
            [pattern.r3.rule :refer [->Rule *post-processor* *identity-rule-post-processor*
                                     make-rule -rebuild-rule]])
  (:import (pattern.types Success SuccessEnv SuccessUnmodified)
           (clojure.lang IFn IObj IMeta)))

(defn raw-matches
  "A success continuation that just returns the match 'dictionary', which may be
  either a dictionary or a function that behaves the same way as calling a map.

  See also [[pattern.match.core/all-values]] [[value-dict]] [[symbol-dict]]"
  [match-procedure]
  (comp list identity))

;;;; rule-implementation

(defn success
  "Explicitly mark an object as successfully matched when returned from a rule.

  The rule will unwrap the data automatically.

  Allows rules to return user data directly without failing.

      (success false)  ;; Allows the rule to return false without failing.

  The arity-0 version tells the matcher to use the original input data, also
  discarding any changes made by patterns that may have recursively matched with
  the rule."
  ([] (->SuccessUnmodified))
  ([x] (->Success x))
  ([x env] (->SuccessEnv x env)))

(defn success:env
  "Success but only change the env."
  [env]
  (success (success) env))

(defn name-rule
  "Attach a rule name to the given object's metadata."
  [name rule]
  (vary-meta rule assoc-in [:rule :name] name))

(defonce pure-pattern (atom identity))
(defonce spliced (atom identity))
(defonce qsub* (atom identity))
(defonce rule-src (atom identity))
(defonce scheme-style (atom identity))

(defmacro sub
  "Use the version in the pattern.r3.rewrite namespace."
  [form]
  (if @qsub*
    (@qsub* form)
    nil))

(defn pattern-args
  "Return the correctly ordered list of variable names in the pattern for use in
  defining a function that may be called with the result of calling all-values
  on a match result.

  This function uses pure-pattern to remove all data from the pattern to prevent
  arg name generation.

  This is so complex is because this function is designed to be called during
  macro expansion. It must eliminate any non-pattern expressions because they
  could look like matchers and be included in the list of matcher names, which
  would cause an arity error when the matcher is called. The identity function
  was chosen arbitrarily to replace all expressions because it could appear in the
  (? x identity) position, so needs to resolve as a function when the var is
  resolved."
  [pattern]
  (let [tidied (if (and (seqable? pattern) (= 'quote (first pattern)))
                 (second pattern)
                 (let [pp (@pure-pattern pattern)]
                   (if (= 'identity pp)
                     pattern
                     pp)))]
    (with-redefs [unquote (constantly identity)
                  unquote-splicing (constantly [identity])]
      (pattern-names tidied))))

(defn- extract-args [matches args]
  (when (seq args)
    (mapcat (fn [arg]
              [arg `(get (~matches '~arg) :value)])
            args)))

(defn- extract-env-args [env-args]
  (when (seq env-args)
    (doall
      (mapcat (fn [arg]
                [(symbol (name arg)) `(~'%env ~(keyword arg))])
        env-args))))

(defn- may-call-success0? [body]
  (boolean (some #{'(success) 'success:env
                   `(success) `success:env}
             (tree-seq list? seq body))))

(defn rule-fn-args [args env-args]
  (vec (concat ['%env] (take-nth 2 (extract-env-args env-args)) args)))

(defmacro rule-fn-body
  ([args env-args handler-body]
   `(rule-fn-body nil ~args ~env-args ~handler-body))
  ([name args env-args handler-body]
   (let [matches (gensym 'matches)
         fn-args (rule-fn-args args env-args)
         name* (if name
                 (symbol (str "rule-" name))
                 'rulebody)]
     `(let [handler# (fn ~name* [~fn-args] ~handler-body)]
        ['~fn-args
         handler#
         (fn ~'rule-dispatch [~'%env ~matches]
           (let [~@(extract-env-args env-args)
                 ~@(extract-args matches args)]
             (handler# ~fn-args)))]))))

(defmacro rule
  "Create a single rule. There are 2 arities, both with unique behavior.

  Arity 1: [pattern] -> identity rule (see below)
  Arity 2: [pattern body] -> simple replacement rule
  Arity 3: [name pattern body] -> named simple replacement rule

  If the `body` of arity 2 is nil/false the rule fails the same as if it had not
  matched at all. If the matcher can backtrack and make another match, it may
  attempt tho body/dict expression multiple times.  Once the expression returns
  a valid replacement value or map, the rule will have matched, the replacement
  will be made, and no further backtracking will happen.

  All pattern variables are bound with the match data in the handler body.
  For instance an arity 2 rule binding ?a and ?b that returns the sum of those
  matches:

      (rule '(?a [?b]) (+ a b))

  The same rule, named:

      (rule add-a-to-b0 '(?a [?b]) (+ a b))

  Rules may have unquote and spliced unquote in their definitions even if they are
  defined as normal quoted lists. The functionality is provided by a ruleset in
  pattern.r3.rewrite/spliced. It allows the following, but note that splices in rule
  definitions only happen at *compile time*:

      (rule '[(? a ~my-pred) ~@my-seq-of-things]
            {:matched a})

  A rule with no handler will act as an identity rule, and will always match if
  the pattern matches.  This may be useful within rule lists or for other higher
  level rule combinators that make use of the rule metadata in the match
  expression. For example:

      (rule '?->expression)

  Or the same rule, named must use the 3 arity:

      (rule expression '?->expression (success))

  Side note, `(rule name '?->e)` seems nice, and I tried it but sometimes one may
  want `(rule symbol :found)`. It's a recipe for weird breakage so I removed it.

  Environment args:

  A rule can bind arguments from its environment by attaching metadata to the input rule as follows:

      (rule set-var ^{:env-args [var-name]} '?form (sub (set ?var-name ?form)))

  Rules can also be called with succeed and fail callbacks

      (my-rule data env succeed fail)
  "
  ([pattern]
   (let [args (pattern-args pattern)
         matches (gensym 'matches)
         p (@spliced (@scheme-style pattern))]
     `(let [p# ~p
            [fn-args# handler# dispatcher#]
            (rule-fn-body ~args ~(:env-args (meta pattern)) (sub ~(second pattern)))]
        (make-rule p#
          dispatcher#
          raw-matches
          *post-processor*
          {:src '(sub ~(second pattern))
           :pattern-meta '~(meta pattern)
           :pattern-args '~(with-meta args nil)
           :handler-fn-args fn-args#
           :handler-fn handler#}))))
  ([pattern handler-body]
   (let [args (pattern-args pattern)]
     `(let [p# ~(@spliced (@scheme-style pattern))
            [fn-args# handler# dispatcher#]
            (rule-fn-body ~args ~(:env-args (meta pattern)) ~handler-body)]
        (make-rule p#
          dispatcher#
          raw-matches
          *post-processor*
          {:may-call-success0? ~(may-call-success0? handler-body)
           :src '~handler-body
           :pattern-args '~(with-meta args nil)
           :pattern-meta '~(meta pattern)
           :handler-fn-args fn-args#
           :handler-fn handler#}))))
  ([name pattern handler-body]
   (let [fname (if (symbol? name) name (gensym '-))
         name (if (symbol? name) (list 'quote name) name)
         args (pattern-args pattern)]
     `(name-rule ~name
        (let [p# ~(@spliced (@scheme-style pattern))
              [fn-args# handler# dispatcher#]
              (rule-fn-body ~fname ~args ~(:env-args (meta pattern)) ~handler-body)]
          (make-rule p#
            dispatcher#
            raw-matches
            *post-processor*
            {:may-call-success0? ~(may-call-success0? handler-body)
             :src '~handler-body
             :pattern-args '~(with-meta args nil)
             :pattern-meta '~(meta pattern)
             :handler-fn-args fn-args#
             :handler-fn handler#}))))))

(defmacro rule-fn-rebuild-body
  [name args env-args handler-body injection-names]
  (let [matches (gensym 'matches)
        fn-args (rule-fn-args args env-args)
        name* (if name
                (symbol (str "rebuild-" name))
                'rebuildbody)]
    ;; Handler can get a huge number of function args, so pass them as a destructuring vector.
    ;; TODO: if there is too much overhead, only do that if > 20 args
    `(let [handler# (volatile! nil)
           ->handler#
           (fn [~injection-names]
             (vreset! handler#
               (fn ~name* [~fn-args] ~handler-body)))]
       ['~fn-args
        ->handler#
        (fn ~'rule-dispatch [~'%env ~matches]
          (let [~@(extract-env-args env-args)
                ~@(extract-args matches args)]
            ((deref handler#) ~fn-args)))])))


(defn rebuild-body [args env-args ->handler injection-names injection-data]
  (when ->handler
    ;; ignore the fn-args and fn-body, just return the dispatcher
    (let [macro (list `rule-fn-rebuild-body nil args env-args ->handler injection-names)]
      (try
        (let [[args ->handler dispatch]
              (eval macro)]
          (->handler injection-data)
          dispatch)
        (catch Exception e
          (println "Error evaluating macro:")
          (prn macro)
          (try
            (clojure.pprint/pprint (macroexpand macro))
            (catch Exception x
              (println "Error macroexpanding:")
              (clojure.pprint/pprint x)))
          (throw e))))))

(defmacro rebuild-rule
  "Update either the pattern or the handler body (or both) of the given rule.

  Both the pattern and the handler-body must be quoted (unlike in [[rule]], where
  the handler-body is not quoted. This is to allow programmatic manipulation
  of the existing handler body, or otherwise generating it. The current version
  of both is present in the rule metadata.

  When rebuilding a rule using eval, anything that may contain local state must
  be injected. In the handler function, refer to data that will be injected with
  normal symbols. Provide those symbols as a vector of injection-names. The
  corresponding data to be injected should be in the same order in
  injection-data."
  [rule pattern handler-body handler-injection-names handler-injection-data]
  `(let [raw-pattern# ~pattern
         args# (when ~(boolean pattern) (pattern-args raw-pattern#))
         p# ~(when pattern
               (@spliced (@scheme-style pattern)))
         r# ~rule
         args# (or args# (get-in (meta r#) [:rule :pattern-args]))
         env-args# '~(:env-args (meta pattern))
         src# ~handler-body
         r# (-rebuild-rule r#
              (when ~(boolean pattern)
                (compile-pattern (apply-replacements p#
                                   (get-in (meta r#) [:rule :pattern.match.core/pattern-replace]))))
              (when ~(boolean handler-body) (rebuild-body args# env-args# src# (vec ~handler-injection-names) (vec ~handler-injection-data))))]
     (cond-> r#
       ~(boolean handler-body) (vary-meta assoc-in [:rule :src] src#)
       ~(boolean pattern) (vary-meta assoc-in [:rule :pattern] p#))))
