package ir.sohrabhs.actor.core.system;


import ir.sohrabhs.actor.core.actor.ActorRef;
import ir.sohrabhs.actor.core.actor.BehaviorFactory;
import ir.sohrabhs.actor.core.actor.SupervisionDecider;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;
import ir.sohrabhs.actor.core.shard.ShardRegion;

/**
 * The top-level entry point for the actor system.
 *
 * DESIGN REASONING:
 * This is the outermost port. The application creates an ActorSystem via an adapter.
 * The system provides:
 * - Actor spawning
 * - Shard region creation
 * - Persistence integration
 * - Lifecycle management
 *
 * Maps to: akka.actor.typed.ActorSystem
 */
public interface ActorSystem {

    /**
     * Spawn a top-level actor.
     *
     * Maps to: ActorSystem.systemActorOf or guardian actor spawning children
     */
    <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name);

    /**
     * Spawn a top-level actor with supervision.
     */
    <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name, SupervisionDecider decider);

    /**
     * Initialize a shard region for entity actors.
     *
     * DESIGN REASONING:
     * This mirrors Akka's ClusterSharding.init(Entity.of(typeKey, createBehavior)).
     * The PersistentBehaviorFactory creates PersistentBehavior instances per entityId.
     *
     * @param typeName Logical type name (e.g., "Counter")
     * @param behaviorFactory Creates PersistentBehavior for each entity
     * @param <C> Command type
     * @param <E> Event type
     * @param <S> State type
     * @return A ShardRegion that routes messages to entity actors
     */
    <C, E, S> ShardRegion<C> initShardRegion(
        String typeName,
        PersistentBehaviorFactory<C, E, S> behaviorFactory,
        EventStore<E> eventStore,
        SnapshotStore<S> snapshotStore
    );

    /**
     * The system name.
     */
    String name();

    /**
     * Shut down the actor system and all actors.
     */
    void terminate();

    /**
     * Factory for creating PersistentBehavior instances per entity.
     */
    @FunctionalInterface
    interface PersistentBehaviorFactory<C, E, S> {
        PersistentBehavior<C, E, S> create(String entityId);
    }
}