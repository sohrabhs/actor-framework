package ir.sohrabhs.actor.core.persistence;

import java.util.Objects;

/**
 * Wrapper around a state snapshot with metadata.
 *
 * @param <S> State type
 */
public final class PersistedSnapshot<S> {

    private final String persistenceId;
    private final long sequenceNumber;
    private final S state;
    private final long timestamp;

    public PersistedSnapshot(String persistenceId, long sequenceNumber, S state, long timestamp) {
        this.persistenceId = Objects.requireNonNull(persistenceId);
        this.sequenceNumber = sequenceNumber;
        this.state = Objects.requireNonNull(state);
        this.timestamp = timestamp;
    }

    public String persistenceId() { return persistenceId; }
    public long sequenceNumber() { return sequenceNumber; }
    public S state() { return state; }
    public long timestamp() { return timestamp; }

    @Override
    public String toString() {
        return "PersistedSnapshot{id=" + persistenceId +
               ", seq=" + sequenceNumber +
               ", state=" + state + "}";
    }
}