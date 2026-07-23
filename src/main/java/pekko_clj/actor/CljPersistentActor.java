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
 *
 * <p>Two ways to supply the persistence id and initial state:
 * <ul>
 *   <li><b>Eager</b> — {@code :persistence-id} and {@code :state} are computed by the caller
 *       (from the spawn args) and baked into the Props. This is what
 *       {@code persistence/spawn} does.</li>
 *   <li><b>Entity</b> — {@code :persistence-id-fn} (and optionally {@code :init-fn}) are
 *       invoked with this actor's entity id at construction time. Cluster sharding creates
 *       every entity of a type from one shared Props, so nothing per-entity can be baked in;
 *       Pekko names each entity actor by its entity id, which is what these functions get.</li>
 * </ul>
 */
public class CljPersistentActor extends AbstractPersistentActorWithTimers implements IDeref {

  private static final String NS = null;
  private static final Keyword STATE = RT.keyword(NS, "state");
  private static final Keyword PERSISTENCE_ID = RT.keyword(NS, "persistence-id");
  private static final Keyword PERSISTENCE_ID_FN = RT.keyword(NS, "persistence-id-fn");
  private static final Keyword INIT_FN = RT.keyword(NS, "init-fn");
  private static final Keyword COMMAND_HANDLER = RT.keyword(NS, "command-handler");
  private static final Keyword EVENT_HANDLER = RT.keyword(NS, "event-handler");
  private static final Keyword SNAPSHOT_EVERY = RT.keyword(NS, "snapshot-every");
  private static final Keyword KEEP_SNAPSHOTS = RT.keyword(NS, "keep-snapshots");
  private static final Keyword DELETE_EVENTS_ON_SNAPSHOT =
    RT.keyword(NS, "delete-events-on-snapshot");
  private static final Keyword TAGGER = RT.keyword(NS, "tagger");
  private static final Keyword ON_RECOVERY_COMPLETE = RT.keyword(NS, "on-recovery-complete");
  private static final Keyword POST_STOP = RT.keyword(NS, "post-stop");
  private static final Keyword SUPERVISOR_STRATEGY = RT.keyword(NS, "supervisor-strategy");
  private static final Keyword JOURNAL_PLUGIN_ID = RT.keyword(NS, "journal-plugin-id");
  private static final Keyword SNAPSHOT_PLUGIN_ID = RT.keyword(NS, "snapshot-plugin-id");
  private static final Keyword RECOVERY = RT.keyword(NS, "recovery");

  private Object state;
  private final String persistenceId;
  private final IFn commandHandler;
  private final IFn eventHandler;
  private final int snapshotEvery;
  private final int keepSnapshots;
  private final boolean deleteEventsOnSnapshot;
  private final IFn tagger;
  private final IFn onRecoveryComplete;
  private final IFn postStop;
  private final SupervisorStrategy supervisorStrategy;
  private final String journalPluginId;
  private final String snapshotPluginId;
  private final Recovery recovery;
  private long eventsSinceSnapshot = 0;
  private boolean recovering = true;
  private LoggingAdapter log;

  public static Props create(ILookup props) {
    return Props.create(CljPersistentActor.class, props);
  }

  public CljPersistentActor(ILookup props) {
    IFn persistenceIdFn = (IFn) props.valAt(PERSISTENCE_ID_FN, null);
    if (persistenceIdFn != null) {
      // Entity mode (cluster sharding). Every entity of a sharded type is created
      // from the same Props, so neither the persistence id nor the initial state
      // can be baked into it: both are derived from the entity id, which Pekko uses
      // as the entity actor's path name. self() is already available here — the
      // Actor trait initializes it before this constructor body runs.
      Object entityId = getSelf().path().name();
      Object id = persistenceIdFn.invoke(entityId);
      this.persistenceId = id == null ? null : id.toString();
      IFn initFn = (IFn) props.valAt(INIT_FN, null);
      this.state = initFn != null ? initFn.invoke(entityId) : null;
    } else {
      this.state = props.valAt(STATE, null);
      this.persistenceId = (String) props.valAt(PERSISTENCE_ID);
    }
    this.commandHandler = (IFn) props.valAt(COMMAND_HANDLER);
    this.eventHandler = (IFn) props.valAt(EVENT_HANDLER);
    Object snapshotEveryVal = props.valAt(SNAPSHOT_EVERY, null);
    this.snapshotEvery = snapshotEveryVal != null ? ((Number) snapshotEveryVal).intValue() : 0;
    Object keepSnapshotsVal = props.valAt(KEEP_SNAPSHOTS, null);
    this.keepSnapshots = keepSnapshotsVal != null ? ((Number) keepSnapshotsVal).intValue() : 0;
    this.deleteEventsOnSnapshot = RT.booleanCast(props.valAt(DELETE_EVENTS_ON_SNAPSHOT, false));
    this.tagger = (IFn) props.valAt(TAGGER, null);
    this.onRecoveryComplete = (IFn) props.valAt(ON_RECOVERY_COMPLETE, null);
    this.postStop = (IFn) props.valAt(POST_STOP, null);
    this.supervisorStrategy = (SupervisorStrategy) props.valAt(SUPERVISOR_STRATEGY, null);
    this.journalPluginId = (String) props.valAt(JOURNAL_PLUGIN_ID, null);
    this.snapshotPluginId = (String) props.valAt(SNAPSHOT_PLUGIN_ID, null);
    this.recovery = (Recovery) props.valAt(RECOVERY, null);

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

  // ---------------------------------------------------------------------------
  // Scala trait linearization, spelled out for javac
  // ---------------------------------------------------------------------------
  //
  // AbstractPersistentActorWithTimers mixes in both Timers and Eventsourced, and both define
  // aroundPreRestart / aroundPostStop / aroundReceive. Scala resolves that by linearization and
  // emits the resolution as *synthetic bridge* methods on the class; javac ignores synthetic
  // methods when deciding what a subclass inherits, so to it the two interface defaults are simply
  // unrelated and the class does not compile ("inherits unrelated defaults").
  //
  // Eventsourced is mixed in last, so it is the outermost link: its implementation drives the
  // recovery/persist state machine and then calls its own super, which the superclass wires to
  // Timers, which in turn calls Actor's. Delegating to the Eventsourced static forwarders
  // reproduces that chain exactly — verified against the bridge's own bytecode
  // (`javap -c AbstractPersistentActorWithTimers`), which invokes Eventsourced.aroundReceive$.
  //
  // Do NOT delegate to Timers here: that enters the chain one link too low and skips Eventsourced
  // entirely, so recovery never completes and no command ever persists. (Measured: every
  // persistence test failed with the actor stuck in `recovering?` = true.) Do not "simplify" these
  // to super.aroundX() either — that resolves to the synthetic bridge.

  @Override
  public void aroundPreRestart(Throwable reason, scala.Option<Object> message) {
    Eventsourced.aroundPreRestart$(this, reason, message);
  }

  @Override
  public void aroundPostStop() {
    Eventsourced.aroundPostStop$(this);
  }

  @Override
  public void aroundReceive(scala.PartialFunction<Object, scala.runtime.BoxedUnit> receive,
                            Object msg) {
    Eventsourced.aroundReceive$(this, receive, msg);
  }

  @Override
  public String persistenceId() {
    return persistenceId;
  }

  /**
   * Journal plugin for this actor only. Pekko reads {@code ""} as "use the plugin configured under
   * {@code pekko.persistence.journal.plugin}", which is the default when no id is given.
   */
  @Override
  public String journalPluginId() {
    return journalPluginId == null ? "" : journalPluginId;
  }

  /** Snapshot-store plugin for this actor only. See {@link #journalPluginId()}. */
  @Override
  public String snapshotPluginId() {
    return snapshotPluginId == null ? "" : snapshotPluginId;
  }

  /**
   * Recovery strategy: which snapshot to start from, how far to replay, or {@code Recovery.none()}
   * for an actor that only writes (commands-only, no replay on start).
   */
  @Override
  public Recovery recovery() {
    return recovery == null ? Recovery.create() : recovery;
  }

  @Override
  public SupervisorStrategy supervisorStrategy() {
    return supervisorStrategy != null ? supervisorStrategy : super.supervisorStrategy();
  }

  @Override
  public void postStop() throws Exception {
    // Runs on stop, and (via the default preRestart) on restart. Powers the
    // defactor-persistent `on-stop` clause. There is no `on-restart` counterpart:
    // a restarted persistent actor rebuilds its state by replaying the journal, so
    // `on-recovery-complete` is the hook that fires once the state is valid again.
    if (postStop != null) {
      postStop.invoke(this);
    }
    super.postStop();
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
      .matchAny(this::handleCommand)
      .build();
  }

  /**
   * Run the command handler and carry out whatever it asked for.
   *
   * <p>Also the entry point for a deferred value, which is why it is factored out of {@link
   * #createReceive()}: a {@link Defer} hands its value back here once the writes issued before it
   * have completed.
   */
  private void handleCommand(Object command) {
    // (fn [this command] ...) -> an event, a marker, or nil.
    runOp(commandHandler.invoke(this, command));
  }

  /**
   * Carry out one persist operation returned by a command handler.
   *
   * <p>No shape inspection: a returned value is a single event whatever its shape unless it is one
   * of the explicit markers, which removes the "vector of vectors" ambiguity a heuristic would
   * have. {@link PersistOps} nests, so operations compose in order.
   */
  private void runOp(Object op) {
    if (op == null) {
      // Nothing to persist.
      return;
    }
    if (op instanceof PersistOps) {
      for (ISeq s = ((PersistOps) op).ops; s != null; s = s.next()) {
        runOp(s.first());
      }
    } else if (op instanceof PersistAll) {
      // Multiple events (from persist-all) - one atomic journal write, applied in order.
      persistAllEvents(((PersistAll) op).events);
    } else if (op instanceof PersistAsync) {
      persistAsyncEvents(((PersistAsync) op).events);
    } else if (op instanceof Defer) {
      deferValue(((Defer) op).value);
    } else {
      // Any other value is a single event, whatever its shape.
      persistEvent(op);
    }
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
   * Persist a batch of events without stashing incoming commands.
   *
   * <p>{@code persistAllAsync} lets the actor keep processing commands while the write is in
   * flight — higher throughput, at the cost of the event handler (and therefore the state) lagging
   * behind the command that produced the event. Not atomic: unlike {@link
   * #persistAllEvents(ISeq)} these are ordinary async writes.
   */
  private void persistAsyncEvents(ISeq events) {
    List<Object> batch = new ArrayList<>();
    for (ISeq s = events; s != null; s = s.next()) {
      batch.add(withTags(s.first()));
    }
    if (batch.isEmpty()) return;
    persistAllAsync(batch, (Object e) -> handlePersistedEvent(e));
  }

  /**
   * Hand {@code value} back to the command handler once every persist issued before it has
   * completed. The value is never written to the journal, so it does not survive a restart and
   * never reaches the event handler; the sender is still in scope, which is what makes it the
   * place to reply from.
   */
  private void deferValue(Object value) {
    deferAsync(value, (Object v) -> handleCommand(v));
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

  // Timer methods (from the Timers trait) — same surface as CljActor's, so
  // pekko-clj.core's timer functions work inside a persistent actor too.

  public void startTimer(Object key, java.time.Duration interval, Object message) {
    timers().startTimerAtFixedRate(key, message, interval);
  }

  public void startTimerWithInitialDelay(Object key, java.time.Duration initialDelay,
                                         java.time.Duration interval, Object message) {
    timers().startTimerAtFixedRate(key, message, initialDelay, interval);
  }

  public void startSingleTimer(Object key, java.time.Duration delay, Object message) {
    timers().startSingleTimer(key, message, delay);
  }

  public void cancelTimer(Object key) {
    timers().cancel(key);
  }

  public boolean isTimerActive(Object key) {
    return timers().isTimerActive(key);
  }

  public void cancelAllTimers() {
    timers().cancelAll();
  }

  // DeathWatch, mirroring CljActor's.

  public void watch(ActorRef actorRef) {
    getContext().watch(actorRef);
  }

  public void unwatch(ActorRef actorRef) {
    getContext().unwatch(actorRef);
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
