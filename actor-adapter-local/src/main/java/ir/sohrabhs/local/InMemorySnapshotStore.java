package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.persistence.PersistedSnapshot;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory snapshot store.
 */
public final class InMemorySnapshotStore<S> implements SnapshotStore<S> {

    private final ConcurrentHashMap<String, PersistedSnapshot<S>> snapshots =
        new ConcurrentHashMap<>();

    @Override
    public void save(String persistenceId, long sequenceNumber, S state) {
        snapshots.put(persistenceId,
            new PersistedSnapshot<>(persistenceId, sequenceNumber, state, System.currentTimeMillis()));
    }

    @Override
    public Optional<PersistedSnapshot<S>> loadLatest(String persistenceId) {
        return Optional.ofNullable(snapshots.get(persistenceId));
    }

    @Override
    public void deleteUpTo(String persistenceId, long maxSequenceNumber) {
        PersistedSnapshot<S> current = snapshots.get(persistenceId);
        if (current != null && current.sequenceNumber() <= maxSequenceNumber) {
            snapshots.remove(persistenceId);
        }
    }
}