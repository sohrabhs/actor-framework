package ir.sohrabhs.example.domain;

import java.io.Serializable;

/**
 * The state of a Counter actor.
 * Immutable value object.
 */
public final class CounterState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int value;

    public CounterState(int value) {
        this.value = value;
    }

    public static CounterState empty() {
        return new CounterState(0);
    }

    public int value() {
        return value;
    }

    public CounterState withValue(int newValue) {
        return new CounterState(newValue);
    }

    @Override
    public String toString() {
        return "CounterState{value=" + value + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return value == ((CounterState) o).value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }
}