package pekko_clj.actor;

import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.serialization.SerializerWithStringManifest;
import com.typesafe.config.Config;
import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * A Pekko {@link SerializerWithStringManifest} that encodes Clojure data with
 * <a href="https://github.com/cognitect/transit-clj">Transit</a>. The actual encoding lives in
 * the {@code pekko-clj.serialization} namespace; this class is only the bridge Pekko can
 * instantiate (it needs a {@code (ExtendedActorSystem)} or no-arg constructor).
 *
 * <p>The system is threaded into the Transit handlers so {@code ActorRef}s embedded in a
 * message survive the round trip (serialized as a full remote path, resolved back through the
 * system's provider).
 *
 * <p>Configuration (see {@code pekko-clj.serialization/transit-config}, which generates it):
 * <pre>
 *   pekko.actor {
 *     serializers.transit = "pekko_clj.actor.CljTransitSerializer"
 *     serialization-bindings."clojure.lang.IPersistentCollection" = transit
 *     serialization-identifiers."pekko_clj.actor.CljTransitSerializer" = 9001
 *   }
 *   pekko-clj.serialization.transit.format = json   # or msgpack / json-verbose
 * </pre>
 *
 * <p>Transit is self-describing, so the manifest is a constant ({@value #MANIFEST}) rather than
 * a class name — the payload carries its own type information.
 */
public class CljTransitSerializer extends SerializerWithStringManifest {

  /** Manifest for every payload: Transit is self-describing. */
  public static final String MANIFEST = "clj";

  /** Identifier used when none is configured under {@code serialization-identifiers}. */
  public static final int DEFAULT_IDENTIFIER = 9001;

  /** Config path for the serializer identifier (Pekko's own convention). */
  private static final String IDENTIFIER_PATH =
      "pekko.actor.serialization-identifiers.\"pekko_clj.actor.CljTransitSerializer\"";

  /** Config path for the Transit format. */
  private static final String FORMAT_PATH = "pekko-clj.serialization.transit.format";

  private static final String DEFAULT_FORMAT = "json";

  private final ExtendedActorSystem system;
  private final int identifier;
  private final String format;
  private final IFn writeFn;
  private final IFn readFn;

  public CljTransitSerializer(ExtendedActorSystem system) {
    this.system = system;
    Config config = system.settings().config();
    this.identifier =
        config.hasPath(IDENTIFIER_PATH) ? config.getInt(IDENTIFIER_PATH) : DEFAULT_IDENTIFIER;
    this.format = config.hasPath(FORMAT_PATH) ? config.getString(FORMAT_PATH) : DEFAULT_FORMAT;
    // Ensure the namespace is loaded before resolving its vars.
    Clojure.var("clojure.core", "require").invoke(Clojure.read("pekko-clj.serialization"));
    this.writeFn = Clojure.var("pekko-clj.serialization", "write-bytes");
    this.readFn = Clojure.var("pekko-clj.serialization", "read-bytes");
  }

  @Override
  public int identifier() {
    return identifier;
  }

  @Override
  public String manifest(Object o) {
    return MANIFEST;
  }

  @Override
  public byte[] toBinary(Object o) {
    return (byte[]) writeFn.invoke(o, system, format);
  }

  @Override
  public Object fromBinary(byte[] bytes, String manifest) {
    return readFn.invoke(bytes, system, format);
  }
}
