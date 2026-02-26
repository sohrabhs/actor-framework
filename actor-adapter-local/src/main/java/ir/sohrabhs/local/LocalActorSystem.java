package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;
import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;
import ir.sohrabhs.actor.core.system.ActorSystemConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local actor system implementation.
 * Suitable for Android, testing, and single-JVM deployments.
 */
public final class LocalActorSystem implements ActorSystem {

    private final ActorSystemConfig config;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ActorRef<?>> topLevelActors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ShardRegion<?>> shardRegions = new ConcurrentHashMap<>();

    public LocalActorSystem(ActorSystemConfig config) {
        this.config = config;
        // Fixed thread pool — reasonable for Android and local execution.
        // Can be configured via ActorSystemConfig in future.
        this.executor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
        );
    }

    public LocalActorSystem(ActorSystemConfig config, ExecutorService executor) {
        this.config = config;
        this.executor = executor;
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name) {
        return spawn(factory, name, config.defaultSupervision());
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name, SupervisionDecider decider) {
        ActorPath path = ActorPath.root().child(name);

        InMemoryMailbox<C> mailbox = new InMemoryMailbox<>(executor);
        LocalActorRef<C> ref = new LocalActorRef<>(path, null, mailbox);

        LocalActorContext<C> context = new LocalActorContext<>(
            ref, path, null, executor, decider
        );

        Behavior<C> behavior = factory.create(context);
        LocalActorCell<C> cell = new LocalActorCell<>(ref, context, behavior, decider);

        mailbox.start(cell::processMessage);
        topLevelActors.put(name, ref);

        return ref;
    }

    @Override
    public <C, E, S> ShardRegion<C> initShardRegion(
            String typeName,
            PersistentBehaviorFactory<C, E, S> behaviorFactory,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore) {

        LocalShardRegion<C, E, S> region = new LocalShardRegion<>(
            typeName, behaviorFactory, eventStore, snapshotStore,
            executor, config.defaultSupervision()
        );

        shardRegions.put(typeName, region);
        return region;
    }

    @Override
    public String name() {
        return config.systemName();
    }

    @Override
    public void terminate() {
        topLevelActors.values().forEach(ref -> {
            if (ref instanceof LocalActorRef) {
                ((LocalActorRef<?>) ref).mailbox().stop();
            }
        });
        executor.shutdown();
    }
}