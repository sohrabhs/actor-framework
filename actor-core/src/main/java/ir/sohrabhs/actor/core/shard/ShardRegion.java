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
     * The type name of entities in this shard region.
     */
    String typeName();
}