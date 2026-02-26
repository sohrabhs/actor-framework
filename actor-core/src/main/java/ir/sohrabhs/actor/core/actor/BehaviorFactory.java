package ir.sohrabhs.actor.core.actor;

/**
 * Factory that creates the initial Behavior given an ActorContext.
 *
 * WHY THIS EXISTS:
 * In Akka Typed, Behaviors.setup(ctx -> ...) provides the context at creation time.
 * We need the same: defer behavior creation until the actor is actually spawned
 * and has a real context.
 *
 * The adapter calls create(context) when spawning, just like Akka calls
 * the setup function when creating the actor.
 *
 * @param <C> Command type
 */
public interface BehaviorFactory<C> {
    Behavior<C> create(ActorContext<C> context);
}