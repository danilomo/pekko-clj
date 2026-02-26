package pekko_clj.actor;

import clojure.lang.IFn;

public class BecomeResult {
    public final IFn function;
    public final Object state;

    private BecomeResult(IFn function, Object state) {
        this.function = function;
        this.state = state;
    }

    public static BecomeResult of(IFn function, Object state) {
        return new BecomeResult(function, state);
    }
}
