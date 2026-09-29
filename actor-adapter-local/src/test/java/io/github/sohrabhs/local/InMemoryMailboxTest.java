package io.github.sohrabhs.local;

import org.junit.Test;
import io.github.sohrabhs.actor.core.mailbox.UrgentMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InMemoryMailboxTest {

    @Test
    public void keepsNewestMessagesWhenCapacityIsReached() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            InMemoryMailbox<Integer> mailbox = new InMemoryMailbox<>(executor, 3);
            mailbox.enqueue(1);
            mailbox.enqueue(2);
            mailbox.enqueue(3);
            mailbox.enqueue(4);

            List<Integer> received = new CopyOnWriteArrayList<>();
            CountDownLatch delivered = new CountDownLatch(3);
            mailbox.start(message -> {
                received.add(message);
                delivered.countDown();
            });

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(2, 3, 4), received);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonPositiveCapacity() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            new InMemoryMailbox<>(executor, 0);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void urgentMessageOvertakesBacklogAndKeepsItsPlaceWhenFull() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            InMemoryMailbox<Object> mailbox = new InMemoryMailbox<>(executor, 3);
            mailbox.enqueue("market-1");
            mailbox.enqueue("market-2");
            mailbox.enqueue("market-3");
            mailbox.enqueue(new Stop());

            List<Object> received = new CopyOnWriteArrayList<>();
            CountDownLatch delivered = new CountDownLatch(3);
            mailbox.start(message -> {
                received.add(message);
                delivered.countDown();
            });

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertTrue(received.get(0) instanceof Stop);
            assertEquals(List.of("market-2", "market-3"), received.subList(1, 3));
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class Stop implements UrgentMessage {
    }
}
