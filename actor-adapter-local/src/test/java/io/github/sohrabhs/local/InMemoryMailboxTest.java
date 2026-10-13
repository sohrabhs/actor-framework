package io.github.sohrabhs.local;

import io.github.sohrabhs.actor.core.mailbox.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class InMemoryMailboxTest {
    record High(int value) implements PriorityMessage {}
    @Test public void fullQueueRejectsWithoutEvictionAndPriorityOvertakes() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            InMemoryMailbox<Object> box = new InMemoryMailbox<>(executor, 3);
            box.enqueue(1); box.enqueue(2); box.enqueue(new High(3));
            assertEquals(Delivery.FULL, box.tryEnqueue(4));
            List<Object> received = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(3);
            box.start(m -> { received.add(m); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(List.of(new High(3), 1, 2), received);
            assertEquals(1, box.stats().rejected());
        } finally { executor.shutdownNow(); }
    }
    @Test public void priorityBurstDoesNotStarveNormal() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            InMemoryMailbox<Object> box = new InMemoryMailbox<>(executor, 64);
            box.enqueue("normal");
            for (int i=0;i<32;i++) box.enqueue(new High(i));
            List<Object> received = new CopyOnWriteArrayList<>();
            CountDownLatch done = new CountDownLatch(33);
            box.start(m -> { received.add(m); done.countDown(); });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals("normal", received.get(8));
        } finally { executor.shutdownNow(); }
    }
    @Test public void rejectedExecutorDoesNotWedgeOrAcceptMessage() {
        ExecutorService executor = Executors.newSingleThreadExecutor(); executor.shutdown();
        InMemoryMailbox<Integer> box = new InMemoryMailbox<>(executor, 4);
        box.start(m -> {});
        assertEquals(Delivery.EXECUTOR_UNAVAILABLE, box.tryEnqueue(1));
        assertEquals(Delivery.EXECUTOR_UNAVAILABLE, box.tryEnqueue(2));
        assertEquals(0, box.stats().depth());
    }
    @Test public void concurrentProducersDeliverEveryAcceptedMessageOnce() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        ExecutorService producers = Executors.newFixedThreadPool(4);
        try {
            InMemoryMailbox<Integer> box = new InMemoryMailbox<>(executor, 64);
            Set<Integer> seen = ConcurrentHashMap.newKeySet();
            AtomicInteger active = new AtomicInteger(); AtomicBoolean concurrent = new AtomicBoolean();
            CountDownLatch done = new CountDownLatch(4000);
            box.start(m -> { if(active.incrementAndGet()!=1) concurrent.set(true); seen.add(m); active.decrementAndGet(); done.countDown(); });
            List<Future<?>> futures = new ArrayList<>();
            for(int p=0;p<4;p++) { final int offset=p*1000; futures.add(producers.submit(() -> {
                for(int i=0;i<1000;i++) while(box.tryEnqueue(offset+i)!=Delivery.ACCEPTED) Thread.yield();
            })); }
            for(Future<?> f:futures) f.get(5,TimeUnit.SECONDS);
            assertTrue(done.await(5,TimeUnit.SECONDS));
            assertEquals(4000,seen.size()); assertFalse(concurrent.get());
        } finally { producers.shutdownNow(); executor.shutdownNow(); }
    }
    @Test public void stoppedMailboxReportsRejection() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { InMemoryMailbox<Integer> box=new InMemoryMailbox<>(executor, 2); box.stop(); assertEquals(Delivery.STOPPED,box.tryEnqueue(1)); }
        finally { executor.shutdownNow(); }
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsNonPositiveCapacity() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try { new InMemoryMailbox<>(executor,0); } finally { executor.shutdownNow(); }
    }
}
