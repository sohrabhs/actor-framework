package ir.sohrabhs.actor.core.persistence;

import java.util.Optional;

/**
 * Port for snapshot persistence.
 *
 * Maps to: Akka Persistence snapshot store
 *
 * @param <S> State type
 */
public interface SnapshotStore<S> {

    /**
     * Save a snapshot of the current state.
     */
    void save(String persistenceId, long sequenceNumber, S state);

    /**
     * Load the latest snapshot for an entity.
     *
     * @return The latest snapshot, or empty if none exists
     */
    Optional<PersistedSnapshot<S>> loadLatest(String persistenceId);

    /**
     * Delete snapshots up to a sequence number (for cleanup).
     */
    void deleteUpTo(String persistenceId, long maxSequenceNumber);
}