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