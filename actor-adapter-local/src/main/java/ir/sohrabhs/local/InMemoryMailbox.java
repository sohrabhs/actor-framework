package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.mailbox.Mailbox;

import java.util.concurrent.ConcurrentLinkedQueue;
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

    private final ConcurrentLinkedQueue<C> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final ExecutorService executor;
    private volatile MessageHandler<C> handler;
    private volatile boolean stopped = false;

    public InMemoryMailbox(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void enqueue(C message) {
        if (stopped) {
            return; // silently drop — matches Akka's dead letter behavior
        }
        queue.offer(message);
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
        queue.clear();
    }

    @Override
    public boolean hasPending() {
        return !queue.isEmpty();
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
            while (!stopped && processed < 10 && (message = queue.poll()) != null) {
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
            if (!stopped && !queue.isEmpty()) {
                scheduleProcessing();
            }
        }
    }
}