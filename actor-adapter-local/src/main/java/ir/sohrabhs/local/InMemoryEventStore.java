package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistedEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory event store for testing and local/Android execution.
 *
 * Thread-safe: multiple actors may share one EventStore instance.
 * Each persistenceId has its own isolated journal.
 */
public final class InMemoryEventStore<E> implements EventStore<E> {

    private final ConcurrentHashMap<String, List<PersistedEvent<E>>> journals =
        new ConcurrentHashMap<>();

    @Override
    public void persist(String persistenceId, long sequenceNumber, E event) {
        journals.computeIfAbsent(persistenceId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PersistedEvent<>(persistenceId, sequenceNumber, event, System.currentTimeMillis()));
    }

    @Override
    public List<PersistedEvent<E>> loadEvents(String persistenceId, long fromSequenceNumber) {
        List<PersistedEvent<E>> journal = journals.get(persistenceId);
        if (journal == null) {
            return Collections.emptyList();
        }
        synchronized (journal) {
            return journal.stream()
                .filter(e -> e.sequenceNumber() > fromSequenceNumber)
                .collect(Collectors.toList());
        }
    }

    @Override
    public long highestSequenceNumber(String persistenceId) {
        List<PersistedEvent<E>> journal = journals.get(persistenceId);
        if (journal == null || journal.isEmpty()) {
            return 0;
        }
        synchronized (journal) {
            return journal.get(journal.size() - 1).sequenceNumber();
        }
    }

    /** For testing: inspect all events */
    public List<PersistedEvent<E>> allEvents(String persistenceId) {
        return loadEvents(persistenceId, -1);
    }
}