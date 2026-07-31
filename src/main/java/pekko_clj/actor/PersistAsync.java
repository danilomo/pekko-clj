package pekko_clj.actor;

import clojure.lang.ISeq;
import clojure.lang.RT;

/**
 * Marker returned by {@code pekko-clj.persistence/persist-async} and
 * {@code persist-all-async}, wrapping events {@link CljPersistentActor} writes with Pekko's
 * {@code persistAsync}.
 *
 * <p>Unlike {@link PersistAll}, this does <em>not</em> stash the commands that arrive while the
 * write is in flight: the actor keeps processing them, and the event handler runs later. That is
 * the throughput/consistency trade-off {@code persistAsync} exists for — state read by the next
 * command may not include the event yet.
 */
public final class PersistAsync {

  /** The events to persist, in order. Null/empty means no events. */
  public final ISeq events;

  private PersistAsync(ISeq events) {
    this.events = events;
  }

  /** Wrap a collection (or seq) of events. */
  public static PersistAsync of(Object events) {
    return new PersistAsync(RT.seq(events));
  }
}
