package pekko_clj.actor;

import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.Props;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.Terminated;
import org.apache.pekko.actor.typed.javadsl.Adapter;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * Runs a classic actor inside a typed {@link Behavior}.
 *
 * <p>{@code ShardedDaemonProcess} has no classic API — it only accepts typed behaviors — so
 * this is the narrow typed shim that lets {@code pekko-clj.cluster.daemon} keep N instances of
 * an ordinary {@code defactor} alive across the cluster. The wrapper:
 *
 * <ul>
 *   <li>spawns the classic actor as its child on start,
 *   <li>forwards every message it receives (including the daemon process stop message) to that
 *       child, and
 *   <li>stops itself when the child terminates, so the daemon process can restart the instance.
 * </ul>
 */
public final class CljDaemonProcess {

  private CljDaemonProcess() {}

  /** A typed behavior that owns one classic actor created from {@code props}. */
  public static Behavior<Object> wrap(final Props props) {
    return Behaviors.setup(
        ctx -> {
          final ActorRef child = Adapter.actorOf(ctx, props);
          ctx.watch(Adapter.toTyped(child));
          return Behaviors.receive(Object.class)
              .onAnyMessage(
                  msg -> {
                    child.tell(msg, ActorRef.noSender());
                    return Behaviors.same();
                  })
              .onSignal(Terminated.class, sig -> Behaviors.stopped())
              .build();
        });
  }
}
