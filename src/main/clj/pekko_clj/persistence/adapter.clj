(ns pekko-clj.persistence.adapter
  "Persistence event adapters — the schema-evolution seam for pekko-clj persistent
   actors, wrapping Pekko's `EventAdapter` (`toJournal`/`fromJournal`/`manifest`).

   An adapter lets you change your event schema without breaking recovery of events
   already in the journal:

   - `to-journal` rewrites an event on the way OUT (write side). Optional; identity
     if omitted.
   - `manifest` stamps a version/type string alongside the stored event. Optional;
     the empty string if omitted.
   - `from-journal` transforms a stored event on the way BACK IN (read/recovery
     side), given the event and its stored manifest — this is where you upcast old
     events to the current schema, or split one stored event into several.

   Because Pekko instantiates an adapter from its class name in config and hands it
   only the ActorSystem (never its own config section), pekko-clj exposes ONE
   adapter per ActorSystem, reading its three function names from the fixed config
   root `pekko-clj.persistence.adapter`. Branch inside your functions on event shape
   or manifest rather than registering several adapters.

   `from-journal` return-value contract:
   - `nil`                         -> the event is dropped
   - a value wrapped by `(many …)` -> one recovered event per element (split)
   - any other value               -> a single recovered event (even a Clojure
                                       vector, so `[:v1 x]` -> `[:v2 x default]` is
                                       never mistaken for a split).

   Example:
     (defn upcast [event _manifest]
       (match event
         [:v1 x] [:v2 x :default]   ; upcast old events
         :else   event))            ; pass everything else through unchanged

     (def sys (core/actor-system \"app\"
                (adapter/config {:journal-plugin \"pekko.persistence.journal.leveldb\"
                                 :from-journal `upcast})))

   The Config produced by `config` must be present in the ActorSystem's
   configuration (pass it to `core/actor-system`, or merge it via
   `cluster/create-system`'s `:extra-config`; it merges onto your journal's own
   config with `withFallback`)."
  (:import [com.typesafe.config Config ConfigFactory]))

(def ^:private adapter-class "pekko_clj.actor.CljEventAdapter")
;; There is one pekko-clj adapter per system, so a single fixed logical name.
(def ^:private adapter-name "clj-event-adapter")

(defn many
  "Wrap `events` (a collection) so that a `from-journal` fn returning it produces
   one recovered event per element — a one-to-many split. Returning a bare value
   yields a single event; returning `nil` drops the event."
  [events]
  (with-meta (vec events) {::split true}))

(defn- fq-name
  "Render an event-adapter fn as a fully-qualified \"namespace/name\" string.
   Accepts a var, a namespaced symbol (e.g. `my.ns/f from a backquote), or such a
   string."
  [f]
  (cond
    (var? f) (let [m (meta f)]
               (str (ns-name (:ns m)) "/" (:name m)))
    (and (symbol? f) (namespace f)) (str f)
    (string? f) f
    :else (throw (IllegalArgumentException.
                  (str "event-adapter fn must be a var, a namespaced symbol, or a "
                       "\"ns/name\" string, got " (pr-str f))))))

(defn- binding-name
  "Coerce a single binding target (a class name string or a java.lang.Class) to the
   class-name string HOCON needs."
  [b]
  (cond
    (class? b) (.getName ^Class b)
    (string? b) b
    (symbol? b) (str b)
    :else (throw (IllegalArgumentException.
                  (str "event-adapter binding must be a class or class-name string, got "
                       (pr-str b))))))

(defn config
  "Build a com.typesafe.config.Config that registers the pekko-clj event adapter and
   binds it, resolving its hooks to the supplied Clojure fns.

   Options:
   - :journal-plugin (required) — the journal plugin id whose events the adapter
     serves, e.g. \"pekko.persistence.journal.leveldb\". Must match the persistent
     actors' journal (their `journal-plugin-id`, or the configured default).
   - :bindings — the event class(es) to route through the adapter, as a class,
     class-name string, or collection of either. Defaults to [\"java.lang.Object\"]
     (every event). Pekko matches by class hierarchy, so Object catches all.
   - :to-journal / :from-journal / :manifest — the hook fns (a var, a namespaced
     symbol, or a \"ns/name\" string). Each is optional; an omitted hook is the
     identity (`to-journal`/`from-journal`) or the empty string (`manifest`).

   Merge the result onto the ActorSystem config (it uses `withFallback` semantics,
   so it composes with the journal's own dir/native settings)."
  ^Config [{:keys [journal-plugin bindings to-journal from-journal manifest]
            :or {bindings ["java.lang.Object"]}}]
  (when-not journal-plugin
    (throw (IllegalArgumentException.
            ":journal-plugin is required (e.g. \"pekko.persistence.journal.leveldb\")")))
  (let [bindings (if (or (string? bindings) (class? bindings) (symbol? bindings))
                   [bindings]
                   bindings)
        fn-line (fn [k f] (when f (str "  " (name k) " = \"" (fq-name f) "\"\n")))
        fns (str (fn-line :to-journal to-journal)
                 (fn-line :from-journal from-journal)
                 (fn-line :manifest manifest))
        bind-lines (apply str (for [b bindings]
                                (str "    \"" (binding-name b) "\" = " adapter-name "\n")))]
    (ConfigFactory/parseString
     (str "pekko-clj.persistence.adapter {\n" fns "}\n"
          journal-plugin " {\n"
          "  event-adapters { " adapter-name " = \"" adapter-class "\" }\n"
          "  event-adapter-bindings {\n" bind-lines "  }\n"
          "}\n"))))
