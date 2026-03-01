package pekko_clj.actor;

import org.apache.pekko.actor.*;
import org.apache.pekko.persistence.*;
import org.apache.pekko.japi.pf.ReceiveBuilder;
import clojure.lang.RT;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import clojure.lang.ILookup;
import clojure.lang.Seqable;
import clojure.lang.ISeq;

/**
 * A persistent actor implementation for Clojure.
 *
 * Supports event sourcing with:
 * - Command handling (returns events to persist)
 * - Event handling (applies events to state)
 * - Snapshot support
 * - Recovery
 */
public class CljPersistentActor extends AbstractPersistentActor implements IDeref {

  private static final String NS = null;
  private static final Keyword STATE = RT.keyword(NS, "state");
  private static final Keyword PERSISTENCE_ID = RT.keyword(NS, "persistence-id");
  private static final Keyword COMMAND_HANDLER = RT.keyword(NS, "command-handler");
  private static final Keyword EVENT_HANDLER = RT.keyword(NS, "event-handler");
  private static final Keyword SNAPSHOT_EVERY = RT.keyword(NS, "snapshot-every");
  private static final Keyword ON_RECOVERY_COMPLETE = RT.keyword(NS, "on-recovery-complete");

  private Object state;
  private final String persistenceId;
  private final IFn commandHandler;
  private final IFn eventHandler;
  private final int snapshotEvery;
  private final IFn onRecoveryComplete;
  private long eventsSinceSnapshot = 0;
  private boolean recovering = true;

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
      })
      .build();
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .matchAny(command -> {
        // Call command handler: (fn [this command] ...) -> event or [events...] or nil
        Object result = commandHandler.invoke(this, command);

        if (result == null) {
          // No event to persist
          return;
        }

        // Check if result is a vector of events or a single event
        if (result instanceof PersistentVector) {
          PersistentVector events = (PersistentVector) result;
          if (events.count() == 0) {
            return;
          }

          // Check if first element is itself a vector (multiple events)
          Object first = events.nth(0);
          if (first instanceof PersistentVector) {
            // Multiple events - persist all sequentially
            ISeq seq = ((Seqable) events).seq();
            persistAllEvents(seq);
          } else {
            // Single event as vector
            persistEvent(result);
          }
        } else {
          // Single non-vector event
          persistEvent(result);
        }
      })
      .build();
  }

  private void persistEvent(Object event) {
    persist(event, (Object e) -> handlePersistedEvent(e));
  }

  private void persistAllEvents(ISeq events) {
    if (events == null) return;
    Object event = events.first();
    ISeq rest = events.next();
    persist(event, (Object e) -> {
      handlePersistedEvent(e);
      if (rest != null) {
        persistAllEvents(rest);
      }
    });
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
    // Call event handler: (fn [state event] ...) -> new-state
    Object newState = eventHandler.invoke(state, event);
    if (newState != null) {
      this.state = newState;
    }
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
