package io.github.sohrabhs.local;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InMemoryEventStoreTest {

    @Test
    public void compactsEventsCoveredBySnapshot() {
        InMemoryEventStore<String> store = new InMemoryEventStore<>();
        store.persist("agent-1", 1, "first");
        store.persist("agent-1", 2, "second");
        store.persist("agent-1", 3, "third");

        store.deleteUpTo("agent-1", 2);

        assertEquals(1, store.loadEvents("agent-1", 0).size());
        assertEquals(3, store.highestSequenceNumber("agent-1"));
    }
}
