package pekko_clj.actor;

/**
 * Marker returned by {@code pekko-clj.persistence/defer}, wrapping a value {@link
 * CljPersistentActor} hands back to the command handler once every persist issued before it has
 * completed (Pekko's {@code deferAsync}).
 *
 * <p>The value is <em>not</em> written to the journal — it never reaches the event handler and is
 * gone after a restart. It exists to sequence a side effect (typically a reply) after the writes
 * of the same command.
 */
public final class Defer {

  /** The value handed to the command handler when the preceding writes have completed. */
  public final Object value;

  private Defer(Object value) {
    this.value = value;
  }

  /** Wrap a value. */
  public static Defer of(Object value) {
    return new Defer(value);
  }
}
