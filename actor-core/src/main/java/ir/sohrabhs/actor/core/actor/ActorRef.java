package ir.sohrabhs.actor.core.actor;

/**
 * A reference to an actor that can receive messages.
 *
 * DESIGN REASONING:
 * - This is the ONLY way to communicate with an actor (no direct method calls)
 * - tell() is fire-and-forget (async, non-blocking)
 * - No ask() in core — ask pattern is built on top via CompletableFuture in adapters
 * - The ref is serializable in concept (for future cluster support)
 *
 * Maps to: akka.actor.typed.ActorRef<T>
 *
 * @param <C> Command type this actor accepts
 */
public interface ActorRef<C> {

    /**
     * Send a message asynchronously. Fire-and-forget.
     * The message is enqueued in the actor's mailbox.
     */
    void tell(C message);

    /**
     * The path of the actor this ref points to.
     */
    ActorPath path();

    /**
     * The identity of the actor (if it's an entity actor).
     * May return null for non-entity actors.
     */
    ActorIdentity identity();
}