// actor-adapter-local/src/main/java/com/actor/local/LocalActorContext.java
package io.github.sohrabhs.local;


import io.github.sohrabhs.actor.core.actor.*;
import io.github.sohrabhs.actor.core.persistence.EventStore;
import io.github.sohrabhs.actor.core.persistence.PersistentBehavior;
import io.github.sohrabhs.actor.core.persistence.SnapshotStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Local implementation of ActorContext.
 * Manages child actors and provides spawning capabilities.
 */
public final class LocalActorContext<C> implements ActorContext<C> {

    private final ActorRef<C> self;
    private final ActorPath path;
    private final ActorIdentity identity;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ActorRef<?>> children = new ConcurrentHashMap<>();
    private final SupervisionDecider supervisionDecider;
    private final int mailboxCapacity;

    public LocalActorContext(
            ActorRef<C> self,
            ActorPath path,
            ActorIdentity identity,
            ExecutorService executor,
            SupervisionDecider supervisionDecider,
            int mailboxCapacity) {
        this.self = self;
        this.path = path;
        this.identity = identity;
        this.executor = executor;
        this.supervisionDecider = supervisionDecider;
        this.mailboxCapacity = mailboxCapacity;
    }

    @Override
    public ActorRef<C> self() {
        return self;
    }

    @Override
    public ActorPath path() {
        return path;
    }

    @Override
    public ActorIdentity identity() {
        return identity;
    }

    @Override
    public synchronized <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName) {
        checkName(childName);
        ActorPath childPath = path.child(childName);

        InMemoryMailbox<M> mailbox = new InMemoryMailbox<>(executor, mailboxCapacity);
        LocalActorRef<M> childRef = new LocalActorRef<>(childPath, null, mailbox);

        LocalActorContext<M> childContext = new LocalActorContext<>(
            childRef, childPath, null, executor, supervisionDecider, mailboxCapacity
        );

        childRef.onStop(() -> { childContext.close(); children.remove(childName, childRef); });
        Behavior<M> behavior = factory.create(childContext);

        LocalActorCell<M> cell = new LocalActorCell<>(childRef, childContext, behavior, supervisionDecider, factory);
        children.put(childName, childRef);
        mailbox.start(cell::processMessage);
        return childRef;
    }

    /**
     * NEW METHOD: Spawn a persistent child actor.
     *
     * This method creates a full persistent actor as a child:
     * 1. Creates mailbox and actor reference
     * 2. Derives ActorIdentity from parent's path + childName
     * 3. Creates a LocalPersistentActorCell (handles recovery + persistence)
     * 4. Wires everything together
     *
     * CRITICAL DESIGN DECISION:
     * The child's persistenceId is derived from the parent's context:
     * Format: "ParentType-ParentId-ChildName"
     * This ensures:
     * - Unique persistence streams for each child
     * - Hierarchical organization in event store
     * - Easy debugging (you can see the full path in events)
     *
     * Example: If parent is VwapStrategy|STR-ETH-2026 and child is "twap-executor",
     * the child's persistenceId becomes: "VwapStrategy-STR-ETH-2026-twap-executor"
     */
    @Override
    public synchronized <M, E, S> ActorRef<M> spawnPersistent(
            PersistentBehaviorFactory<M, E, S> persistentBehaviorFactory,
            String childName,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore) {

        checkName(childName);
        // 1. Derive child identity from parent context
        String parentType = identity != null ? identity.typeName() : path.name();
        String parentId = identity != null ? identity.entityId() : path.name();
        ActorIdentity childIdentity = new ActorIdentity(
            parentType + "-Child",
            parentId + "-" + childName
        );
        ActorPath childPath = path.child(childName);

        // 2. Create mailbox and reference
        InMemoryMailbox<M> mailbox = new InMemoryMailbox<>(executor, mailboxCapacity);
        LocalActorRef<M> childRef = new LocalActorRef<>(childPath, childIdentity, mailbox);

        // 3. Create child context
        LocalActorContext<M> childContext = new LocalActorContext<>(
            childRef, childPath, childIdentity, executor, supervisionDecider, mailboxCapacity
        );

        childRef.onStop(() -> { childContext.close(); children.remove(childName, childRef); });
        // 4. Create persistent behavior using the factory
        PersistentBehavior<M, E, S> persistentBehavior = persistentBehaviorFactory.create(childName);

        // 5. Create persistent actor cell (this handles recovery automatically)
        LocalPersistentActorCell<M, E, S> cell = new LocalPersistentActorCell<>(
            childRef, childContext, persistentBehavior, eventStore, snapshotStore, supervisionDecider
        );

        // 6. Wire mailbox to cell
        mailbox.start(cell::processMessage);

        // 7. Track child
        children.put(childName, childRef);

        return childRef;
    }

    private boolean stopped;

    private void checkName(String name) {
        if (stopped) throw new IllegalStateException("Parent is stopped");
        if (name == null || name.isBlank() || name.contains("/")) throw new IllegalArgumentException("Invalid child name");
        if (children.containsKey(name)) throw new IllegalArgumentException("Duplicate child: " + name);
    }

    synchronized void stopChildren() {
        for (ActorRef<?> child : children.values().toArray(new ActorRef<?>[0])) {
            ((LocalActorRef<?>) child).stop();
        }
        children.clear();
    }

    synchronized void close() { stopped = true; stopChildren(); }

    @Override
    public synchronized void stop(ActorRef<?> child) {
        children.entrySet().removeIf(entry -> {
            if (entry.getValue() != child) return false;
            ((LocalActorRef<?>) child).stop();
            return true;
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public <M> ActorRef<M> getChild(String childName) {
        return (ActorRef<M>) children.get(childName);
    }

    @Override
    public void log(String message, Object... args) {
        String formatted = args.length > 0 ? String.format(message, args) : message;
        System.out.println("[" + path.toStringPath() + "] " + formatted);
    }
}
