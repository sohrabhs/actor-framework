package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.persistence.Effect;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;

/**
 * OPTIONAL EXTENDED INTERFACE:
 * Behaviors that need access to ActorContext can implement this interface.
 *
 * This is a clean architectural pattern that:
 * 1. Keeps the core PersistentBehavior interface pure
 * 2. Allows opt-in context access for behaviors that need it
 * 3. Doesn't require storing context in state
 */
public interface PersistentBehaviorWithContext<C, E, S> extends PersistentBehavior<C, E, S> {
    /**
     * Command handler with explicit context access.
     * Override this instead of onCommand() when you need context capabilities.
     */
    Effect<E, S> onCommandWithContext(ActorContext<C> context, S state, C command);

    /**
     * Default implementation delegates to context-aware version.
     */
    @Override
    default Effect<E, S> onCommand(S state, C command) {
        throw new UnsupportedOperationException(
                "This behavior uses onCommandWithContext - context must be provided by runtime"
        );
    }
}