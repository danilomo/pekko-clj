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

public class CljActor extends UntypedAbstractActor implements IDeref {

  private static final String NS = null;
  private static final Keyword STATE = RT.keyword(NS,"state");
  private static final Keyword FUNCTION = RT.keyword(NS,"function");
  private static final Keyword POST_RESTART = RT.keyword(NS,"post-restart");
  private static final Keyword POST_STOP = RT.keyword(NS,"post-stop");
  private static final Keyword PRE_RESTART = RT.keyword(NS,"pre-restart");
  private static final Keyword PRE_START = RT.keyword(NS,"pre-start");
  private static final Keyword SUPERVISOR_STRATEGY = RT.keyword(NS,"supervisor-strategy");
  
  private Object state;  
  private IFn function;
  private IFn postRestart;
  private IFn postStop;
  private IFn preRestart;
  private IFn preStart;

  public static Props create(ILookup props) {    
    return Props.create(CljActor.class, () -> {
        CljActor actor = new CljActor();
        actor.state = props.valAt(STATE, null);
        actor.function = (IFn) props.valAt(FUNCTION);
        actor.postRestart = (IFn) props.valAt(POST_RESTART, null);
        actor.postStop = (IFn) props.valAt(POST_STOP, null);
        actor.preRestart = (IFn) props.valAt(PRE_RESTART, null);
        actor.preStart = (IFn) props.valAt(PRE_START, null);
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

  @Override
  public void onReceive(Object message) throws Throwable {
    Object result = function.invoke(this, message);
    
    handleState(result);
  }

  private void handleState(Object result) {
    if (result == null) {
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
}
