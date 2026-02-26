package ir.sohrabhs.actor.core.actor;

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