package pekko_clj.actor;

import org.apache.pekko.actor.*;
import org.apache.pekko.event.Logging;
import org.apache.pekko.event.LoggingAdapter;
import org.apache.pekko.persistence.*;
import org.apache.pekko.persistence.journal.Tagged;
import org.apache.pekko.japi.pf.ReceiveBuilder;
import clojure.lang.RT;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.ILookup;
import clojure.lang.ISeq;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A persistent actor implementation for Clojure.
 *
 * Supports event sourcing with:
 * - Command handling (returns events to persist)
 * - Event handling (applies events to state)
 * - Event tagging (for persistence-query eventsByTag)
 * - Snapshot support, with optional retention (keep-snapshots /
 *   delete-events-on-snapshot)
 * - Recovery
 */
public class CljPersistentActor extends AbstractPersistentActor implements IDeref {

  private static final String NS = null;
  private static final Keyword STATE = RT.keyword(NS, "state");
  private static final Keyword PERSISTENCE_ID = RT.keyword(NS, "persistence-id");
  private static final Keyword COMMAND_HANDLER = RT.keyword(NS, "command-handler");
  private static final Keyword EVENT_HANDLER = RT.keyword(NS, "event-handler");
  private static final Keyword SNAPSHOT_EVERY = RT.keyword(NS, "snapshot-every");
  private static final Keyword KEEP_SNAPSHOTS = RT.keyword(NS, "keep-snapshots");
  private static final Keyword DELETE_EVENTS_ON_SNAPSHOT =
    RT.keyword(NS, "delete-events-on-snapshot");
  private static final Keyword TAGGER = RT.keyword(NS, "tagger");
  private static final Keyword ON_RECOVERY_COMPLETE = RT.keyword(NS, "on-recovery-complete");

  private Object state;
  private final String persistenceId;
  private final IFn commandHandler;
  private final IFn eventHandler;
  private final int snapshotEvery;
  private final int keepSnapshots;
  private final boolean deleteEventsOnSnapshot;
  private final IFn tagger;
  private final IFn onRecoveryComplete;
  private long eventsSinceSnapshot = 0;
  private boolean recovering = true;
  private LoggingAdapter log;

  public static Props create(ILookup props) {
    return Props.create(CljPersistentActor.class, props);
  }

  public CljPersistentActor(ILookup props) {
    this.state = props.valAt(STATE, null);
    this.persistenceId = (String) props.valAt(PERSISTENCE_ID);
    this.commandHandler = (IFn) props.valAt(COMMAND_HANDLER);
    this.eventHandler = (IFn) props.valAt(EVENT_HANDLER);
    Object snapshotEveryVal = props.valAt(SNAPSHOT_EVERY, null);
    this.snapshotEvery = snapshotEveryVal != null ? ((Number) snapshotEveryVal).intValue() : 0;
    Object keepSnapshotsVal = props.valAt(KEEP_SNAPSHOTS, null);
    this.keepSnapshots = keepSnapshotsVal != null ? ((Number) keepSnapshotsVal).intValue() : 0;
    this.deleteEventsOnSnapshot = RT.booleanCast(props.valAt(DELETE_EVENTS_ON_SNAPSHOT, false));
    this.tagger = (IFn) props.valAt(TAGGER, null);
    this.onRecoveryComplete = (IFn) props.valAt(ON_RECOVERY_COMPLETE, null);

    if (persistenceId == null) {
      throw new IllegalArgumentException("persistence-id is required");
    }
    if (commandHandler == null) {
      throw new IllegalArgumentException("command-handler is required");
    }
    if (eventHandler == null) {
      throw new IllegalArgumentException("event-handler is required");
    }
  }

  @Override
  public String persistenceId() {
    return persistenceId;
  }

  @Override
  public Receive createReceiveRecover() {
    return receiveBuilder()
      .match(SnapshotOffer.class, offer -> {
        this.state = offer.snapshot();
        // The offered snapshot subsumes every event up to its sequence number,
        // so cadence restarts from here rather than from 0 (which would forget
        // however many events preceded this recovery's most recent restart).
        eventsSinceSnapshot = 0;
      })
      .match(RecoveryCompleted.class, msg -> {
        recovering = false;
        if (onRecoveryComplete != null) {
          onRecoveryComplete.invoke(this);
        }
      })
      .matchAny(event -> {
        // It's an event - apply it to state
        applyEvent(event);
        eventsSinceSnapshot++;
      })
      .build();
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      // Journal/snapshot-store protocol replies are infrastructure, not user
      // commands: handle retention here and never route them to the command
      // handler (which would try to match them as a message).
      .match(SaveSnapshotSuccess.class, msg -> applyRetention(msg.metadata()))
      .match(SaveSnapshotFailure.class, msg ->
        logger().warning("Snapshot save failed for [{}]: {}",
                         persistenceId, msg.cause().getMessage()))
      .match(DeleteSnapshotsFailure.class, msg ->
        logger().warning("Snapshot deletion failed for [{}]: {}",
                         persistenceId, msg.cause().getMessage()))
      .match(DeleteMessagesFailure.class, msg ->
        logger().warning("Event deletion failed for [{}]: {}",
                         persistenceId, msg.cause().getMessage()))
      .match(DeleteSnapshotsSuccess.class, msg -> {})
      .match(DeleteMessagesSuccess.class, msg -> {})
      .matchAny(command -> {
        // Call command handler: (fn [this command] ...) -> event, (persist-all ...)
        // marker, or nil. No shape inspection: a returned value is a single event
        // whatever its shape unless it is an explicit PersistAll marker, which
        // removes the "vector of vectors" ambiguity of the old heuristic.
        Object result = commandHandler.invoke(this, command);

        if (result == null) {
          // No event to persist.
          return;
        }

        if (result instanceof PersistAll) {
          // Multiple events (from persist-all) - one atomic journal write, applied in order.
          persistAllEvents(((PersistAll) result).events);
        } else {
          // Any other value is a single event, whatever its shape.
          persistEvent(result);
        }
      })
      .build();
  }

  private void persistEvent(Object event) {
    persist(withTags(event), (Object e) -> handlePersistedEvent(e));
  }

  /**
   * Persist a batch of events atomically.
   *
   * <p>{@code persistAll} hands the journal the whole batch as one write: either every event of
   * the batch is stored or none is. Persisting them as nested single {@code persist} calls — one
   * journal write each — would let a crash mid-batch leave a partial event sequence behind, which
   * is exactly the half-applied command {@code persist-all} exists to prevent.
   *
   * <p>The callback still runs once per event, in order, after the write, so event application and
   * snapshot cadence are unchanged.
   */
  private void persistAllEvents(ISeq events) {
    List<Object> batch = new ArrayList<>();
    for (ISeq s = events; s != null; s = s.next()) {
      batch.add(withTags(s.first()));
    }
    if (batch.isEmpty()) return;
    persistAll(batch, (Object e) -> handlePersistedEvent(e));
  }

  /**
   * Wrap an event in a Tagged envelope when the tagger returns tags for it.
   * The journal strips the envelope: it stores the payload plus a tag index,
   * so tags are invisible to the event handler and to recovery.
   */
  private Object withTags(Object event) {
    if (tagger == null) return event;
    Object tags = tagger.invoke(event);
    if (tags == null) return event;
    Set<String> tagSet = new LinkedHashSet<>();
    for (ISeq s = RT.seq(tags); s != null; s = s.next()) {
      Object tag = s.first();
      if (tag != null) {
        tagSet.add(tag instanceof String ? (String) tag : tag.toString());
      }
    }
    return tagSet.isEmpty() ? event : new Tagged(event, tagSet);
  }

  private void handlePersistedEvent(Object event) {
    applyEvent(event);
    eventsSinceSnapshot++;

    // Check if we should take a snapshot
    if (snapshotEvery > 0 && eventsSinceSnapshot >= snapshotEvery) {
      saveSnapshot(state);
      eventsSinceSnapshot = 0;
    }
  }

  private void applyEvent(Object event) {
    // Persist hands the callback whatever was passed to it, so a tagged event
    // arrives wrapped here; on recovery the journal has already unwrapped it.
    // Unwrap so the event handler only ever sees the raw event.
    Object payload = event instanceof Tagged ? ((Tagged) event).payload() : event;
    // Call event handler: (fn [state event] ...) -> new-state
    Object newState = eventHandler.invoke(state, payload);
    if (newState != null) {
      this.state = newState;
    }
  }

  /**
   * Snapshot retention: once a snapshot at sequence number N is stored, the
   * keep-snapshots most recent snapshots span the last (keep-snapshots *
   * snapshot-every) events, so everything at or below that lower bound is
   * redundant and can be dropped — along with the events it subsumes when
   * delete-events-on-snapshot is set.
   */
  private void applyRetention(SnapshotMetadata metadata) {
    if (keepSnapshots <= 0 || snapshotEvery <= 0) return;
    long deleteUpTo = metadata.sequenceNr() - ((long) keepSnapshots * snapshotEvery);
    if (deleteUpTo <= 0) return;
    deleteSnapshots(SnapshotSelectionCriteria.create(deleteUpTo, Long.MAX_VALUE));
    if (deleteEventsOnSnapshot) {
      deleteMessages(deleteUpTo);
    }
  }

  private LoggingAdapter logger() {
    if (log == null) {
      log = Logging.getLogger(getContext().getSystem(), this);
    }
    return log;
  }

  @Override
  public Object deref() {
    return state;
  }

  // Helper methods accessible from Clojure

  public ActorRef senderRef() {
    return getSender();
  }

  public ActorRef selfRef() {
    return getSelf();
  }

  public ActorContext actorContext() {
    return getContext();
  }

  public void reply(Object msg) {
    getSender().tell(msg, getSelf());
  }

  public void tell(ActorRef ref, Object msg) {
    ref.tell(msg, getSelf());
  }

  /**
   * Mark a command as unhandled. Delegates to Pekko's default handling, which
   * publishes an {@link org.apache.pekko.actor.UnhandledMessage} to the actor
   * system's event stream. Used by the {@code defactor-persistent} catch-all so
   * an unmatched command does not vanish silently. Mirrors {@link CljActor}'s.
   */
  @Override
  public void unhandled(Object message) {
    super.unhandled(message);
  }

  public boolean isRecovering() {
    return recovering;
  }

  public long getLastSequenceNr() {
    return lastSequenceNr();
  }

  /**
   * Manually trigger a snapshot.
   */
  public void triggerSnapshot() {
    saveSnapshot(state);
    eventsSinceSnapshot = 0;
  }

  /**
   * Delete events up to a sequence number.
   */
  public void deleteEventsTo(long sequenceNr) {
    deleteMessages(sequenceNr);
  }

  /**
   * Delete snapshots matching criteria.
   */
  public void deleteSnapshotsMatching(SnapshotSelectionCriteria criteria) {
    deleteSnapshots(criteria);
  }
}
