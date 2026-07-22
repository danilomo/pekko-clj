(ns hooks.pekko-clj.routing
  "clj-kondo hook for the Compojure-style HTTP route macros — GET, POST, PUT,
   DELETE, PATCH in `pekko-clj.http.routing`.

   Each call looks like (GET \"/users/:id\" [id] body...): the binding vector
   names the pattern's `:param` segments, and the macro scopes those symbols over
   the body. clj-kondo cannot expand the macro, so without this hook every such
   symbol is reported as unresolved."
  (:require [clj-kondo.hooks-api :as api]))

(defn route
  "Rewrite (VERB pattern [bindings] body...) into (fn [bindings] pattern body...)
   so the bindings are scoped over the body and the pattern is still linted.

   The pattern sits inside the `fn` rather than beside it: a route macro is
   almost always used for its value, so a `do` wrapper would make the whole call
   look like it returns the last body form to callers reading the rewrite."
  [{:keys [node]}]
  (let [[_macro pattern bindings & body] (:children node)]
    (if (and bindings (= :vector (api/tag bindings)))
      {:node (api/list-node
              (list* (api/token-node 'fn) bindings pattern body))}
      ;; Malformed call — leave it alone so the arity/usage errors surface as-is.
      {:node node})))
