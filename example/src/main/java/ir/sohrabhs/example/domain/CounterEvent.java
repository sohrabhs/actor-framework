package ir.sohrabhs.example.domain;

import java.io.Serializable;

/**
 * Events that the Counter actor persists.
 * Pure domain facts. Immutable. Serializable (for persistence).
 */
public interface CounterEvent extends Serializable {

    final class Incremented implements CounterEvent {
        private static final long serialVersionUID = 1L;
        private final int amount;

        public Incremented(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Incremented{" + amount + "}";
        }
    }

    final class Decremented implements CounterEvent {
        private static final long serialVersionUID = 1L;
        private final int amount;

        public Decremented(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Decremented{" + amount + "}";
        }
    }
}