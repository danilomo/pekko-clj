# Spec 1: Java Foundation

**Prerequisites**: None (do this first)
**Files to modify/create**:
- CREATE `src/main/java/pekko_clj/actor/BecomeResult.java`
- MODIFY `src/main/java/pekko_clj/actor/CljActor.java`

---

## 1.1 Create `BecomeResult.java`

New file at `src/main/java/pekko_clj/actor/BecomeResult.java`.

This is a simple value class used to signal that an actor wants to change its
behavior function AND state atomically (like Erlang's "become").

```java
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
```

## 1.2 Update `CljActor.handleState()` to support `BecomeResult`

In `CljActor.java`, modify `handleState` so it checks for `BecomeResult` **before**
the existing `PersistentVector` check:

```java
private void handleState(Object result) {
    if (result == null) {
        return;
    }

    // NEW: explicit become
    if (result instanceof BecomeResult) {
        BecomeResult b = (BecomeResult) result;
        this.function = b.function;
        this.state = b.state;
        return;
    }

    // existing vector-based become (keep for backward compat)
    if (PersistentVector.class.isAssignableFrom(result.getClass())) {
        var seq = ((Seqable) result).seq();
        handleSeq(seq);
        return;
    }

    state = result;
}
```

## 1.3 Add convenience methods to `CljActor`

Add these three methods to `CljActor.java`:

```java
public ActorRef parentRef() {
    return getContext().getParent();
}

public ActorRef senderRef() {
    return getSender();
}

public ActorRef selfRef() {
    return getSelf();
}
```

Note: named `parentRef`/`senderRef`/`selfRef` to avoid clashing with existing
Pekko methods.

## 1.4 Add `spawn(ILookup props)` overload to `CljActor`

Currently `spawn` only takes `(IFn func, Object state)`. Add an overload that
accepts a Clojure map (ILookup), reusing the existing `create(ILookup)` factory:

```java
public ActorRef spawn(ILookup props) {
    return getContext().actorOf(create(props));
}
```

## Verification

Run `lein compile` — all Java sources should compile without errors. No Clojure
changes needed for this spec.
