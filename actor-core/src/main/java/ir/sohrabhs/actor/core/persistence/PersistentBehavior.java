package ir.sohrabhs.actor.core.persistence;


import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.actor.ActorIdentity;

/**
 * Defines an event-sourced actor's behavior.
 *
 * DESIGN REASONING:
 * In Akka Typed, EventSourcedBehavior<Command, Event, State> defines:
 * - emptyState(): the initial state
 * - commandHandler(state, command): returns Effect
 * - eventHandler(state, event): returns new state
 *
 * We mirror this exactly. The adapter wraps this into the actual
 * actor implementation:
 * - Akka adapter: wraps into EventSourcedBehavior
 * - Local adapter: wraps into a stateful Behavior that calls
 *   EventStore/SnapshotStore directly
 *
 * WHY SEPARATE FROM Behavior:
 * A PersistentBehavior is NOT a Behavior. It's a higher-level contract.
 * The adapter converts it into a Behavior by adding persistence machinery.
 * This is exactly how Akka Typed works: EventSourcedBehavior produces a Behavior<Command>.
 *
 * @param <C> Command type (messages this actor receives)
 * @param <E> Event type (facts that are persisted)
 * @param <S> State type (current state built from events)
 */
public interface PersistentBehavior<C, E, S> {

    /**
     * The identity of this persistent actor.
     * Used to derive persistenceId for event storage.
     */
    ActorIdentity identity();

    /**
     * The initial state when no events have been replayed.
     */
    S emptyState();

    /**
     * Handle a command given the current state.
     * Returns an Effect describing what events to persist.
     *
     * MUST BE PURE (no side effects during command handling).
     * Side effects go into Effect.thenRun().
     */
    Effect<E, S> onCommand(S state, C command);

    /**
     * Apply an event to the state, producing a new state.
     *
     * MUST BE PURE. No side effects. No exceptions.
     * This is called during:
     * - Recovery (replaying stored events)
     * - After persisting new events
     */
    S onEvent(S state, E event);

    /**
     * How often to snapshot. Returns the number of events between snapshots.
     * Return 0 to disable automatic snapshotting.
     * Default: every 100 events.
     */
    default int snapshotEvery() {
        return 100;
    }

    /**
     * Called after recovery is complete.
     * Useful for logging or initializing timers.
     */
    default void onRecoveryComplete(ActorContext<?> context, S state) {
        // default: no-op
    }
}