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
    private final ConcurrentHashMap<String, LocalActorRef<C>> entities = new ConcurrentHashMap<>();

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
        return entities.computeIfAbsent(entityId, this::createEntityActor);
    }

    @Override
    public String typeName() {
        return typeName;
    }

    private LocalActorRef<C> createEntityActor(String entityId) {
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

        // Create persistent actor cell (handles recovery + command processing)
        LocalPersistentActorCell<C, E, S> cell = new LocalPersistentActorCell<>(
            ref, context, persistentBehavior, eventStore, snapshotStore, supervisionDecider
        );

        // Wire mailbox to cell
        mailbox.start(cell::processMessage);

        return ref;
    }
}