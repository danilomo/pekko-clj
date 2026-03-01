package pekko_clj.actor;

import org.apache.pekko.actor.*;
import clojure.lang.RT;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import clojure.lang.Seqable;
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
      if (errorHandler != null) {
        // Call error handler: (fn [this exception message] ...) -> new-state
        Object result = errorHandler.invoke(this, t, translatedMessage);
        handleState(result);
      } else {
        // No error handler - rethrow to trigger supervision
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

    if (result instanceof BecomeResult) {
      BecomeResult b = (BecomeResult) result;
      this.function = b.function;
      this.state = b.state;
      return;
    }

    if (PersistentVector.class.isAssignableFrom(result.getClass())) {
      var seq = ((Seqable) result).seq();
      handleSeq(seq);
      return;
    }

    state = result;
  }

  private void handleSeq(ISeq seq) {
    function = (IFn) seq.first();
    state = seq.more().first();
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

  public void unwatch(ActorRef actorRef) {
    getContext().unwatch(actorRef);
  }

  // Stash methods
  /**
   * Stash the current message for later processing.
   * Call this during message handling to defer processing.
   */
  public void stash() {
    if (currentMessage == null) {
      throw new IllegalStateException("stash() can only be called during message handling");
    }
    stash.addLast(new StashedMessage(currentMessage, currentSender));
  }

  /**
   * Unstash all messages, prepending them to the mailbox.
   * Messages will be processed in the order they were stashed (FIFO).
   */
  public void unstashAll() {
    // Send messages in FIFO order
    while (!stash.isEmpty()) {
      StashedMessage msg = stash.removeFirst();
      getSelf().tell(msg.message, msg.sender);
    }
  }

  /**
   * Unstash the first stashed message only.
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
