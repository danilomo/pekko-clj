package pekko_clj.actor;

import org.apache.pekko.actor.SupervisorStrategy;
import org.apache.pekko.actor.OneForOneStrategy;
import org.apache.pekko.actor.AllForOneStrategy;
import org.apache.pekko.japi.pf.DeciderBuilder;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.RT;
import scala.concurrent.duration.Duration;

/**
 * Factory for creating Pekko supervisor strategies from Clojure functions.
 */
public class CljSupervisorStrategy {

    private static final String NS = null;
    private static final Keyword RESUME = RT.keyword(NS, "resume");
    private static final Keyword RESTART = RT.keyword(NS, "restart");
    private static final Keyword STOP = RT.keyword(NS, "stop");
    private static final Keyword ESCALATE = RT.keyword(NS, "escalate");

    /**
     * Convert a Clojure keyword directive to a Pekko Directive.
     */
    private static SupervisorStrategy.Directive toDirective(Object result) {
        if (RESUME.equals(result)) {
            return SupervisorStrategy.resume();
        } else if (RESTART.equals(result)) {
            return SupervisorStrategy.restart();
        } else if (STOP.equals(result)) {
            return SupervisorStrategy.stop();
        } else if (ESCALATE.equals(result)) {
            return SupervisorStrategy.escalate();
        } else {
            // Default to escalate for unknown directives
            return SupervisorStrategy.escalate();
        }
    }

    /**
     * Create a OneForOneStrategy from a Clojure decider function.
     *
     * @param maxRetries Maximum number of retries within the time window (-1 for infinite)
     * @param withinMillis Time window in milliseconds (-1 for infinite)
     * @param decider A Clojure function (fn [throwable] :directive)
     * @return A OneForOneStrategy
     */
    public static SupervisorStrategy oneForOne(int maxRetries, long withinMillis, IFn decider) {
        Duration duration = withinMillis < 0
            ? Duration.Inf()
            : Duration.create(withinMillis, "milliseconds");

        return new OneForOneStrategy(
            maxRetries,
            duration,
            DeciderBuilder
                .match(Throwable.class, t -> toDirective(decider.invoke(t)))
                .build()
        );
    }

    /**
     * Create an AllForOneStrategy from a Clojure decider function.
     *
     * @param maxRetries Maximum number of retries within the time window (-1 for infinite)
     * @param withinMillis Time window in milliseconds (-1 for infinite)
     * @param decider A Clojure function (fn [throwable] :directive)
     * @return An AllForOneStrategy
     */
    public static SupervisorStrategy allForOne(int maxRetries, long withinMillis, IFn decider) {
        Duration duration = withinMillis < 0
            ? Duration.Inf()
            : Duration.create(withinMillis, "milliseconds");

        return new AllForOneStrategy(
            maxRetries,
            duration,
            DeciderBuilder
                .match(Throwable.class, t -> toDirective(decider.invoke(t)))
                .build()
        );
    }

    /**
     * Create a simple OneForOneStrategy that always restarts.
     */
    public static SupervisorStrategy defaultStrategy() {
        return SupervisorStrategy.defaultStrategy();
    }
}
