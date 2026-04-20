# Adding Actor Stop Capability

## تحلیل مشکل

الان سیستم شما قابلیت stop کردن اکتور از طریق `ActorContext.stop(child)` را دارد، اما:

1. **ShardRegion** هیچ متد `stop` ندارد
2. اکتورهای entity که از طریق shard ساخته شدند قابل stop نیستند از بیرون
3. مکانیزم **Passivation** (مثل Akka) وجود ندارد
4. اکتور نمی‌تواند خودش را stop کند از داخل `PersistentBehavior`

## طراحی راه‌حل

```
 Stop Mechanisms:
 
 1. External Stop (via ShardRegion)
    ┌──────────┐     stop("entity-42")     ┌──────────────┐
    │  Client  │ ──────────────────────────▶│ ShardRegion  │
    └──────────┘                            │ .stop(id)    │
                                            └──────┬───────┘
                                                   │
                                            removes from registry
                                            stops mailbox
                                            
 2. Self-Stop (Passivation from inside behavior)
    ┌───────────────┐    Effect.stop()     ┌──────────────┐
    │ onCommand()   │ ───────────────────▶ │ Cell detects │
    │ returns       │                      │ stop effect  │
    │ Effect.stop() │                      │ stops actor  │
    └───────────────┘                      └──────────────┘
    
 3. Poison Pill (send special message)
    ┌──────────┐   PoisonPill via tell()   ┌──────────────┐
    │  Client  │ ──────────────────────────▶│ Mailbox      │
    └──────────┘                            │ processes    │
                                            │ stops actor  │
                                            └──────────────┘
```

## تغییرات فایل‌ها

### 1. `ShardRegion.java` - اضافه کردن stop

```java
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
```

### 2. `Effect.java` - اضافه کردن stop effect

```java
package ir.sohrabhs.actor.core.persistence;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Represents a persistence effect: what should happen after processing a command.
 *
 * DESIGN REASONING:
 * In Akka Typed Persistence, commandHandler returns an Effect<Event, State>.
 * The Effect describes:
 * - What events to persist
 * - What to do after persistence succeeds (side effects, reply, etc.)
 * - Whether to snapshot
 * - Whether to stop the actor (passivation)
 *
 * We mirror this exactly. The adapter interprets the Effect:
 * - Akka adapter: maps to Akka's Effect API
 * - Local adapter: persists to EventStore directly, then applies callbacks
 *
 * This is NOT a behavior — it's a description of what should happen.
 * The runtime (adapter) executes it.
 *
 * @param <E> Event type
 * @param <S> State type
 */
public final class Effect<E, S> {

    private final List<E> events;
    private final Function<S, Void> sideEffect;
    private final boolean snapshot;
    private final boolean unhandled;
    private final boolean stop;

    private Effect(List<E> events, Function<S, Void> sideEffect, boolean snapshot, boolean stop) {
        this.events = Collections.unmodifiableList(events);
        this.sideEffect = sideEffect;
        this.snapshot = snapshot;
        this.unhandled = false;
        this.stop = stop;
    }

    private Effect(boolean unhandled) {
        this.events = Collections.emptyList();
        this.sideEffect = null;
        this.snapshot = false;
        this.unhandled = unhandled;
        this.stop = false;
    }

    /**
     * Persist a single event.
     */
    public static <E, S> EffectBuilder<E, S> persist(E event) {
        return new EffectBuilder<>(Collections.singletonList(Objects.requireNonNull(event)));
    }

    /**
     * Persist multiple events (atomically).
     */
    public static <E, S> EffectBuilder<E, S> persistAll(List<E> events) {
        return new EffectBuilder<>(events);
    }

    /**
     * No events to persist, just run side effects.
     * Useful for read-only commands like GetValue.
     */
    public static <E, S> EffectBuilder<E, S> none() {
        return new EffectBuilder<>(Collections.emptyList());
    }

    /**
     * Unhandled command.
     */
    @SuppressWarnings("unchecked")
    public static <E, S> Effect<E, S> unhandled() {
        return new Effect<>(true);
    }

    /**
     * Stop the actor after processing.
     * Optionally persist events before stopping.
     *
     * This is the self-stop / passivation mechanism.
     * In Akka, this maps to Effect().stop().
     *
     * Usage in PersistentBehavior.onCommand():
     *   return Effect.stop();                              // just stop
     *   return Effect.persist(event).thenStop().build();   // persist then stop
     *
     * When the actor is part of a ShardRegion, stopping means passivation:
     * the actor will be re-created and recover from events on next message.
     */
    public static <E, S> EffectBuilder<E, S> stop() {
        return new EffectBuilder<E, S>(Collections.emptyList()).thenStop();
    }

    public List<E> events() { return events; }
    public Function<S, Void> sideEffect() { return sideEffect; }
    public boolean shouldSnapshot() { return snapshot; }
    public boolean isUnhandled() { return unhandled; }

    /**
     * Whether the actor should stop after this effect is processed.
     */
    public boolean shouldStop() { return stop; }

    /**
     * Builder for fluent Effect construction.
     */
    public static final class EffectBuilder<E, S> {
        private final List<E> events;
        private Function<S, Void> sideEffect;
        private boolean snapshot = false;
        private boolean stop = false;

        EffectBuilder(List<E> events) {
            this.events = events;
        }

        /**
         * Run a side effect after events are persisted.
         * The side effect receives the updated state.
         *
         * Example: reply to a caller, update a read model, etc.
         */
        public EffectBuilder<E, S> thenRun(Function<S, Void> sideEffect) {
            this.sideEffect = sideEffect;
            return this;
        }

        /**
         * Take a snapshot after persisting these events.
         */
        public EffectBuilder<E, S> thenSnapshot() {
            this.snapshot = true;
            return this;
        }

        /**
         * Stop the actor after processing this effect.
         *
         * This can be combined with persist:
         *   Effect.persist(event).thenStop().build()
         *
         * Or used alone:
         *   Effect.stop().build()
         *
         * When combined with thenRun, the side effect runs before stopping:
         *   Effect.persist(event).thenRun(s -> reply(s)).thenStop().build()
         */
        public EffectBuilder<E, S> thenStop() {
            this.stop = true;
            return this;
        }

        public Effect<E, S> build() {
            return new Effect<>(events, sideEffect, snapshot, stop);
        }
    }
}
```

### 3. `LocalShardRegion.java` - پیاده‌سازی stop

```java
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
```

### 4. `LocalPersistentActorCell.java` - پشتیبانی از Effect.stop()

```java
package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.*;

import java.util.List;
import java.util.Optional;

/**
 * Runtime cell for a persistent (event-sourced) actor.
 *
 * DESIGN REASONING:
 * This is where the magic happens. This cell:
 * 1. On creation: recovers state from EventStore/SnapshotStore
 * 2. On message: calls PersistentBehavior.onCommand → gets Effect
 * 3. Interprets the Effect: persists events, updates state, runs side effects
 * 4. Handles snapshotting based on PersistentBehavior.snapshotEvery()
 * 5. Handles stop/passivation when Effect.shouldStop() is true
 *
 * KEY ARCHITECTURAL DECISION:
 * We do NOT store context in state (that breaks immutability).
 * Instead, we wrap the behavior's onCommand call with context injection.
 * This is how Akka Typed works internally.
 */
final class LocalPersistentActorCell<C, E, S> {

    private final ActorContext<C> context;
    private final PersistentBehavior<C, E, S> behavior;
    private final EventStore<E> eventStore;
    private final SnapshotStore<S> snapshotStore;
    private final String persistenceId;
    private final LocalActorRef<C> self;
    private final SupervisionDecider supervisionDecider;
    private final Runnable onSelfStop;

    private S currentState;
    private long sequenceNumber;
    private long eventsSinceSnapshot;

    /**
     * Constructor with self-stop callback.
     *
     * @param onSelfStop Called when the actor stops itself via Effect.stop().
     *                   The ShardRegion uses this to remove the entity from its registry.
     *                   For non-shard actors, this can be a no-op.
     */
    LocalPersistentActorCell(
            LocalActorRef<C> self,
            ActorContext<C> context,
            PersistentBehavior<C, E, S> behavior,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore,
            SupervisionDecider supervisionDecider,
            Runnable onSelfStop) {
        this.self = self;
        this.context = context;
        this.behavior = behavior;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.persistenceId = behavior.identity().persistenceId();
        this.supervisionDecider = supervisionDecider;
        this.onSelfStop = onSelfStop != null ? onSelfStop : () -> {};

        recover();
    }

    /**
     * Backward-compatible constructor without self-stop callback.
     */
    LocalPersistentActorCell(
            LocalActorRef<C> self,
            ActorContext<C> context,
            PersistentBehavior<C, E, S> behavior,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore,
            SupervisionDecider supervisionDecider) {
        this(self, context, behavior, eventStore, snapshotStore, supervisionDecider, null);
    }

    /**
     * Recovery: load snapshot + replay events.
     */
    private void recover() {
        currentState = behavior.emptyState();
        sequenceNumber = 0;
        eventsSinceSnapshot = 0;

        // Step 1: Try to load latest snapshot
        Optional<PersistedSnapshot<S>> snapshot = snapshotStore.loadLatest(persistenceId);
        if (snapshot.isPresent()) {
            PersistedSnapshot<S> snap = snapshot.get();
            currentState = snap.state();
            sequenceNumber = snap.sequenceNumber();
            context.log("Recovered snapshot at seqNr %d", sequenceNumber);
        }

        // Step 2: Replay events after snapshot
        List<PersistedEvent<E>> events = eventStore.loadEvents(persistenceId, sequenceNumber);
        for (PersistedEvent<E> persisted : events) {
            currentState = behavior.onEvent(currentState, persisted.event());
            sequenceNumber = persisted.sequenceNumber();
        }

        if (!events.isEmpty()) {
            context.log("Replayed %d events, seqNr now %d", events.size(), sequenceNumber);
        }

        // Step 3: Notify recovery complete
        behavior.onRecoveryComplete(context, currentState);
        context.log("Recovery complete. State: %s", currentState);
    }

    /**
     * Process a command message.
     */
    void processMessage(C command) {
        try {
            // Wrap behavior to inject context
            ContextualPersistentBehavior<C, E, S> contextualBehavior =
                    new ContextualPersistentBehavior<>(behavior, context);

            // Call command handler with context-aware wrapper
            Effect<E, S> effect = contextualBehavior.onCommand(currentState, command);

            if (effect.isUnhandled()) {
                context.log("Unhandled command: %s", command);
                return;
            }

            // Persist events
            for (E event : effect.events()) {
                sequenceNumber++;
                eventStore.persist(persistenceId, sequenceNumber, event);
                currentState = behavior.onEvent(currentState, event);
                eventsSinceSnapshot++;
            }

            // Check if we should snapshot
            boolean shouldSnapshot = effect.shouldSnapshot();
            if (!shouldSnapshot && behavior.snapshotEvery() > 0
                    && eventsSinceSnapshot >= behavior.snapshotEvery()) {
                shouldSnapshot = true;
            }

            if (shouldSnapshot && sequenceNumber > 0) {
                snapshotStore.save(persistenceId, sequenceNumber, currentState);
                eventsSinceSnapshot = 0;
                context.log("Snapshot saved at seqNr %d", sequenceNumber);
            }

            // Run side effects BEFORE stopping
            if (effect.sideEffect() != null) {
                effect.sideEffect().apply(currentState);
            }

            // Handle stop/passivation
            if (effect.shouldStop()) {
                context.log("Actor stopping via Effect.stop() (passivation)");
                self.mailbox().stop();
                onSelfStop.run();
            }

        } catch (Exception e) {
            handleFailure(e, command);
        }
    }

    private void handleFailure(Exception e, C command) {
        SupervisionStrategy strategy = supervisionDecider.decide(e);
        switch (strategy) {
            case RESTART:
                context.log("Persistent actor restarting due to: %s. Re-recovering...", e.getMessage());
                recover();
                break;
            case STOP:
                context.log("Persistent actor stopping due to: %s", e.getMessage());
                self.mailbox().stop();
                onSelfStop.run();
                break;
            case RESUME:
                context.log("Persistent actor resuming after: %s", e.getMessage());
                break;
            case ESCALATE:
                throw new RuntimeException("Escalated from persistent actor " + self.path(), e);
        }
    }

    /**
     * Wrapper that injects ActorContext into PersistentBehavior.
     */
    private static final class ContextualPersistentBehavior<C, E, S>
            implements PersistentBehavior<C, E, S> {

        private final PersistentBehavior<C, E, S> delegate;
        private final ActorContext<C> context;

        ContextualPersistentBehavior(PersistentBehavior<C, E, S> delegate, ActorContext<C> context) {
            this.delegate = delegate;
            this.context = context;
        }

        @Override
        public ActorIdentity identity() {
            return delegate.identity();
        }

        @Override
        public S emptyState() {
            return delegate.emptyState();
        }

        @Override
        public Effect<E, S> onCommand(S state, C command) {
            if (delegate instanceof PersistentBehaviorWithContext) {
                @SuppressWarnings("unchecked")
                PersistentBehaviorWithContext<C, E, S> contextAware =
                        (PersistentBehaviorWithContext<C, E, S>) delegate;
                return contextAware.onCommandWithContext(context, state, command);
            }
            return delegate.onCommand(state, command);
        }

        @Override
        public S onEvent(S state, E event) {
            return delegate.onEvent(state, event);
        }

        @Override
        public int snapshotEvery() {
            return delegate.snapshotEvery();
        }

        @Override
        public void onRecoveryComplete(ActorContext<?> context, S state) {
            delegate.onRecoveryComplete(context, state);
        }
    }
}
```

### 5. `AkkaShardAdapter.java` - پیاده‌سازی stop برای Akka

```java
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
```

### 6. `AkkaPersistenceBridge.java` - پشتیبانی از Effect.stop()

```java
package ir.sohrabhs.akka;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;
import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;

/**
 * Bridges our PersistentBehavior to Akka Typed Persistence's EventSourcedBehavior.
 *
 * DESIGN REASONING:
 * Akka Typed Persistence has EventSourcedBehavior with:
 * - emptyState()
 * - commandHandler() → returns akka Effect
 * - eventHandler() → returns new State
 *
 * Our PersistentBehavior has the exact same structure.
 * This bridge translates:
 * - Our Effect → Akka's Effect
 * - Our onCommand → Akka's commandHandler
 * - Our onEvent → Akka's eventHandler
 * - Our Effect.stop() → Akka's Effect().stop() (passivation)
 *
 * The domain code stays completely unaware of Akka.
 *
 * @param <C> Command
 * @param <E> Event
 * @param <S> State
 */
public final class AkkaPersistenceBridge {

    private AkkaPersistenceBridge() {}

    /**
     * Convert our PersistentBehavior to Akka's EventSourcedBehavior.
     */
    public static <C, E, S> Behavior<C> toBehavior(PersistentBehavior<C, E, S> ourBehavior) {
        return Behaviors.setup(akkaCtx -> {
            ActorContext<C> ourContext = new AkkaActorContextAdapter<>(akkaCtx);

            PersistenceId persistenceId = PersistenceId.ofUniqueId(
                ourBehavior.identity().persistenceId()
            );

            return new EventSourcedBehavior<C, E, S>(persistenceId) {

                @Override
                public S emptyState() {
                    return ourBehavior.emptyState();
                }

                @Override
                public CommandHandler<C, E, S> commandHandler() {
                    return newCommandHandlerBuilder()
                        .forAnyState()
                            .onAnyCommand((state, command) -> translateEffect(
                                            ourBehavior.onCommand(state, command),
                                            state, Effect()
                                    )
                            );
                }

                @Override
                public EventHandler<S, E> eventHandler() {
                    return newEventHandlerBuilder()
                        .forAnyState()
                        .onAnyEvent((state, event) -> ourBehavior.onEvent(state, event));
                }

                @Override
                public SignalHandler<S> signalHandler() {
                    return newSignalHandlerBuilder()
                            .onSignal(
                                    akka.persistence.typed.RecoveryCompleted.instance(),
                                    state -> {
                                        ourBehavior.onRecoveryComplete(ourContext, state);
                                    }
                            )
                            .build();
                }

                @Override
                public boolean shouldSnapshot(S state, E event, long sequenceNr) {
                    int interval = ourBehavior.snapshotEvery();
                    return interval > 0 && sequenceNr % interval == 0;
                }
            };
        });
    }

    /**
     * Translate our Effect to Akka's Effect.
     *
     * Now handles Effect.shouldStop() by mapping to Akka's Effect().stop().
     */
    private static <C, E, S> akka.persistence.typed.javadsl.Effect<E, S> translateEffect(
            ir.sohrabhs.actor.core.persistence.Effect<E, S> ourEffect,
            S currentState,
            EffectFactories<E, S> akkaEffectFactory) {

        if (ourEffect.isUnhandled()) {
            return akkaEffectFactory.unhandled();
        }

        // Build the base effect (persist or none)
        akka.persistence.typed.javadsl.EffectBuilder<E, S> baseEffect;

        if (ourEffect.events().isEmpty()) {
            baseEffect = akkaEffectFactory.none();
        } else if (ourEffect.events().size() == 1) {
            baseEffect = akkaEffectFactory.persist(ourEffect.events().get(0));
        } else {
            baseEffect = akkaEffectFactory.persist(ourEffect.events());
        }

        // Apply side effects
        if (ourEffect.sideEffect() != null) {
            baseEffect = (akka.persistence.typed.javadsl.EffectBuilder<E, S>)
                    baseEffect.thenRun(newState -> {
                        ourEffect.sideEffect().apply(newState);
                    });
        }

        // Apply stop if requested
        if (ourEffect.shouldStop()) {
            return baseEffect.thenStop();
        }

        return baseEffect.thenNoReply();
    }
}
```

### 7. `CounterCommand.java` - اضافه کردن Stop command

```java
package ir.sohrabhs.example.domain;

/**
 * Commands for the Counter actor.
 * These are pure domain objects. No actor framework dependency.
 */
public interface CounterCommand {

    final class Increment implements CounterCommand {
        private final int amount;

        public Increment(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Increment{" + amount + "}";
        }
    }

    final class Decrement implements CounterCommand {
        private final int amount;

        public Decrement(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Decrement{" + amount + "}";
        }
    }

    /**
     * GetValue uses a callback pattern (not ask pattern) to stay framework-agnostic.
     * The replyTo is a simple functional interface.
     */
    final class GetValue implements CounterCommand {
        private final ValueConsumer replyTo;

        public GetValue(ValueConsumer replyTo) {
            this.replyTo = replyTo;
        }

        public ValueConsumer replyTo() { return replyTo; }

        @Override
        public String toString() {
            return "GetValue{}";
        }
    }

    /**
     * Request the actor to stop (passivate).
     *
     * DESIGN REASONING:
     * In Akka Cluster Sharding, the recommended way to stop an entity is
     * to send it a message that the entity handles by returning Effect.stop().
     * This ensures:
     * - The stop is processed in order with other messages
     * - Any pending messages before Stop are processed first
     * - Side effects can run before stopping (e.g., saving final state)
     * - The entity can persist a final event if needed
     *
     * This is cleaner than "killing" the actor externally because:
     * 1. No race conditions with in-flight messages
     * 2. The actor can clean up resources
     * 3. The actor can decide whether to actually stop (e.g., reject if busy)
     */
    final class Stop implements CounterCommand {
        public static final Stop INSTANCE = new Stop();

        private Stop() {}

        @Override
        public String toString() {
            return "Stop{}";
        }
    }

    @FunctionalInterface
    interface ValueConsumer {
        void accept(int value);
    }
}
```

### 8. `CounterBehaviorFactory.java` - handle Stop command

```java
package ir.sohrabhs.example.actor;

import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.actor.ActorIdentity;
import ir.sohrabhs.actor.core.persistence.Effect;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.example.domain.CounterCommand;
import ir.sohrabhs.example.domain.CounterEvent;
import ir.sohrabhs.example.domain.CounterState;

/**
 * Defines the Counter entity's persistent behavior.
 *
 * THIS IS THE KEY FILE: Notice there is ZERO dependency on any actor framework.
 * No Akka imports. No local adapter imports. Pure core abstractions.
 *
 * This same class works with:
 * - LocalActorSystem (for Android/testing)
 * - AkkaActorSystem (for production cluster)
 * - Any future adapter
 *
 * Maps to: Akka Typed EventSourcedBehavior<CounterCommand, CounterEvent, CounterState>
 */
public final class CounterBehaviorFactory {

    private final int snapshotInterval;

    public CounterBehaviorFactory() {
        this(5); // snapshot every 5 events for demo
    }

    public CounterBehaviorFactory(int snapshotInterval) {
        this.snapshotInterval = snapshotInterval;
    }

    /**
     * Creates a PersistentBehavior for a specific counter entity.
     *
     * This is the factory method called by ShardRegion for each entityId.
     * Maps to: Entity.of(typeKey, ctx -> EventSourcedBehavior.create(...))
     */
    public PersistentBehavior<CounterCommand, CounterEvent, CounterState> create(String entityId) {
        return new CounterPersistentBehavior(entityId, snapshotInterval);
    }

    /**
     * The actual PersistentBehavior implementation.
     */
    private static final class CounterPersistentBehavior
            implements PersistentBehavior<CounterCommand, CounterEvent, CounterState> {

        private final ActorIdentity identity;
        private final int snapshotInterval;

        CounterPersistentBehavior(String entityId, int snapshotInterval) {
            this.identity = new ActorIdentity("Counter", entityId);
            this.snapshotInterval = snapshotInterval;
        }

        @Override
        public ActorIdentity identity() {
            return identity;
        }

        @Override
        public CounterState emptyState() {
            return CounterState.empty();
        }

        /**
         * Command handler: pure function (state, command) → Effect
         *
         * Now handles Stop command by returning Effect.stop().
         * The runtime (local or Akka) will:
         * 1. Process any side effects
         * 2. Stop the actor's mailbox
         * 3. Remove the entity from the shard region
         * 4. On next message, the entity is re-created and recovers
         */
        @Override
        public Effect<CounterEvent, CounterState> onCommand(CounterState state, CounterCommand command) {
            if (command instanceof CounterCommand.Increment) {
                CounterCommand.Increment inc = (CounterCommand.Increment) command;
                return Effect.<CounterEvent, CounterState>persist(
                    new CounterEvent.Incremented(inc.amount())
                ).build();
            }

            if (command instanceof CounterCommand.Decrement) {
                CounterCommand.Decrement dec = (CounterCommand.Decrement) command;
                return Effect.<CounterEvent, CounterState>persist(
                    new CounterEvent.Decremented(dec.amount())
                ).build();
            }

            if (command instanceof CounterCommand.GetValue) {
                CounterCommand.GetValue get = (CounterCommand.GetValue) command;
                return Effect.<CounterEvent, CounterState>none()
                    .thenRun(s -> {
                        get.replyTo().accept(s.value());
                        return null;
                    })
                    .build();
            }

            if (command instanceof CounterCommand.Stop) {
                // Graceful stop: run any cleanup, then stop the actor
                return Effect.<CounterEvent, CounterState>stop()
                    .thenRun(s -> {
                        System.out.println("[Counter " + identity.entityId()
                            + "] Stopping with final value: " + s.value());
                        return null;
                    })
                    .build();
            }

            return Effect.unhandled();
        }

        /**
         * Event handler: pure function (state, event) → new state
         */
        @Override
        public CounterState onEvent(CounterState state, CounterEvent event) {
            if (event instanceof CounterEvent.Incremented) {
                return state.withValue(state.value() + ((CounterEvent.Incremented) event).amount());
            }
            if (event instanceof CounterEvent.Decremented) {
                return state.withValue(state.value() - ((CounterEvent.Decremented) event).amount());
            }
            return state;
        }

        @Override
        public int snapshotEvery() {
            return snapshotInterval;
        }

        @Override
        public void onRecoveryComplete(ActorContext<?> context, CounterState state) {
            context.log("Counter '%s' recovery complete. Current value: %d",
                identity.entityId(), state.value());
        }
    }
}
```

### 9. `MainWithLocal.java` - دمو با stop و recovery

```java
package ir.sohrabhs.example;

import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;
import ir.sohrabhs.actor.core.system.ActorSystemConfig;
import ir.sohrabhs.example.actor.CounterBehaviorFactory;
import ir.sohrabhs.example.domain.CounterCommand;
import ir.sohrabhs.example.domain.CounterEvent;
import ir.sohrabhs.example.domain.CounterState;
import ir.sohrabhs.local.InMemoryEventStore;
import ir.sohrabhs.local.InMemorySnapshotStore;
import ir.sohrabhs.local.LocalActorSystem;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the Counter actor running on the local adapter.
 * Now includes stop, passivation, and recovery demonstration.
 */
public class MainWithLocal {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Actor Framework Demo: Local Adapter ===\n");

        // --- Infrastructure Setup ---

        ActorSystemConfig config = ActorSystemConfig.builder("actor-system")
            .mailboxCapacity(1000)
            .build();

        ActorSystem system = new LocalActorSystem(config, Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors())
        ));

        InMemoryEventStore<CounterEvent> eventStore = new InMemoryEventStore<>();
        InMemorySnapshotStore<CounterState> snapshotStore = new InMemorySnapshotStore<>();

        CounterBehaviorFactory counterFactory = new CounterBehaviorFactory(5);

        ShardRegion<CounterCommand> counterShard = system.initShardRegion(
            "Counter",
            counterFactory::create,
            eventStore,
            snapshotStore
        );

        // =====================================================
        // PHASE 1: Normal operations
        // =====================================================

        System.out.println("--- Phase 1: Normal operations ---");

        counterShard.tell("42", new CounterCommand.Increment(10));
        counterShard.tell("42", new CounterCommand.Increment(5));
        counterShard.tell("42", new CounterCommand.Decrement(3));
        counterShard.tell("99", new CounterCommand.Increment(100));

        Thread.sleep(500);

        CompletableFuture<Integer> value42 = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(value42::complete));

        CompletableFuture<Integer> value99 = new CompletableFuture<>();
        counterShard.tell("99", new CounterCommand.GetValue(value99::complete));

        System.out.println("Counter 42 value: " + value42.get(2, TimeUnit.SECONDS));  // 12
        System.out.println("Counter 99 value: " + value99.get(2, TimeUnit.SECONDS));  // 100
        System.out.println("Active entities: " + counterShard.activeEntityCount());     // 2
        System.out.println("Counter 42 active: " + counterShard.isActive("42"));       // true

        // =====================================================
        // PHASE 2: Stop via command (self-passivation)
        // =====================================================

        System.out.println("\n--- Phase 2: Stop counter-42 via Stop command ---");

        counterShard.tell("42", CounterCommand.Stop.INSTANCE);
        Thread.sleep(500);

        System.out.println("Counter 42 active after stop: " + counterShard.isActive("42")); // false
        System.out.println("Active entities after stop: " + counterShard.activeEntityCount()); // 1

        // =====================================================
        // PHASE 3: Recovery after stop
        // =====================================================

        System.out.println("\n--- Phase 3: Send message to stopped counter-42 (triggers recovery) ---");

        counterShard.tell("42", new CounterCommand.Increment(100));
        Thread.sleep(500);

        CompletableFuture<Integer> recoveredValue = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(recoveredValue::complete));

        System.out.println("Counter 42 value after recovery + increment: "
            + recoveredValue.get(2, TimeUnit.SECONDS)); // 112 (12 + 100)
        System.out.println("Counter 42 active after recovery: " + counterShard.isActive("42")); // true

        // =====================================================
        // PHASE 4: External stop (via shard region)
        // =====================================================

        System.out.println("\n--- Phase 4: External stop of counter-99 ---");

        boolean stopped = counterShard.stop("99");
        System.out.println("Counter 99 stopped: " + stopped);  // true
        System.out.println("Active entities: " + counterShard.activeEntityCount()); // 1

        // Recovery again
        counterShard.tell("99", new CounterCommand.Increment(50));
        Thread.sleep(500);

        CompletableFuture<Integer> recovered99 = new CompletableFuture<>();
        counterShard.tell("99", new CounterCommand.GetValue(recovered99::complete));
        System.out.println("Counter 99 value after recovery + increment: "
            + recovered99.get(2, TimeUnit.SECONDS)); // 150

        // =====================================================
        // PHASE 5: Stop all
        // =====================================================

        System.out.println("\n--- Phase 5: Stop all entities ---");

        System.out.println("Active before stopAll: " + counterShard.activeEntityCount());
        int stoppedCount = counterShard.stopAll();
        System.out.println("Stopped " + stoppedCount + " entities");
        System.out.println("Active after stopAll: " + counterShard.activeEntityCount()); // 0

        // =====================================================
        // PHASE 6: Verify events are still in store
        // =====================================================

        System.out.println("\n--- Phase 6: Event store inspection ---");
        System.out.println("Events for Counter|42: " + eventStore.allEvents("Counter|42"));
        System.out.println("Events for Counter|99: " + eventStore.allEvents("Counter|99"));
        System.out.println("Highest seqNr for Counter|42: " + eventStore.highestSequenceNumber("Counter|42"));
        System.out.println("Highest seqNr for Counter|99: " + eventStore.highestSequenceNumber("Counter|99"));

        // --- Cleanup ---
        System.out.println("\n--- Shutting down ---");
        system.terminate();
        System.out.println("Done.");
    }
}
```

### 10. `ActorSystem.java` - بدون تغییر (فقط اطمینان از سازگاری)

فایل `ActorSystem.java` تغییری نمی‌خواهد چون `initShardRegion` قبلاً `ShardRegion<C>` برمی‌گرداند و متدهای جدید stop در interface `ShardRegion` اضافه شدند.

---

## خلاصه تغییرات

```
┌─────────────────────────────────┬────────────────────────────────────────┐
│ File                            │ Change                                 │
├─────────────────────────────────┼────────────────────────────────────────┤
│ ShardRegion.java                │ +stop(id), +stopAll(), +isActive(),    │
│                                 │ +activeEntityCount()                   │
├─────────────────────────────────┼────────────────────────────────────────┤
│ Effect.java                     │ +shouldStop(), +stop(), +thenStop()    │
├─────────────────────────────────┼────────────────────────────────────────┤
│ LocalShardRegion.java           │ Implement stop methods,                │
│                                 │ onEntitySelfStop callback              │
├─────────────────────────────────┼────────────────────────────────────────┤
│ LocalPersistentActorCell.java   │ Handle Effect.shouldStop(),            │
│                                 │ Runnable onSelfStop callback           │
├─────────────────────────────────┼────────────────────────────────────────┤
│ AkkaShardAdapter.java           │ Implement stop methods,                │
│                                 │ track known entities                   │
├─────────────────────────────────┼────────────────────────────────────────┤
│ AkkaPersistenceBridge.java      │ Map Effect.shouldStop → thenStop()     │
├─────────────────────────────────┼────────────────────────────────────────┤
│ CounterCommand.java             │ +Stop command                          │
├─────────────────────────────────┼────────────────────────────────────────┤
│ CounterBehaviorFactory.java     │ Handle Stop → Effect.stop()            │
├─────────────────────────────────┼────────────────────────────────────────┤
│ MainWithLocal.java              │ Demo stop, recovery, stopAll           │
└─────────────────────────────────┴────────────────────────────────────────┘
```