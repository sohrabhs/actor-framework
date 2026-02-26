package ir.sohrabhs.akka;

import akka.actor.typed.javadsl.Behaviors;
import ir.sohrabhs.actor.core.actor.ActorRef;
import ir.sohrabhs.actor.core.actor.BehaviorFactory;
import ir.sohrabhs.actor.core.actor.SupervisionDecider;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;
import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;
import ir.sohrabhs.actor.core.system.ActorSystemConfig;

/**
 * Akka Typed adapter for our ActorSystem interface.
 *
 * When you switch to this:
 * - Actors run on Akka's dispatcher
 * - Persistence uses Akka Persistence (Cassandra, JDBC, etc.)
 * - Sharding uses Akka Cluster Sharding
 * - Domain code: ZERO changes
 */
public final class AkkaActorSystemAdapter implements ActorSystem {

    private final akka.actor.typed.ActorSystem<Void> akkaSystem;
    private final ActorSystemConfig config;

    public AkkaActorSystemAdapter(ActorSystemConfig config) {
        this.config = config;
        this.akkaSystem = akka.actor.typed.ActorSystem.create(
            Behaviors.empty(),
            config.systemName()
        );
    }

    /** Use existing Akka system */
    public AkkaActorSystemAdapter(akka.actor.typed.ActorSystem<Void> akkaSystem, ActorSystemConfig config) {
        this.akkaSystem = akkaSystem;
        this.config = config;
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name) {
        akka.actor.typed.Behavior<C> akkaBehavior = AkkaBehaviorBridge.toBehavior(factory);
        akka.actor.typed.ActorRef<C> akkaRef = akkaSystem.systemActorOf(akkaBehavior, name, akka.actor.typed.Props.empty());
        return new AkkaActorRefAdapter<>(akkaRef);
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name, SupervisionDecider decider) {
        akka.actor.typed.Behavior<C> akkaBehavior = AkkaBehaviorBridge.toBehavior(factory);

        // Wrap with Akka supervision
        akka.actor.typed.Behavior<C> supervised = akka.actor.typed.javadsl.Behaviors.supervise(akkaBehavior)
            .onFailure(Exception.class, mapStrategy(decider));

        akka.actor.typed.ActorRef<C> akkaRef = akkaSystem.systemActorOf(supervised, name, akka.actor.typed.Props.empty());
        return new AkkaActorRefAdapter<>(akkaRef);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C, E, S> ShardRegion<C> initShardRegion(
            String typeName,
            PersistentBehaviorFactory<C, E, S> behaviorFactory,
            EventStore<E> eventStore,      // Ignored — Akka uses its own persistence
            SnapshotStore<S> snapshotStore) { // Ignored — Akka uses its own snapshot store

        // NOTE: eventStore and snapshotStore are ignored when using Akka.
        // Akka Persistence manages its own journal and snapshot store
        // configured via application.conf (Cassandra, JDBC, etc.)
        // This is intentional: the ports exist for local/Android adapters.

        return new AkkaShardAdapter<>(
            akkaSystem,
            typeName,
            (Class<C>) Object.class, // In real code, pass the actual class
            entityId -> (PersistentBehavior<C, Object, Object>)(PersistentBehavior) behaviorFactory.create(entityId)
        );
    }

    @Override
    public String name() {
        return config.systemName();
    }

    @Override
    public void terminate() {
        akkaSystem.terminate();
    }

    public akka.actor.typed.ActorSystem<Void> unwrap() {
        return akkaSystem;
    }

    private akka.actor.typed.SupervisorStrategy mapStrategy(SupervisionDecider decider) {
        // Simple mapping — in production, you'd check specific exception types
        return akka.actor.typed.SupervisorStrategy.restart();
    }
}