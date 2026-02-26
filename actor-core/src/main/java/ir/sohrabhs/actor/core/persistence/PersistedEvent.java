package ir.sohrabhs.actor.core.persistence;

import java.util.Objects;

/**
 * Wrapper around a domain event with metadata for persistence.
 *
 * @param <E> Event type
 */
public final class PersistedEvent<E> {

    private final String persistenceId;
    private final long sequenceNumber;
    private final E event;
    private final long timestamp;

    public PersistedEvent(String persistenceId, long sequenceNumber, E event, long timestamp) {
        this.persistenceId = Objects.requireNonNull(persistenceId);
        this.sequenceNumber = sequenceNumber;
        this.event = Objects.requireNonNull(event);
        this.timestamp = timestamp;
    }

    public String persistenceId() { return persistenceId; }
    public long sequenceNumber() { return sequenceNumber; }
    public E event() { return event; }
    public long timestamp() { return timestamp; }

    @Override
    public String toString() {
        return "PersistedEvent{id=" + persistenceId +
               ", seq=" + sequenceNumber +
               ", event=" + event + "}";
    }
}