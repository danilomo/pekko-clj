package pekko_clj.actor;

import clojure.lang.ISeq;
import clojure.lang.RT;

/**
 * Marker returned by {@code pekko-clj.persistence/persist-all}, wrapping a sequence of events for
 * {@link CljPersistentActor} to persist in order.
 *
 * <p>Using an explicit marker — rather than inspecting the shape of the value a command handler
 * returns — removes the ambiguity between "one event that happens to be a vector of vectors" and
 * "several events". A value returned from {@code persist} is always a single event whatever its
 * shape; only a value wrapped here is treated as multiple events.
 */
public final class PersistAll {

  /** The events to persist, in order. Null/empty means no events. */
  public final ISeq events;

  private PersistAll(ISeq events) {
    this.events = events;
  }

  /** Wrap a collection (or seq) of events. */
  public static PersistAll of(Object events) {
    return new PersistAll(RT.seq(events));
  }
}
