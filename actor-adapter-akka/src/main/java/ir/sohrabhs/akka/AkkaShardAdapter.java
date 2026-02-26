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
 * @param <C> Command type
 */
public final class AkkaShardAdapter<C> implements ShardRegion<C> {

    private final String typeName;
    private final ClusterSharding sharding;
    private final EntityTypeKey<C> typeKey;

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
                PersistentBehavior<C, ?, ?> ourBehavior = behaviorFactory.apply(entityId);
                return AkkaPersistenceBridge.toBehavior(ourBehavior);
            })
        );
    }

    @Override
    public void tell(String entityId, C message) {
        EntityRef<C> entityRef = sharding.entityRefFor(typeKey, entityId);
        entityRef.tell(message);
    }

    @Override
    public ActorRef<C> entityRefFor(String entityId) {
        EntityRef<C> entityRef = sharding.entityRefFor(typeKey, entityId);
        // Wrap in our ActorRef
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

    @Override
    public String typeName() {
        return typeName;
    }
}