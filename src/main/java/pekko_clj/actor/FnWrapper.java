package pekko_clj.actor;

import scala.Function1;
import clojure.lang.IFn;

public class FnWrapper implements Function1 {

  public static FnWrapper create(IFn func) {
    return new FnWrapper(func);
  }
  
  private final IFn func;
  
  private FnWrapper(IFn func) {
    this.func = func;
  }
  
  @Override public Object apply(Object obj) {
    return func.invoke(obj);
  }
}
