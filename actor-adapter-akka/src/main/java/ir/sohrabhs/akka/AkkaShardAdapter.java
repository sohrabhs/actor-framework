package ir.sohrabhs.akka;

import akka.actor.typed.ActorSystem;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import ir.sohrabhs.actor.core.actor.ActorIdentity;
import ir.sohrabhs.actor.core.actor.ActorPath;
import ir.sohrabhs.actor.core.actor.ActorRef;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.shard.ShardRegion;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Adapts Akka Cluster Sharding to our ShardRegion interface.
 *
 * DESIGN REASONING:
 * This is the production adapter. When you migrate from local to cluster:
 * 1. Replace LocalActorSystem with AkkaActorSystemAdapter
 * 2. ShardRegion now uses Akka Cluster Sharding internally
 * 3. PersistentBehavior is bridged to Akka Persistence via AkkaPersistenceBridge
 * 4. NO CHANGES to domain or application code
 *
 * Stop behavior in Akka:
 * Akka Cluster Sharding manages entity lifecycle. When we "stop" an entity:
 * - We send a passivation signal
 * - Akka handles the actual stopping (may wait for in-flight messages)
 * - On next message, the entity is re-created and recovers from journal
 *
 * @param <C> Command type
 */
public final class AkkaShardAdapter<C> implements ShardRegion<C> {

    private final String typeName;
    private final ClusterSharding sharding;
    private final EntityTypeKey<C> typeKey;
    private final Set<String> knownEntities = ConcurrentHashMap.newKeySet();

    public AkkaShardAdapter(
            ActorSystem<?> akkaSystem,
            String typeName,
            Class<C> commandClass,
            Function<String, PersistentBehavior<C, ?, ?>> behaviorFactory) {

        this.typeName = typeName;
        this.sharding = ClusterSharding.get(akkaSystem);
        this.typeKey = EntityTypeKey.create(commandClass, typeName);

        // Initialize the shard region
        sharding.init(
                Entity.of(typeKey, entityContext -> {
                    String entityId = entityContext.getEntityId();
                    knownEntities.add(entityId);
                    PersistentBehavior<C, ?, ?> ourBehavior = behaviorFactory.apply(entityId);
                    return AkkaPersistenceBridge.toBehavior(ourBehavior);
                })
        );
    }

    @Override
    public void tell(String entityId, C message) {
        EntityRef<C> entityRef = sharding.entityRefFor(typeKey, entityId);
        entityRef.tell(message);
        knownEntities.add(entityId);
    }

    @Override
    public ActorRef<C> entityRefFor(String entityId) {
        EntityRef<C> entityRef = sharding.entityRefFor(typeKey, entityId);
        knownEntities.add(entityId);
        return new ActorRef<C>() {
            @Override
            public void tell(C message) {
                entityRef.tell(message);
            }

            @Override
            public ActorPath path() {
                return new ActorIdentity(typeName, entityId).toActorPath();
            }

            @Override
            public ActorIdentity identity() {
                return new ActorIdentity(typeName, entityId);
            }
        };
    }

    /**
     * Stop an entity in Akka Cluster Sharding.
     *
     * IMPLEMENTATION NOTE:
     * In Akka Cluster Sharding, you don't directly stop entities from outside.
     * Instead, entities passivate themselves. The recommended pattern is:
     * 1. Send a "Stop" command to the entity
     * 2. The entity's behavior handles it by returning Behaviors.stopped()
     *
     * Since we can't send arbitrary commands here (C is generic), we rely on
     * the domain implementing a stop command, or we use Akka's internal
     * passivation mechanism.
     *
     * For now, we track known entities and return false if not known.
     * In production, you'd typically use Effect.stop() from within the behavior.
     */
    @Override
    public boolean stop(String entityId) {
        // In Akka, the preferred way is for the entity to stop itself via Effect.stop()
        // which translates to Behaviors.stopped() in AkkaPersistenceBridge.
        //
        // External stop would require sending a typed message, which we can't do
        // generically. The application should define a StopCommand in their protocol.
        //
        // We remove from our tracking set.
        return knownEntities.remove(entityId);
    }

    @Override
    public int stopAll() {
        int count = knownEntities.size();
        knownEntities.clear();
        return count;
    }

    @Override
    public boolean isActive(String entityId) {
        // NOTE: This is an approximation. Akka doesn't expose real-time entity status
        // through a simple API. In production, you'd use Akka Cluster Sharding's
        // GetShardRegionState message for accurate status.
        return knownEntities.contains(entityId);
    }

    @Override
    public int activeEntityCount() {
        return knownEntities.size();
    }

    @Override
    public String typeName() {
        return typeName;
    }
}