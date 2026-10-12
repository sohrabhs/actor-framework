package io.github.sohrabhs.local;

import io.github.sohrabhs.actor.core.actor.*;
import io.github.sohrabhs.actor.core.persistence.EventStore;
import io.github.sohrabhs.actor.core.persistence.SnapshotStore;
import io.github.sohrabhs.actor.core.shard.ShardRegion;
import io.github.sohrabhs.actor.core.system.ActorSystem;
import io.github.sohrabhs.actor.core.system.ActorSystemConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Local actor system implementation.
 * Suitable for Android, testing, and single-JVM deployments.
 */
public final class LocalActorSystem implements ActorSystem {

    private final ActorSystemConfig config;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ActorRef<?>> topLevelActors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ShardRegion<?>> shardRegions = new ConcurrentHashMap<>();

    public LocalActorSystem(ActorSystemConfig config, ExecutorService executor) {
        this.config = config;
        this.executor = executor;
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name) {
        return spawn(factory, name, config.defaultSupervision());
    }

    @Override
    public synchronized <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name, SupervisionDecider decider) {
        checkRunning();
        if (topLevelActors.containsKey(name)) throw new IllegalArgumentException("Duplicate actor: " + name);
        ActorPath path = ActorPath.root().child(name);

        InMemoryMailbox<C> mailbox = new InMemoryMailbox<>(executor, config.defaultMailboxCapacity());
        LocalActorRef<C> ref = new LocalActorRef<>(path, null, mailbox);

        LocalActorContext<C> context = new LocalActorContext<>(
            ref, path, null, executor, decider, config.defaultMailboxCapacity()
        );

        ref.onStop(() -> { context.close(); topLevelActors.remove(name, ref); });
        Behavior<C> behavior = factory.create(context);
        LocalActorCell<C> cell = new LocalActorCell<>(ref, context, behavior, decider, factory);

        topLevelActors.put(name, ref);
        mailbox.start(cell::processMessage);

        return ref;
    }

    @Override
    public synchronized <C, E, S> ShardRegion<C> initShardRegion(
            String typeName,
            PersistentBehaviorFactory<C, E, S> behaviorFactory,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore) {

        checkRunning();
        if (shardRegions.containsKey(typeName)) throw new IllegalArgumentException("Duplicate region: " + typeName);
        LocalShardRegion<C, E, S> region = new LocalShardRegion<>(
            typeName, behaviorFactory, eventStore, snapshotStore,
            executor, config.defaultSupervision(), config.defaultMailboxCapacity()
        );

        shardRegions.put(typeName, region);
        return region;
    }

    private boolean terminated;
    private void checkRunning() { if (terminated) throw new IllegalStateException("Actor system terminated"); }

    @Override
    public String name() {
        return config.systemName();
    }

    @Override
    public synchronized void terminate() {
        terminated = true;
        shardRegions.values().forEach(region -> ((LocalShardRegion<?, ?, ?>) region).close());
        shardRegions.clear();
        topLevelActors.values().forEach(ref -> {
            if (ref instanceof LocalActorRef) {
                ((LocalActorRef<?>) ref).stop();
            }
        });
        topLevelActors.clear();
        executor.shutdown();
    }
}
