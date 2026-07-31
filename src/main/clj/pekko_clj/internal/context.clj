(ns pekko-clj.internal.context
  "One tiny shared dynamic var so `pekko-clj.core/!` can resolve the right sender
   from inside a persistent (`defactor-persistent`) or delivery
   (`defactor-delivery`) actor, without those actor classes sharing a common
   interface with `CljActor` (deliberately avoided; see the N11 note). Their
   command / event / on-stop binders bind this to the actor's self ref. A classic
   `defactor` does not need it — it binds `pekko-clj.core/*current-actor*`, which
   `!` uses directly (fast path) — so `*current-self*` is nil there.

   This namespace has no pekko-clj dependencies, so `core`, `persistence` and
   `persistence.delivery` can all require it without a cycle.")

(def ^{:dynamic true :tag org.apache.pekko.actor.ActorRef} *current-self*
  "The self `ActorRef` of the current persistent/delivery actor during command,
   event or on-stop handling, or nil otherwise. Read by `pekko-clj.core/!` as the
   sender fallback when `pekko-clj.core/*current-actor*` is unbound — which is the
   case inside `defactor-persistent` / `defactor-delivery` bodies, where `!` (and
   `sharding/tell`, which routes through it) would otherwise send as noSender and
   silently break the reply path."
  nil)
