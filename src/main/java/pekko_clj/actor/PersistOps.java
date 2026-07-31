package pekko_clj.actor;

import clojure.lang.ISeq;
import clojure.lang.RT;

/**
 * Marker returned by {@code pekko-clj.persistence/then}, wrapping several persist operations a
 * command handler wants {@link CljPersistentActor} to run in order.
 *
 * <p>A command handler returns one value, so this is how "persist these events, then reply once
 * they are written" is expressed: {@code (then (persist-all [e1 e2]) (defer :done))}. Each element
 * is itself an operation — a bare event, a {@link PersistAll}, a {@link PersistAsync}, a {@link
 * Defer}, or a nested {@code PersistOps}.
 */
public final class PersistOps {

  /** The operations, in the order they should run. Null/empty means nothing to do. */
  public final ISeq ops;

  private PersistOps(ISeq ops) {
    this.ops = ops;
  }

  /** Wrap a collection (or seq) of operations. */
  public static PersistOps of(Object ops) {
    return new PersistOps(RT.seq(ops));
  }
}
