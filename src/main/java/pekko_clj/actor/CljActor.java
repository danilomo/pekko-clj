package pekko_clj.actor;

import org.apache.pekko.actor.*;
import clojure.lang.RT;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import clojure.lang.ILookup;
import java.util.LinkedList;

public class CljActor extends UntypedAbstractActorWithTimers implements IDeref {

  private static final String NS = null;
  private static final Keyword STATE = RT.keyword(NS,"state");
  private static final Keyword FUNCTION = RT.keyword(NS,"function");
  private static final Keyword POST_RESTART = RT.keyword(NS,"post-restart");
  private static final Keyword POST_STOP = RT.keyword(NS,"post-stop");
  private static final Keyword PRE_RESTART = RT.keyword(NS,"pre-restart");
  private static final Keyword PRE_START = RT.keyword(NS,"pre-start");
  private static final Keyword SUPERVISOR_STRATEGY = RT.keyword(NS,"supervisor-strategy");
  private static final Keyword ERROR_HANDLER = RT.keyword(NS,"error-handler");

  private Object state;
  private IFn function;
  private IFn postRestart;
  private IFn postStop;
  private IFn preRestart;
  private IFn preStart;
  private SupervisorStrategy supervisorStrategy;
  private IFn errorHandler;

  // Stash support
  private static class StashedMessage {
    final Object message;
    final ActorRef sender;
    StashedMessage(Object message, ActorRef sender) {
      this.message = message;
      this.sender = sender;
    }
  }
  private final LinkedList<StashedMessage> stash = new LinkedList<>();
  private Object currentMessage;
  private ActorRef currentSender;

  public static Props create(ILookup props) {
    return Props.create(CljActor.class, () -> {
        CljActor actor = new CljActor();
        actor.state = props.valAt(STATE, null);
        actor.function = (IFn) props.valAt(FUNCTION);
        actor.postRestart = (IFn) props.valAt(POST_RESTART, null);
        actor.postStop = (IFn) props.valAt(POST_STOP, null);
        actor.preRestart = (IFn) props.valAt(PRE_RESTART, null);
        actor.preStart = (IFn) props.valAt(PRE_START, null);
        actor.supervisorStrategy = (SupervisorStrategy) props.valAt(SUPERVISOR_STRATEGY, null);
        actor.errorHandler = (IFn) props.valAt(ERROR_HANDLER, null);
        return actor;
      });
  }

  public static Props create(Object initialState,
                             IFn function) {
    return Props.create(
                        CljActor.class,
                        initialState,
                        function);
  }

  private CljActor() {
  }

  private CljActor(Object initialState, IFn function) {
    this.state = initialState;
    this.function = function;
  }  

  private static final Keyword TERMINATED = RT.keyword(NS, "terminated");

  @Override
  public void onReceive(Object message) throws Throwable {
    // Translate Terminated messages to [:terminated actor-ref]
    Object translatedMessage = message;
    if (message instanceof Terminated) {
      Terminated t = (Terminated) message;
      translatedMessage = PersistentVector.create(TERMINATED, t.getActor());
    }

    // Track current message and sender for stashing
    currentMessage = translatedMessage;
    currentSender = getSender();

    try {
      Object result = function.invoke(this, translatedMessage);
      handleState(result);
    } catch (Throwable t) {
      // on-error vs supervision contract:
      //  - Error and InterruptedException always propagate: they are never
      //    routed to on-error. Swallowing them would hide fatal failures
      //    (OutOfMemoryError, StackOverflowError) and break thread interruption.
      //  - Any other Throwable (i.e. a recoverable Exception): if an on-error
      //    handler is set it recovers the actor IN PLACE, so the parent's
      //    supervisor strategy never sees the failure; otherwise it is rethrown
      //    so supervision can decide (restart/resume/stop/escalate).
      if (t instanceof Error || t instanceof InterruptedException) {
        throw t;
      }
      if (errorHandler != null) {
        // on-error handler: (fn [this exception message] ...) -> new-state
        Object result = errorHandler.invoke(this, t, translatedMessage);
        handleState(result);
      } else {
        throw t;
      }
    } finally {
      currentMessage = null;
      currentSender = null;
    }
  }

  private void handleState(Object result) {
    if (result == null) {
      return;
    }

    // Behavior switching goes through BecomeResult only (see core/become). Any
    // other return value — including a PersistentVector — is the new state, so a
    // handler whose state legitimately is a vector is handled correctly.
    if (result instanceof BecomeResult) {
      BecomeResult b = (BecomeResult) result;
      this.function = b.function;
      this.state = b.state;
      return;
    }

    state = result;
  }

  @Override
  public Object deref() {
    return state;
  }

  @Override
  public void preStart() {
    if (preStart == null) {
      return;
    }

    Object initial = preStart.invoke(this);
    handleState(initial);
  }

  @Override
  public void postStop() {
    // Runs on stop, and (via the default preRestart) on restart. Powers the
    // defactor `on-stop` clause and the :post-stop prop.
    if (postStop != null) {
      postStop.invoke(this);
    }
    // Anything still stashed when the actor stops for good has nowhere to go.
    // Re-sending it to a stopped self routes it to dead letters, so the loss is
    // observable on the event stream instead of silent. On a *restart* this
    // finds an empty stash: preRestart drained it back into the mailbox first.
    drainStashToSelf();
  }

  @Override
  public void preRestart(Throwable reason, java.util.Optional<Object> message) throws Exception {
    // Runs on the OLD instance just before it is discarded by a supervised
    // restart. Powers the low-level :pre-restart prop. The default behaviour
    // (super) stops this actor's children and then calls postStop(), which
    // fires the on-stop hook — so on a restart both on-restart (new instance,
    // below) and on-stop (old instance) run. (We override the javadsl
    // Optional-based overload; the scala.Option one is deprecated.)
    if (preRestart != null) {
      preRestart.invoke(this, reason);
    }
    // Hand the stash to the new instance. Pekko's Stash contract is that a
    // restart does not swallow stashed messages: they go back to the mailbox,
    // which a restart keeps. Without this the fresh instance started with an
    // empty stash and the messages vanished silently. Draining after the
    // :pre-restart hook lets that hook drop them deliberately (clear-stash).
    drainStashToSelf();
    super.preRestart(reason, message);
  }

  /**
   * Re-send every stashed message to {@code self} with its original sender, in
   * stash order, emptying the stash.
   *
   * <p>From {@link #preRestart} the messages land in the mailbox the restart
   * keeps, so the fresh instance receives them (at the tail — see
   * {@link #unstashAll()} for the ordering note). From {@link #postStop} self is
   * already stopped, so they become dead letters.
   */
  private void drainStashToSelf() {
    while (!stash.isEmpty()) {
      StashedMessage msg = stash.removeFirst();
      getSelf().tell(msg.message, msg.sender);
    }
  }

  @Override
  public void postRestart(Throwable reason) throws Exception {
    // Runs on the FRESH instance after a supervised restart. The default (super)
    // calls preStart(), re-running init so state is rebuilt before the hook
    // runs. Powers the defactor `on-restart` clause and the :post-restart prop;
    // like a message handler, the hook's return value becomes the new state
    // (a nil return leaves it unchanged).
    super.postRestart(reason);
    if (postRestart != null) {
      handleState(postRestart.invoke(this, reason));
    }
  }

  @Override
  public SupervisorStrategy supervisorStrategy() {
    if (supervisorStrategy != null) {
      return supervisorStrategy;
    }
    return super.supervisorStrategy();
  }

  public ActorRef parentRef() {
    return getContext().getParent();
  }

  public ActorRef senderRef() {
    return getSender();
  }

  public ActorRef selfRef() {
    return getSelf();
  }

  public ActorRef spawn(IFn func, Object state) {
    return getContext()
      .actorOf(create(state, func));
  }

  public ActorRef spawn(ILookup props) {
    return getContext().actorOf(create(props));
  }

  public void tell(ActorRef ref, Object msg) {
    ref.tell(msg, getSelf());
  }

  public void forward(ActorRef ref, Object msg) {
    ref.tell(msg, getSender());	
  }

  public void reply(Object msg) {
    getSender().tell(msg, getSelf());
  }

  /**
   * Mark a message as unhandled. Delegates to Pekko's default handling, which
   * publishes an {@link org.apache.pekko.actor.UnhandledMessage} to the actor
   * system's event stream (and throws {@code DeathPactException} for an
   * unwatched {@code Terminated}). Used by the {@code defactor} catch-all so an
   * unmatched message does not crash the actor with a {@code MatchError}.
   */
  @Override
  public void unhandled(Object message) {
    super.unhandled(message);
  }

  public Scheduler scheduler() {
    return getContext().getSystem().scheduler();
  }

  public Cancellable scheduleOnce(java.time.Duration duration, Runnable runnable) {
    return scheduler().scheduleOnce(duration, runnable, getContext().getSystem().getDispatcher());
  }

  // Timer methods (from AbstractActorWithTimers)
  public void startTimer(Object key, java.time.Duration interval, Object message) {
    getTimers().startTimerAtFixedRate(key, message, interval);
  }

  public void startTimerWithInitialDelay(Object key, java.time.Duration initialDelay, java.time.Duration interval, Object message) {
    getTimers().startTimerAtFixedRate(key, message, initialDelay, interval);
  }

  public void startSingleTimer(Object key, java.time.Duration delay, Object message) {
    getTimers().startSingleTimer(key, message, delay);
  }

  public void cancelTimer(Object key) {
    getTimers().cancel(key);
  }

  public boolean isTimerActive(Object key) {
    return getTimers().isTimerActive(key);
  }

  public void cancelAllTimers() {
    getTimers().cancelAll();
  }

  // DeathWatch methods
  public void watch(ActorRef actorRef) {
    getContext().watch(actorRef);
  }

  // Like watch, but delivers `message` (as-is) instead of a Terminated when the
  // watched actor stops — Pekko's watchWith. The custom message flows through the
  // normal receive path, so it is NOT translated to [:terminated actor-ref].
  public void watchWith(ActorRef actorRef, Object message) {
    getContext().watchWith(actorRef, message);
  }

  public void unwatch(ActorRef actorRef) {
    getContext().unwatch(actorRef);
  }

  // Stash methods
  /**
   * Stash the current message for later processing.
   * Call this during message handling to defer processing.
   *
   * <p>Stashed messages outlive a supervised restart: {@link #preRestart} puts
   * them back in the mailbox for the fresh instance. When the actor stops for
   * good they become dead letters (see {@link #postStop}).
   */
  public void stash() {
    if (currentMessage == null) {
      throw new IllegalStateException("stash() can only be called during message handling");
    }
    stash.addLast(new StashedMessage(currentMessage, currentSender));
  }

  /**
   * Re-enqueue all stashed messages, in the order they were stashed (FIFO), each
   * with its original sender.
   *
   * <p>Note: unlike Pekko's {@code Stash} (which prepends to the mailbox front),
   * this re-sends the messages to {@code self}, so they land at the TAIL of the
   * mailbox — after any messages already queued. Equivalent for the common
   * stash-until-ready pattern; differs only when other messages queued up in
   * between and their relative ordering matters.
   */
  public void unstashAll() {
    drainStashToSelf();
  }

  /**
   * Re-enqueue the first stashed message only (with its original sender), placing
   * it at the tail of the mailbox (see {@link #unstashAll()} for the ordering note).
   */
  public void unstash() {
    if (!stash.isEmpty()) {
      StashedMessage msg = stash.removeFirst();
      getSelf().tell(msg.message, msg.sender);
    }
  }

  /**
   * Returns the number of stashed messages.
   */
  public int stashSize() {
    return stash.size();
  }

  /**
   * Clear all stashed messages without processing them.
   */
  public void clearStash() {
    stash.clear();
  }

}
