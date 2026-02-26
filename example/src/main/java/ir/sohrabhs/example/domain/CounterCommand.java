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

    @FunctionalInterface
    interface ValueConsumer {
        void accept(int value);
    }
}