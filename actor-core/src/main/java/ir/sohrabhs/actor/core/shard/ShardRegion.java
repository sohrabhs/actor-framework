package ir.sohrabhs.actor.core.shard;

import ir.sohrabhs.actor.core.actor.ActorRef;

/**
 * A logical shard region that routes messages to entity actors.
 *
 * DESIGN REASONING:
 * This is the core abstraction for entity routing. It mirrors Akka Cluster Sharding's
 * ClusterSharding.init(Entity.of(typeKey, ctx -> behavior)).
 *
 * Key properties:
 * - Entities are created lazily on first message
 * - Entity actors are identified by entityId
 * - Routing is transparent to the caller
 * - The same entityId always maps to the same actor (within the system's lifecycle)
 * - Entities can be stopped (passivated) and will be re-created on next message
 *
 * Current: all entities are local (logical sharding)
 * Future: adapter can route to remote shard coordinators
 *
 * Maps to: akka.cluster.sharding.typed.javadsl.ClusterSharding
 *
 * @param <C> Command type of the entity actors
 */
public interface ShardRegion<C> {

    /**
     * Send a message to an entity.
     * If the entity doesn't exist yet, it will be created.
     *
     * This is the primary API. Maps to:
     * - Akka: entityRef.tell(message)
     * - where entityRef = sharding.entityRefFor(typeKey, entityId)
     */
    void tell(String entityId, C message);

    /**
     * Get a reference to a specific entity.
     * The entity will be created lazily when it receives its first message.
     *
     * Maps to: ClusterSharding.entityRefFor(typeKey, entityId)
     */
    ActorRef<C> entityRefFor(String entityId);

    /**
     * Stop (passivate) an entity actor.
     *
     * The entity's mailbox is drained and stopped. The entity is removed
     * from the active entity registry. If a new message arrives for this
     * entityId after stopping, the entity will be re-created and will
     * recover its state from the event store (snapshot + replay).
     *
     * This mirrors Akka Cluster Sharding's passivation mechanism.
     * In Akka, entities can be passivated via:
     * - ClusterSharding.get(system).entityRefFor(typeKey, id) with Passivate message
     * - Idle timeout (automatic passivation)
     *
     * IMPORTANT: Stop is graceful. Messages already in the mailbox
     * will be processed before the actor stops (best effort).
     * Messages arriving after stop() is called will be buffered
     * until the entity is re-created on next tell().
     *
     * @param entityId The entity to stop
     * @return true if the entity was found and stopped, false if it wasn't active
     */
    boolean stop(String entityId);

    /**
     * Stop all active entity actors in this shard region.
     * Useful for graceful shutdown.
     *
     * @return the number of entities that were stopped
     */
    int stopAll();

    /**
     * Check if an entity is currently active (has been created and not stopped).
     *
     * @param entityId The entity to check
     * @return true if the entity actor is currently active
     */
    boolean isActive(String entityId);

    /**
     * Get the number of currently active entities.
     */
    int activeEntityCount();

    /**
     * The type name of entities in this shard region.
     */
    String typeName();
}