package pekko_clj.actor;

import org.apache.pekko.actor.*;
import clojure.lang.IDeref;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import clojure.lang.Seqable;

public class CljActor extends UntypedAbstractActor implements IDeref {

  private Object state;
  private IFn function;

  public static Props create(Object initialState,
      IFn function) {
    return Props.create(
        CljActor.class,
        initialState,
        function);
  }

  public CljActor(Object initialState, IFn function) {
    this.state = initialState;
    this.function = function;
  }

  @Override
  public void onReceive(Object message) throws Throwable {
    Object result = function.invoke(this, message);

    if (result == null) {
      unhandled(message);
      return;
    }

    if (Seqable.class.isAssignableFrom(result.getClass())) {
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
}
