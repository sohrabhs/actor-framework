// actor-core/src/main/java/com/actor/core/actor/ActorContext.java
package ir.sohrabhs.actor.core.actor;

import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;

/**
 * Provides capabilities to an actor during message processing.
 *
 * DESIGN REASONING:
 * Mirrors Akka Typed's ActorContext<T>. Provides:
 * - Self reference (for replying, scheduling, etc.)
 * - Child spawning (parent-child hierarchy)
 * - Path and identity access
 * - Logging hook (adapter provides implementation)
 *
 * This is passed to Behavior.onMessage() and BehaviorFactory.create().
 *
 * Maps to: akka.actor.typed.javadsl.ActorContext<T>
 *
 * @param <C> Command type of the owning actor
 */
public interface ActorContext<C> {

    /**
     * Reference to self. Used for:
     * - Telling others how to reply to this actor
     * - Self-messaging (timers, delayed processing)
     */
    ActorRef<C> self();

    /**
     * The path of this actor.
     */
    ActorPath path();

    /**
     * The identity of this actor (if entity-based).
     */
    ActorIdentity identity();

    /**
     * Spawn a child actor with the given name and behavior factory.
     *
     * The child's path will be: this.path()/childName
     *
     * Maps to: context.spawn(behavior, name) in Akka Typed
     *
     * @param <M> The child's command type
     * @param factory The behavior factory for the child
     * @param childName Unique name within this actor's children
     * @return Reference to the newly spawned child
     */
    <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName);

    /**
     * Spawn a persistent (event-sourced) child actor.
     *
     * DESIGN REASONING:
     * This method allows parent actors to spawn children that are themselves
     * persistent. This is essential for hierarchical event-sourced systems
     * where a coordinator actor (e.g., VWAP) spawns sub-strategy actors (e.g., TWAP).
     *
     * The method requires explicit EventStore and SnapshotStore because:
     * 1. Child actors may need isolated persistence (separate streams)
     * 2. In production, different actors might use different databases
     * 3. The parent might be non-persistent while children are persistent
     *
     * Maps to: Akka's context.spawn(EventSourcedBehavior.create(...), name)
     *
     * @param <M> The child's command type
     * @param <E> The child's event type
     * @param <S> The child's state type
     * @param persistentBehaviorFactory Factory that creates the persistent behavior
     * @param childName Unique name within this actor's children
     * @param eventStore Event store for the child's events
     * @param snapshotStore Snapshot store for the child's state
     * @return Reference to the newly spawned persistent child
     */
    <M, E, S> ActorRef<M> spawnPersistent(
        PersistentBehaviorFactory<M, E, S> persistentBehaviorFactory,
        String childName,
        EventStore<E> eventStore,
        SnapshotStore<S> snapshotStore
    );

    /**
     * Factory for creating PersistentBehavior instances.
     * Takes childName as parameter so identity can be derived.
     */
    @FunctionalInterface
    interface PersistentBehaviorFactory<C, E, S> {
        PersistentBehavior<C, E, S> create(String childName);
    }

    /**
     * Stop a child actor.
     *
     * @param child The child reference to stop
     */
    void stop(ActorRef<?> child);

    /**
     * Get a child by name (if it exists).
     */
    <M> ActorRef<M> getChild(String childName);

    /**
     * Log a message. Adapter decides how (Android Log, SLF4J, etc.)
     */
    void log(String message, Object... args);
}
