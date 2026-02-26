package ir.sohrabhs.actor.core.actor;

/**
 * The fundamental abstraction for actor behavior.
 *
 * DESIGN REASONING:
 * In Akka Typed, a Behavior<T> is a function: (ActorContext<T>, T) → Behavior<T>.
 * We mirror this exactly but without any Akka dependency.
 *
 * Why functional-style?
 * - Immutable behavior transitions (no mutable state in behavior itself)
 * - State is encoded in the *next* Behavior returned
 * - This maps 1:1 to Akka Typed's Behaviors.receive()
 *
 * The returned Behavior becomes the actor's new behavior for the next message.
 * Returning Behaviors.same() means "keep current behavior".
 * Returning Behaviors.stopped() means "stop this actor".
 *
 * @param <C> Command type (the message type this actor handles)
 */
@FunctionalInterface
public interface Behavior<C> {

    /**
     * Called for each incoming message.
     *
     * @param context provides access to actor capabilities (spawning children, self ref, etc.)
     * @param command the incoming message
     * @return the next behavior (could be same, new state, or stopped)
     */
    Behavior<C> onMessage(ActorContext<C> context, C command);
}