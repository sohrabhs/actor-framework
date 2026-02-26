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
    private final boolean noReply;

    private Effect(List<E> events, Function<S, Void> sideEffect, boolean snapshot) {
        this.events = Collections.unmodifiableList(events);
        this.sideEffect = sideEffect;
        this.snapshot = snapshot;
        this.noReply = false;
    }

    private Effect(boolean noReply) {
        this.events = Collections.emptyList();
        this.sideEffect = null;
        this.snapshot = false;
        this.noReply = noReply;
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

    public List<E> events() { return events; }
    public Function<S, Void> sideEffect() { return sideEffect; }
    public boolean shouldSnapshot() { return snapshot; }
    public boolean isUnhandled() { return noReply; }

    /**
     * Builder for fluent Effect construction.
     */
    public static final class EffectBuilder<E, S> {
        private final List<E> events;
        private Function<S, Void> sideEffect;
        private boolean snapshot = false;

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

        public Effect<E, S> build() {
            return new Effect<>(events, sideEffect, snapshot);
        }
    }
}