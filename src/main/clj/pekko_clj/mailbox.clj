(ns pekko-clj.mailbox
  "Custom mailbox helpers. Currently a priority mailbox: messages are dequeued by a
   priority computed from a Clojure function (lower value = higher priority; equal
   priorities keep FIFO order), backed by the Java `CljPriorityMailbox`.

   Usage:
     ;; A priority function (message -> int; lower is served first):
     (defn my-priority [msg]
       (if (= (:type msg) :urgent) 0 100))

     ;; Register the mailbox in the ActorSystem config, then attach it to an actor:
     (def sys (core/actor-system \"app\"
                (mailbox/priority-mailbox-config \"urgent-mailbox\" `my-priority)))
     (def a (core/spawn-props sys
              (mailbox/with-mailbox (core/actor-props my-actor) \"urgent-mailbox\")))

   The config produced by `priority-mailbox-config` must be present in the
   ActorSystem's configuration (pass it to `core/actor-system`, or merge it via
   `cluster/create-system`'s `:extra-config`)."
  (:import [com.typesafe.config Config ConfigFactory]
           [org.apache.pekko.actor Props]))

(def ^:private priority-mailbox-class "pekko_clj.actor.CljPriorityMailbox")

(defn- fq-name
  "Render `priority-fn` as a fully-qualified \"namespace/name\" string. Accepts a
   var, a namespaced symbol (e.g. `my.ns/f from a backquote), or such a string."
  [priority-fn]
  (cond
    (var? priority-fn) (let [m (meta priority-fn)]
                         (str (ns-name (:ns m)) "/" (:name m)))
    (and (symbol? priority-fn) (namespace priority-fn)) (str priority-fn)
    (string? priority-fn) priority-fn
    :else (throw (IllegalArgumentException.
                  (str "priority-fn must be a var, a namespaced symbol, or a "
                       "\"ns/name\" string, got " (pr-str priority-fn))))))

(defn priority-mailbox-config
  "Build a com.typesafe.config.Config defining a priority mailbox under
   `mailbox-id` whose priority is computed by `priority-fn` (a var, a namespaced
   symbol, or a \"ns/name\" string naming a 1-arg fn: message -> int, lower served
   first). Merge the result into the ActorSystem config, then attach the mailbox to
   an actor's Props with `with-mailbox` and spawn via `core/spawn-props`."
  ^Config [mailbox-id priority-fn]
  (ConfigFactory/parseString
   (str (name mailbox-id) " {\n"
        "  mailbox-type = \"" priority-mailbox-class "\"\n"
        "  priority-fn = \"" (fq-name priority-fn) "\"\n"
        "}\n")))

(defn with-mailbox
  "Return `props` configured to use the mailbox registered under `mailbox-id`
   (e.g. from `priority-mailbox-config`)."
  ^Props [^Props props mailbox-id]
  (.withMailbox props (name mailbox-id)))
