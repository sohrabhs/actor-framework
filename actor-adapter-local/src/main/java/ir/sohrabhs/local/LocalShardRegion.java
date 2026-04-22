package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.*;
import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Local shard region: routes messages to entity actors on this JVM.
 *
 * DESIGN REASONING:
 * This is a "logical" shard region. It provides the same API as
 * Akka Cluster Sharding but everything runs locally.
 *
 * Key behaviors that match Akka:
 * - Lazy creation: actors are created on first message
 * - Deterministic routing: same entityId → same actor
 * - Each entity has its own mailbox (single-threaded processing)
 * - Entity actors are persistent (event-sourced)
 * - Entities can be stopped (passivated) and re-created on demand
 *
 * Stop/Passivation behavior:
 * When an entity is stopped (via stop(entityId) or Effect.stop()):
 * 1. The entity's mailbox is stopped (no more message processing)
 * 2. The entity is removed from the active entity registry
 * 3. If a new message arrives for this entityId, the entity is re-created
 * 4. On re-creation, the entity recovers from snapshot + events (full recovery)
 * 5. This is exactly how Akka Cluster Sharding passivation works
 *
 * Migration to Akka Cluster Sharding:
 * Replace LocalShardRegion with AkkaShardAdapter.
 * The PersistentBehavior stays the same. The routing becomes distributed.
 */
public final class LocalShardRegion<C, E, S> implements ShardRegion<C> {

    private final String typeName;
    private final ActorSystem.PersistentBehaviorFactory<C, E, S> behaviorFactory;
    private final EventStore<E> eventStore;
    private final SnapshotStore<S> snapshotStore;
    private final ExecutorService executor;
    private final SupervisionDecider supervisionDecider;
    private final ConcurrentHashMap<String, EntityEntry<C>> entities = new ConcurrentHashMap<>();

    public LocalShardRegion(
            String typeName,
            ActorSystem.PersistentBehaviorFactory<C, E, S> behaviorFactory,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore,
            ExecutorService executor,
            SupervisionDecider supervisionDecider) {
        this.typeName = typeName;
        this.behaviorFactory = behaviorFactory;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.executor = executor;
        this.supervisionDecider = supervisionDecider;
    }

    @Override
    public void tell(String entityId, C message) {
        ActorRef<C> ref = entityRefFor(entityId);
        ref.tell(message);
    }

    @Override
    public ActorRef<C> entityRefFor(String entityId) {
        EntityEntry<C> entry = entities.computeIfAbsent(entityId, this::createEntityActor);
        return entry.ref;
    }

    @Override
    public boolean stop(String entityId) {
        EntityEntry<C> removed = entities.remove(entityId);
        if (removed != null) {
            removed.ref.mailbox().stop();
            return true;
        }
        return false;
    }

    @Override
    public int stopAll() {
        int count = entities.size();
        // Take a snapshot of keys to avoid concurrent modification
        for (String entityId : entities.keySet().toArray(new String[0])) {
            stop(entityId);
        }
        return count;
    }

    @Override
    public boolean isActive(String entityId) {
        return entities.containsKey(entityId);
    }

    @Override
    public int activeEntityCount() {
        return entities.size();
    }

    @Override
    public String typeName() {
        return typeName;
    }

    /**
     * Called by LocalPersistentActorCell when an Effect.stop() is processed.
     * This is the internal passivation path.
     */
    void onEntitySelfStop(String entityId) {
        entities.remove(entityId);
    }

    private EntityEntry<C> createEntityActor(String entityId) {
        ActorIdentity identity = new ActorIdentity(typeName, entityId);
        ActorPath actorPath = identity.toActorPath();

        // Create mailbox
        InMemoryMailbox<C> mailbox = new InMemoryMailbox<>(executor);
        LocalActorRef<C> ref = new LocalActorRef<>(actorPath, identity, mailbox);

        // Create context
        LocalActorContext<C> context = new LocalActorContext<>(
                ref, actorPath, identity, executor, supervisionDecider
        );

        // Create persistent behavior
        PersistentBehavior<C, E, S> persistentBehavior = behaviorFactory.create(entityId);

        // Create persistent actor cell with shard region callback for self-stop
        LocalPersistentActorCell<C, E, S> cell = new LocalPersistentActorCell<>(
                ref, context, persistentBehavior, eventStore, snapshotStore,
                supervisionDecider, () -> onEntitySelfStop(entityId)
        );

        // Wire mailbox to cell
        mailbox.start(cell::processMessage);

        return new EntityEntry<>(ref);
    }

    /**
     * Holds the actor ref for an entity.
     * Could be extended with metadata (creation time, last message time, etc.)
     * for idle-timeout passivation in the future.
     */
    private static final class EntityEntry<C> {
        final LocalActorRef<C> ref;

        EntityEntry(LocalActorRef<C> ref) {
            this.ref = ref;
        }
    }
}