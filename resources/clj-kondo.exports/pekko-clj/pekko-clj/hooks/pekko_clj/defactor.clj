(ns hooks.pekko-clj.defactor
  "clj-kondo hooks for the `defactor` and `defactor-persistent` macros.

   Both macros build an actor definition out of unquoted clauses — (init [args] ...),
   (handle pattern ...), (command pattern ...) and friends. clj-kondo cannot expand
   them, so without these hooks every clause head, every core.match pattern binding
   and the implicit `state`/`this` anaphors are reported as unresolved symbols.

   The hooks rewrite each macro call into an equivalent `let`/`fn` skeleton that
   clj-kondo *can* analyse: the var gets defined, clause bodies get linted, and the
   symbols a clause introduces are visible to the body that uses them."
  (:require [clj-kondo.hooks-api :as api]))

(defn- simple-symbol-nodes
  "Every token node under `node` (inclusive) holding an unqualified symbol.

   Used to harvest the locals a core.match pattern introduces. Patterns range from
   a bare symbol (`msg`) through vectors (`[:add n]`) to maps (`{:id id}`); the
   non-binding parts are keywords, strings and numbers, so collecting the simple
   symbols is enough. Qualified symbols (class names, `:guard` predicates) are
   skipped — they resolve on their own."
  [node]
  (let [sexpr (api/sexpr node)]
    (if (and (symbol? sexpr) (nil? (namespace sexpr)))
      [node]
      (mapcat simple-symbol-nodes (:children node)))))

(defn- distinct-bindings
  "The symbol nodes in `nodes`, minus `_` and minus repeats.

   A core.match pattern may mention the same symbol twice, and `_` is a
   placeholder nothing refers to; both would be errors in the parameter vector
   this feeds."
  [nodes]
  (let [seen (volatile! #{})]
    (into []
          (filter (fn [n]
                    (let [s (api/sexpr n)]
                      (when (and (not= '_ s) (not (contains? @seen s)))
                        (vswap! seen conj s)
                        true))))
          nodes)))

(defn- scoped-body
  "Wrap `body-nodes` in an immediately-shaped `fn` whose parameters are
   `binding-nodes` plus each of `anaphors`, so the body can reference them.

   Parameters rather than `let` bindings, because a `let` would have to bind each
   symbol to some value and clj-kondo would then infer that value's type — binding
   to nil makes `(* 2 n)` look like arithmetic on nil. Function parameters carry
   no inferred type, which matches reality: these come from a runtime message.

   Only the anaphors are referenced in a leading vector, to keep :unused-binding
   quiet about them: the macro injects them into every body, so a handler that
   does not need `state` has done nothing wrong. Pattern bindings deliberately get
   no such treatment — a symbol destructured out of a message and then never used
   is worth reporting, and the author can silence it by renaming it `_`."
  [anaphors binding-nodes body-nodes]
  (let [anaphor-nodes (mapv api/token-node anaphors)
        params        (api/vector-node
                       (into anaphor-nodes (distinct-bindings binding-nodes)))]
    (api/list-node
     (list* (api/token-node 'fn)
            params
            ;; Mark the injected anaphors as used, then the real body.
            (api/vector-node anaphor-nodes)
            body-nodes))))

(defn- clause?
  "True when `node` is a list clause headed by one of `heads`."
  [heads node]
  (and (= :list (api/tag node))
       (contains? heads (api/sexpr (first (:children node))))))

(defn- clause-head [node] (api/sexpr (first (:children node))))

(defn- binding-vector? [node]
  (and node (= :vector (api/tag node))))

(defn- rewrite-clause
  "Rewrite one actor clause into a form clj-kondo can analyse.

   - Clauses with a binding vector — (init [args] ...), (on-error [ex msg] ...),
     (tagger [event] ...) — become `fn`s, so their params are scoped and checked
     like ordinary arguments.
   - Pattern clauses — (handle p ...), (command p ...), (event p ...) — become a
     `let` binding the pattern's symbols plus the anaphors the macro injects.
   - Plain body clauses — (on-stop ...), (on-restart ...) — become a `let` binding
     just the anaphors.
   - Anything else (e.g. (snapshot-every 50 2), (supervision strat)) keeps its
     arguments so they are still linted, minus the clause head."
  [anaphors node]
  (let [[_head arg1 & more] (:children node)
        head (clause-head node)]
    (case head
      (init tagger on-recovery-complete)
      (if (binding-vector? arg1)
        (api/list-node (list* (api/token-node 'fn) arg1 more))
        (api/list-node (list* (api/token-node 'do) (cons arg1 more))))

      (handle command event)
      (scoped-body anaphors (simple-symbol-nodes arg1) more)

      ;; (on-error [ex msg] ...) — explicit params, and `state` is bound too.
      on-error
      (if (binding-vector? arg1)
        (api/list-node
         (list* (api/token-node 'fn) arg1 [(scoped-body '[state] [] more)]))
        (scoped-body '[state] [] (cons arg1 more)))

      (on-stop on-restart)
      ;; (on-restart [reason] ...) has an optional binding vector. These bodies see
      ;; the macro's own anaphors — `state` in defactor, `this` and `state` in
      ;; defactor-persistent — so scope whichever set the caller passed.
      (if (binding-vector? arg1)
        (api/list-node
         (list* (api/token-node 'fn) arg1 [(scoped-body anaphors [] more)]))
        (scoped-body anaphors [] (cons arg1 more)))

      ;; supervision, snapshot-every, delete-events-on-snapshot, recovery,
      ;; journal-plugin-id, snapshot-plugin-id, unknown clauses
      (api/list-node (list* (api/token-node 'do) (cons arg1 more))))))

(defn- rewrite
  "Shared rewriter for both macros. `anaphors` are the symbols the macro binds
   implicitly inside pattern-clause bodies."
  [anaphors {:keys [node]}]
  (let [[_macro name-node & body] (:children node)
        docstring (when (api/string-node? (first body)) (first body))
        body      (if docstring (rest body) body)
        clause-heads #{'init 'handle 'command 'event 'on-stop 'on-restart
                       'supervision 'on-error 'tagger 'snapshot-every
                       'delete-events-on-snapshot 'on-recovery-complete
                       'recovery 'journal-plugin-id 'snapshot-plugin-id}
        {clauses true others false} (group-by #(clause? clause-heads %) body)]
    {:node
     (api/list-node
      (list* (api/token-node 'do)
             ;; The var itself, so callers resolve it and the docstring sticks.
             (api/list-node
              (cond-> [(api/token-node 'def) name-node]
                docstring (conj docstring)
                :always   (conj (api/token-node nil))))
             (concat
              ;; Non-clause forms — :persistence-id (fn [args] ...) and its value,
              ;; for instance — are linted as-is.
              others
              (map #(rewrite-clause anaphors %) clauses))))}))

(defn defactor
  "Hook for pekko-clj.core/defactor. `state` is bound in handle bodies."
  [call]
  (rewrite '[state] call))

(defn defactor-persistent
  "Hook for pekko-clj.persistence/defactor-persistent. Command bodies see both
   `this` (the actor) and `state`; event bodies see `state`."
  [call]
  (rewrite '[this state] call))
