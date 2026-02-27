// actor-adapter-akka/src/main/java/com/actor/akka/AkkaActorContextAdapter.java
package ir.sohrabhs.akka;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;

/**
 * Adapts Akka's ActorContext to our ActorContext interface.
 */
public final class AkkaActorContextAdapter<C> implements ActorContext<C> {

    private final akka.actor.typed.javadsl.ActorContext<C> akkaCtx;

    public AkkaActorContextAdapter(akka.actor.typed.javadsl.ActorContext<C> akkaCtx) {
        this.akkaCtx = akkaCtx;
    }

    @Override
    public ActorRef<C> self() {
        return new AkkaActorRefAdapter<>(akkaCtx.getSelf());
    }

    @Override
    public ActorPath path() {
        return ActorPath.of(akkaCtx.getSelf().path().toString());
    }

    @Override
    public ActorIdentity identity() {
        // For shard entities, this would be set differently
        String name = akkaCtx.getSelf().path().name();
        String parent = akkaCtx.getSelf().path().parent().name();
        return new ActorIdentity(parent, name);
    }

    @Override
    public <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName) {
        akka.actor.typed.Behavior<M> akkaBehavior = AkkaBehaviorBridge.toBehavior(factory);
        akka.actor.typed.ActorRef<M> akkaRef = akkaCtx.spawn(akkaBehavior, childName);
        return new AkkaActorRefAdapter<>(akkaRef);
    }

    /**
     * Spawn a persistent child actor in Akka.
     *
     * CRITICAL IMPLEMENTATION NOTE:
     * When using Akka Persistence, the EventStore and SnapshotStore parameters
     * are IGNORED because Akka manages its own persistence plugins configured
     * in application.conf.
     *
     * This is intentional - the ports exist for:
     * 1. LocalActorSystem (which needs explicit stores)
     * 2. Testing (where you want in-memory stores)
     * 3. Alternative adapters (Orleans, custom implementations)
     *
     * For Akka, we delegate to Akka's persistence mechanism which reads
     * configuration from application.conf:
     * - akka.persistence.journal.plugin = "jdbc-journal" (or cassandra, etc.)
     * - akka.persistence.snapshot-store.plugin = "jdbc-snapshot-store"
     */
    @Override
    public <M, E, S> ActorRef<M> spawnPersistent(
            PersistentBehaviorFactory<M, E, S> persistentBehaviorFactory,
            String childName,
            EventStore<E> eventStore,      // Ignored in Akka adapter
            SnapshotStore<S> snapshotStore) { // Ignored in Akka adapter

        // Create our PersistentBehavior
        PersistentBehavior<M, E, S> ourBehavior = persistentBehaviorFactory.create(childName);

        // Bridge to Akka EventSourcedBehavior
        akka.actor.typed.Behavior<M> akkaBehavior = AkkaPersistenceBridge.toBehavior(ourBehavior);

        // Spawn the actor in Akka
        akka.actor.typed.ActorRef<M> akkaRef = akkaCtx.spawn(akkaBehavior, childName);

        return new AkkaActorRefAdapter<>(akkaRef);
    }

    @Override
    public void stop(ActorRef<?> child) {
        if (child instanceof AkkaActorRefAdapter) {
            akkaCtx.stop(((AkkaActorRefAdapter<?>) child).unwrap());
        }
    }

    @Override
    public <M> ActorRef<M> getChild(String childName) {
        // Akka Typed doesn't have direct child lookup by name in the public API.
        // In practice, you'd track children in a Map within the behavior.
        return null;
    }

    @Override
    public void log(String message, Object... args) {
        akkaCtx.getLog().info(String.format(message, args));
    }
}
