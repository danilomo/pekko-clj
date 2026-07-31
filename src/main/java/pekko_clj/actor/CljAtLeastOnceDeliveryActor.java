package pekko_clj.actor;

import org.apache.pekko.actor.*;
import org.apache.pekko.persistence.*;
import org.apache.pekko.japi.Function;
import clojure.lang.RT;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.ILookup;
import clojure.lang.ISeq;
import scala.concurrent.duration.FiniteDuration;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * A persistent actor with <b>at-least-once delivery</b> for Clojure — Pekko's
 * {@code AtLeastOnceDelivery}: a message is redelivered on an interval until the
 * destination confirms it, and the outstanding (unconfirmed) set is rebuilt on
 * restart by replaying the journal.
 *
 * <p>This is a deliberate sibling of {@link CljPersistentActor}, not a subclass or a
 * shared base, because Java single inheritance and the absence of a Scala compiler in
 * this build make it impossible to combine the two Scala traits each one needs:
 * {@code CljPersistentActor} extends {@code AbstractPersistentActorWithTimers}
 * (Timers), this one extends {@code AbstractPersistentActorWithAtLeastOnceDelivery}
 * (AtLeastOnceDelivery). There is no provided Java class mixing both, and making every
 * persistent actor at-least-once would saddle each with a periodic redelivery tick it
 * never asked for. So the persist/event machinery below is intentionally duplicated
 * (kept lean: no timers, tagging, snapshot retention, plugin overrides or sharded
 * entity mode — add them here if a use case needs them).
 *
 * <p><b>Delivery state and recovery.</b> Call {@code deliver} / {@code confirmDelivery}
 * from the <i>event</i> handler, never the command handler: recovery re-runs the event
 * handler for every replayed event, which re-issues the still-unconfirmed deliveries
 * (Pekko re-derives the same delivery ids from the restored delivery sequence number)
 * and drops the confirmed ones — so the outstanding set survives a restart with no
 * snapshot involved. The event handler therefore receives {@code this} as its first
 * argument (unlike {@code CljPersistentActor}'s two-arg one), so it can reach the
 * delivery methods while recovering.
 */
// The Scala traits this class inherits (AtLeastOnceDeliveryLike, Eventsourced) expose
// synthetic accessors with raw generic return types (SortedMap, Option, …); javac flags
// the inherited-method implementations as unchecked. Nothing here can type them — it is a
// property of extending the Scala-generated class from Java.
@SuppressWarnings("unchecked")
public class CljAtLeastOnceDeliveryActor extends AbstractPersistentActorWithAtLeastOnceDelivery
    implements IDeref {

  private static final String NS = null;
  private static final Keyword STATE = RT.keyword(NS, "state");
  private static final Keyword PERSISTENCE_ID = RT.keyword(NS, "persistence-id");
  private static final Keyword COMMAND_HANDLER = RT.keyword(NS, "command-handler");
  private static final Keyword EVENT_HANDLER = RT.keyword(NS, "event-handler");
  private static final Keyword ON_RECOVERY_COMPLETE = RT.keyword(NS, "on-recovery-complete");
  private static final Keyword POST_STOP = RT.keyword(NS, "post-stop");
  private static final Keyword SUPERVISOR_STRATEGY = RT.keyword(NS, "supervisor-strategy");
  private static final Keyword REDELIVER_INTERVAL = RT.keyword(NS, "redeliver-interval");
  private static final Keyword REDELIVERY_BURST_LIMIT = RT.keyword(NS, "redelivery-burst-limit");
  private static final Keyword WARN_AFTER_UNCONFIRMED = RT.keyword(NS, "warn-after-unconfirmed");
  private static final Keyword MAX_UNCONFIRMED = RT.keyword(NS, "max-unconfirmed");

  private Object state;
  private final String persistenceId;
  private final IFn commandHandler;
  private final IFn eventHandler;
  private final IFn onRecoveryComplete;
  private final IFn postStop;
  private final SupervisorStrategy supervisorStrategy;
  private final FiniteDuration redeliverInterval;   // null => Pekko default
  private final int redeliveryBurstLimit;           // <= 0 => Pekko default
  private final int warnAfterUnconfirmed;           // <= 0 => Pekko default
  private final int maxUnconfirmed;                 // <= 0 => Pekko default
  private boolean recovering = true;

  public static Props create(ILookup props) {
    return Props.create(CljAtLeastOnceDeliveryActor.class, props);
  }

  public CljAtLeastOnceDeliveryActor(ILookup props) {
    this.state = props.valAt(STATE, null);
    this.persistenceId = (String) props.valAt(PERSISTENCE_ID);
    this.commandHandler = (IFn) props.valAt(COMMAND_HANDLER);
    this.eventHandler = (IFn) props.valAt(EVENT_HANDLER);
    this.onRecoveryComplete = (IFn) props.valAt(ON_RECOVERY_COMPLETE, null);
    this.postStop = (IFn) props.valAt(POST_STOP, null);
    this.supervisorStrategy = (SupervisorStrategy) props.valAt(SUPERVISOR_STRATEGY, null);

    Object interval = props.valAt(REDELIVER_INTERVAL, null);
    this.redeliverInterval = interval == null ? null : toFinite((java.time.Duration) interval);
    this.redeliveryBurstLimit = intOr(props.valAt(REDELIVERY_BURST_LIMIT, null), 0);
    this.warnAfterUnconfirmed = intOr(props.valAt(WARN_AFTER_UNCONFIRMED, null), 0);
    this.maxUnconfirmed = intOr(props.valAt(MAX_UNCONFIRMED, null), 0);

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

  private static int intOr(Object v, int dflt) {
    return v != null ? ((Number) v).intValue() : dflt;
  }

  private static FiniteDuration toFinite(java.time.Duration d) {
    return FiniteDuration.apply(d.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public String persistenceId() {
    return persistenceId;
  }

  // At-least-once delivery tuning: override only when a value was supplied,
  // otherwise fall through to Pekko's configured default.

  @Override
  public FiniteDuration redeliverInterval() {
    return redeliverInterval != null ? redeliverInterval : super.redeliverInterval();
  }

  @Override
  public int redeliveryBurstLimit() {
    return redeliveryBurstLimit > 0 ? redeliveryBurstLimit : super.redeliveryBurstLimit();
  }

  @Override
  public int warnAfterNumberOfUnconfirmedAttempts() {
    return warnAfterUnconfirmed > 0 ? warnAfterUnconfirmed
                                    : super.warnAfterNumberOfUnconfirmedAttempts();
  }

  @Override
  public int maxUnconfirmedMessages() {
    return maxUnconfirmed > 0 ? maxUnconfirmed : super.maxUnconfirmedMessages();
  }

  @Override
  public SupervisorStrategy supervisorStrategy() {
    return supervisorStrategy != null ? supervisorStrategy : super.supervisorStrategy();
  }

  @Override
  public void postStop() throws Exception {
    if (postStop != null) {
      postStop.invoke(this);
    }
    super.postStop();
  }

  @Override
  public Receive createReceiveRecover() {
    return receiveBuilder()
      .match(RecoveryCompleted.class, msg -> {
        recovering = false;
        if (onRecoveryComplete != null) {
          onRecoveryComplete.invoke(this);
        }
      })
      // Replayed event: apply it. Because applyEvent runs the event handler, the
      // deliver/confirmDelivery calls in that handler re-run here too, rebuilding
      // the unconfirmed set from the journal.
      .matchAny(this::applyEvent)
      .build();
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder().matchAny(this::handleCommand).build();
  }

  private void handleCommand(Object command) {
    runOp(commandHandler.invoke(this, command));
  }

  /** Carry out one persist operation returned by a command handler (mirrors {@link
   *  CljPersistentActor#runOp}, minus tagging). {@link PersistOps} nests. */
  private void runOp(Object op) {
    if (op == null) {
      return;
    }
    if (op instanceof PersistOps) {
      for (ISeq s = ((PersistOps) op).ops; s != null; s = s.next()) {
        runOp(s.first());
      }
    } else if (op instanceof PersistAll) {
      persistBatch(((PersistAll) op).events, false);
    } else if (op instanceof PersistAsync) {
      persistBatch(((PersistAsync) op).events, true);
    } else if (op instanceof Defer) {
      deferAsync(((Defer) op).value, (Object v) -> handleCommand(v));
    } else {
      persist(op, (Object e) -> applyEvent(e));
    }
  }

  private void persistBatch(ISeq events, boolean async) {
    List<Object> batch = new ArrayList<>();
    for (ISeq s = events; s != null; s = s.next()) {
      batch.add(s.first());
    }
    if (batch.isEmpty()) return;
    if (async) {
      persistAllAsync(batch, (Object e) -> applyEvent(e));
    } else {
      persistAll(batch, (Object e) -> applyEvent(e));
    }
  }

  private void applyEvent(Object event) {
    // (fn [this state event] -> new-state). `this` is passed so the event handler
    // can call deliver / confirmDelivery, which it must during recovery too.
    Object newState = eventHandler.invoke(this, state, event);
    if (newState != null) {
      this.state = newState;
    }
  }

  @Override
  public Object deref() {
    return state;
  }

  // ---------------------------------------------------------------------------
  // Helpers accessible from Clojure (same surface as CljPersistentActor's, plus
  // the delivery methods).
  // ---------------------------------------------------------------------------

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

  public void watch(ActorRef actorRef) {
    getContext().watch(actorRef);
  }

  public void unwatch(ActorRef actorRef) {
    getContext().unwatch(actorRef);
  }

  @Override
  public void unhandled(Object message) {
    super.unhandled(message);
  }

  public boolean isRecovering() {
    return recovering;
  }

  /**
   * Deliver {@code deliveryIdToMessage(id)} to {@code destination}, redelivering until
   * {@link #confirmDeliveryId(long)} is called with the same id. Call from the event
   * handler so replay reconstructs the outstanding set.
   *
   * <p>{@code org.apache.pekko.japi.Function} is deprecated, but it is the only type
   * {@code AtLeastOnceDeliveryLike.deliver} accepts from Java — there is no
   * non-deprecated overload — so the suppression is intentional, not a stale call.
   */
  @SuppressWarnings("deprecation")
  public void deliverTo(ActorPath destination, IFn deliveryIdToMessage) {
    deliver(destination, new Function<Long, Object>() {
      @Override
      public Object apply(Long id) {
        return deliveryIdToMessage.invoke(id);
      }
    });
  }

  /** Confirm delivery {@code id}; returns true if it was outstanding. */
  public boolean confirmDeliveryId(long id) {
    return confirmDelivery(id);
  }

  /** How many messages are awaiting confirmation. */
  public int unconfirmedCount() {
    return numberOfUnconfirmed();
  }
}
