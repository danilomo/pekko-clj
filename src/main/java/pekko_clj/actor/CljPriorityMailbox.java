package pekko_clj.actor;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.dispatch.PriorityGenerator;
import org.apache.pekko.dispatch.UnboundedStablePriorityMailbox;
import com.typesafe.config.Config;
import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * A stable, unbounded priority mailbox whose per-message priority is computed by a
 * Clojure function. Pekko instantiates a mailbox type from configuration via a
 * {@code (ActorSystem.Settings, Config)} constructor; this class reads the
 * {@code priority-fn} config key — a fully-qualified {@code "namespace/var"} — and
 * resolves it to a Clojure {@link IFn}. The function is called with the message and
 * must return an integer priority: LOWER values are dequeued first. Messages of
 * equal priority keep FIFO (arrival) order.
 *
 * <p>Configure it (see {@code pekko-clj.mailbox/priority-mailbox-config}) as:
 * <pre>
 *   my-mailbox {
 *     mailbox-type = "pekko_clj.actor.CljPriorityMailbox"
 *     priority-fn  = "my.ns/my-priority"
 *   }
 * </pre>
 * then attach it to an actor's Props with {@code Props.withMailbox("my-mailbox")}.
 */
public class CljPriorityMailbox extends UnboundedStablePriorityMailbox {

  public CljPriorityMailbox(ActorSystem.Settings settings, Config config) {
    super(generator(config.getString("priority-fn")));
  }

  private static PriorityGenerator generator(final String qualifiedName) {
    final IFn priorityFn = resolve(qualifiedName);
    return new PriorityGenerator() {
      @Override
      public int gen(Object message) {
        Object result = priorityFn.invoke(message);
        return ((Number) result).intValue();
      }
    };
  }

  private static IFn resolve(String qualifiedName) {
    int slash = qualifiedName.indexOf('/');
    if (slash <= 0 || slash == qualifiedName.length() - 1) {
      throw new IllegalArgumentException(
          "priority-fn must be a fully-qualified \"namespace/var\", got: " + qualifiedName);
    }
    String namespace = qualifiedName.substring(0, slash);
    String name = qualifiedName.substring(slash + 1);
    // Ensure the namespace is loaded before resolving the var.
    Clojure.var("clojure.core", "require").invoke(Clojure.read(namespace));
    return Clojure.var(namespace, name);
  }
}
