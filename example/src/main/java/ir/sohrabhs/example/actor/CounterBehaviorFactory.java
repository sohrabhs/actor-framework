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
         * DESIGN REASONING:
         * We use if/else instead of visitor pattern for simplicity
         * (this is Java, not Scala). In real code, you might use
         * pattern matching or a command handler map.
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
                // No events to persist — just reply with current state
                return Effect.<CounterEvent, CounterState>none()
                    .thenRun(s -> {
                        get.replyTo().accept(s.value());
                        return null;
                    })
                    .build();
            }

            return Effect.unhandled();
        }

        /**
         * Event handler: pure function (state, event) → new state
         *
         * Called during:
         * - Recovery (replaying events from store)
         * - After persisting a new event
         *
         * MUST be pure. No side effects.
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