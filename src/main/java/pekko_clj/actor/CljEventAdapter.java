package pekko_clj.actor;

import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.persistence.journal.EventAdapter;
import org.apache.pekko.persistence.journal.EventSeq;
import com.typesafe.config.Config;
import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IMeta;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.RT;

/**
 * An {@link EventAdapter} whose three hooks are Clojure functions, resolved from
 * configuration by fully-qualified {@code "namespace/var"} name. This is the
 * schema-evolution seam: {@code toJournal} rewrites events on the way out,
 * {@code fromJournal} upcasts (or splits) them on the way back in, and
 * {@code manifest} stamps a version string that {@code fromJournal} can read.
 *
 * <p>Pekko instantiates an event adapter reflectively from its class name in
 * config, passing only the {@link ExtendedActorSystem} — unlike a mailbox, an
 * adapter is <em>not</em> handed its own config section, so this class reads its
 * function names from a single fixed root, {@code pekko-clj.persistence.adapter}:
 * <pre>
 *   pekko-clj.persistence.adapter {
 *     to-journal   = "my.ns/to-journal"     # optional; identity if absent
 *     from-journal = "my.ns/from-journal"   # optional; identity (single) if absent
 *     manifest     = "my.ns/manifest"       # optional; "" if absent
 *   }
 *   pekko.persistence.journal.leveldb {
 *     event-adapters         { clj-event-adapter = "pekko_clj.actor.CljEventAdapter" }
 *     event-adapter-bindings { "java.lang.Object" = clj-event-adapter }
 *   }
 * </pre>
 * because there is one instance per ActorSystem, branch inside the functions on
 * event shape / manifest rather than trying to register several adapters. See
 * {@code pekko-clj.persistence.adapter/config} for the config builder.
 *
 * <p>Return-value contract for {@code from-journal} (a {@code (fn [event manifest])}):
 * <ul>
 *   <li>{@code nil} &rarr; the event is dropped ({@link EventSeq#empty()});</li>
 *   <li>a value tagged by {@code pekko-clj.persistence.adapter/many} &rarr; each of
 *       its elements becomes one recovered event (a one-to-many split);</li>
 *   <li>any other value &rarr; a single recovered event — even a Clojure vector,
 *       so an upcast such as {@code [:v1 x]} &rarr; {@code [:v2 x default]} is
 *       never mistaken for a split.</li>
 * </ul>
 */
public class CljEventAdapter implements EventAdapter {

  private static final String ROOT = "pekko-clj.persistence.adapter";
  // Set by pekko-clj.persistence.adapter/many on the value it returns.
  private static final Keyword SPLIT_KEY = Keyword.intern(ROOT, "split");

  private final IFn toJournalFn;   // may be null
  private final IFn fromJournalFn; // may be null
  private final IFn manifestFn;    // may be null

  public CljEventAdapter(ExtendedActorSystem system) {
    Config config = system.settings().config();
    this.toJournalFn = resolveOpt(config, ROOT + ".to-journal");
    this.fromJournalFn = resolveOpt(config, ROOT + ".from-journal");
    this.manifestFn = resolveOpt(config, ROOT + ".manifest");
  }

  @Override
  public Object toJournal(Object event) {
    return toJournalFn == null ? event : toJournalFn.invoke(event);
  }

  @Override
  public String manifest(Object event) {
    if (manifestFn == null) {
      return "";
    }
    Object m = manifestFn.invoke(event);
    return m == null ? "" : m.toString();
  }

  @Override
  public EventSeq fromJournal(Object event, String manifest) {
    Object result = fromJournalFn == null ? event : fromJournalFn.invoke(event, manifest);
    if (result == null) {
      return EventSeq.empty();
    }
    if (result instanceof EventSeq) {
      return (EventSeq) result;
    }
    if (result instanceof IMeta) {
      IPersistentMap meta = ((IMeta) result).meta();
      if (meta != null && meta.containsKey(SPLIT_KEY)) {
        return EventSeq.create(RT.toArray(result));
      }
    }
    return EventSeq.single(result);
  }

  private static IFn resolveOpt(Config config, String path) {
    return config.hasPath(path) ? resolve(config.getString(path)) : null;
  }

  private static IFn resolve(String qualifiedName) {
    int slash = qualifiedName.indexOf('/');
    if (slash <= 0 || slash == qualifiedName.length() - 1) {
      throw new IllegalArgumentException(
          "event-adapter function must be a fully-qualified \"namespace/var\", got: "
              + qualifiedName);
    }
    String namespace = qualifiedName.substring(0, slash);
    String name = qualifiedName.substring(slash + 1);
    // Ensure the namespace is loaded before resolving the var.
    Clojure.var("clojure.core", "require").invoke(Clojure.read(namespace));
    return Clojure.var(namespace, name);
  }
}
