package io.github.sohrabhs.local;


import io.github.sohrabhs.actor.core.mailbox.Mailbox;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe, single-consumer mailbox using a lock-free queue.
 *
 * DESIGN REASONING:
 * - ConcurrentLinkedQueue for lock-free enqueue from any thread
 * - Single-threaded processing via AtomicBoolean scheduling flag
 * - This guarantees: at most one message is processed at a time
 * - Android-friendly: no heavy locking, no Java 8+ API beyond ConcurrentLinkedQueue
 *
 * This is the key guarantee of the Actor Model: no concurrent processing within one actor.
 */
public final class InMemoryMailbox<C> implements Mailbox<C> {

    private final Object queueLock = new Object();
    private final ArrayDeque<C> queue;
    private final int capacity;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final ExecutorService executor;
    private volatile MessageHandler<C> handler;
    private volatile boolean stopped = false;

    public InMemoryMailbox(ExecutorService executor, int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Mailbox capacity must be positive");
        }
        this.executor = executor;
        this.capacity = capacity;
        this.queue = new ArrayDeque<>(capacity);
    }

    @Override
    public void enqueue(C message) {
        if (stopped) {
            return; // silently drop — matches Akka's dead letter behavior
        }
        synchronized (queueLock) {
            // Keep the newest observations. A slow actor must degrade by skipping stale market
            // data, not by retaining an unbounded history until the process runs out of heap.
            if (queue.size() == capacity) {
                queue.removeFirst();
            }
            queue.addLast(message);
        }
        scheduleProcessing();
    }

    @Override
    public void start(MessageHandler<C> handler) {
        this.handler = handler;
        scheduleProcessing();
    }

    @Override
    public void stop() {
        this.stopped = true;
        synchronized (queueLock) {
            queue.clear();
        }
    }

    @Override
    public boolean hasPending() {
        synchronized (queueLock) {
            return !queue.isEmpty();
        }
    }

    /**
     * Ensures only one processing task is scheduled at a time.
     * This is the mechanism that provides single-threaded illusion.
     */
    private void scheduleProcessing() {
        if (handler == null || stopped) return;

        if (scheduled.compareAndSet(false, true)) {
            executor.submit(this::processMessages);
        }
    }

    private void processMessages() {
        try {
            // Process a batch of messages (up to 10) before re-scheduling.
            // This prevents starvation of other actors sharing the executor.
            int processed = 0;
            C message;
            while (!stopped && processed < 10 && (message = poll()) != null) {
                try {
                    handler.handle(message);
                } catch (Exception e) {
                    // Supervision handles this — for now, log and continue
                    System.err.println("[Mailbox] Exception processing message: " + e.getMessage());
                    e.printStackTrace();
                }
                processed++;
            }
        } finally {
            scheduled.set(false);
            // If there are still pending messages, re-schedule
            if (!stopped && hasPending()) {
                scheduleProcessing();
            }
        }
    }

    private C poll() {
        synchronized (queueLock) {
            return queue.pollFirst();
        }
    }
}
