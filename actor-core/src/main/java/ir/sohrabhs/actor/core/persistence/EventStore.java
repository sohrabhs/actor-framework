package ir.sohrabhs.actor.core.persistence;

import java.util.List;

/**
 * Port for event persistence.
 *
 * DESIGN REASONING:
 * This is a pure port (Hexagonal Architecture). The core defines WHAT it needs,
 * not HOW it's done. Adapters provide:
 * - InMemoryEventStore (for local/Android/testing)
 * - AkkaPersistenceAdapter (delegates to Akka Persistence)
 * - SQLiteEventStore (for Android production)
 * - JdbcEventStore, CassandraEventStore, etc.
 *
 * Maps to: Akka Persistence journal
 *
 * @param <E> Event type
 */
public interface EventStore<E> {

    /**
     * Persist a single event.
     *
     * @param persistenceId The entity's persistence ID
     * @param sequenceNumber Monotonically increasing sequence number
     * @param event The domain event to persist
     */
    void persist(String persistenceId, long sequenceNumber, E event);

    /**
     * Load all events for an entity, starting from a sequence number.
     *
     * @param persistenceId The entity's persistence ID
     * @param fromSequenceNumber Load events with seqNr > this value (exclusive)
     * @return Ordered list of persisted events
     */
    List<PersistedEvent<E>> loadEvents(String persistenceId, long fromSequenceNumber);

    /**
     * Get the highest sequence number for an entity.
     * Returns 0 if no events exist.
     */
    long highestSequenceNumber(String persistenceId);
}