package io.github.sohrabhs.local;

import io.github.sohrabhs.actor.core.mailbox.*;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** Bounded, non-blocking admission, one consumer, FIFO per lane. Never evicts accepted traffic. */
public final class InMemoryMailbox<C> implements Mailbox<C> {
    private final Object lock = new Object();
    // Allocate on demand, not capacity-sized arrays for thousands of idle actors.
    private final ArrayDeque<C> normal = new ArrayDeque<>();
    private final ArrayDeque<C> priority = new ArrayDeque<>();
    private final ExecutorService executor;
    private final int capacity;
    private final int reserve;
    private boolean scheduled;
    private boolean stopped;
    private int priorityRun;
    private MessageHandler<C> handler;
    private long accepted, rejected, processed, discardedOnStop;

    public InMemoryMailbox(ExecutorService executor, int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Mailbox capacity must be positive");
        this.executor = Objects.requireNonNull(executor);
        this.capacity = capacity;
        this.reserve = capacity >= 16 ? Math.max(1, capacity / 8) : 0;
    }

    @Override public void enqueue(C message) {
        Delivery result = tryEnqueue(message);
        if (result != Delivery.ACCEPTED) throw new DeliveryException(result);
    }

    @Override public Delivery tryEnqueue(C message) {
        Objects.requireNonNull(message, "message");
        synchronized (lock) {
            if (stopped) { rejected++; return Delivery.STOPPED; }
            boolean high = message instanceof UrgentMessage ||
                    (message instanceof PriorityMessage p && p.highPriority());
            int size = normal.size() + priority.size();
            if (size >= capacity || (!high && size >= capacity - reserve)) {
                rejected++; return Delivery.FULL;
            }
            ArrayDeque<C> lane = high ? priority : normal;
            lane.addLast(message);
            if (!scheduleLocked()) {
                lane.removeLast(); rejected++;
                return Delivery.EXECUTOR_UNAVAILABLE;
            }
            accepted++;
            return Delivery.ACCEPTED;
        }
    }

    @Override public void start(MessageHandler<C> handler) {
        synchronized (lock) {
            if (this.handler != null) throw new IllegalStateException("Mailbox already started");
            this.handler = Objects.requireNonNull(handler);
            if (!scheduleLocked()) throw new DeliveryException(Delivery.EXECUTOR_UNAVAILABLE);
        }
    }

    @Override public void stop() {
        synchronized (lock) {
            stopped = true;
            discardedOnStop += normal.size() + priority.size();
            normal.clear(); priority.clear();
        }
    }

    @Override public boolean hasPending() {
        synchronized (lock) { return !normal.isEmpty() || !priority.isEmpty(); }
    }

    public record Stats(int depth, long accepted, long rejected, long processed, long discardedOnStop) {}
    public Stats stats() {
        synchronized (lock) { return new Stats(normal.size() + priority.size(), accepted, rejected, processed, discardedOnStop); }
    }

    private boolean scheduleLocked() {
        if (scheduled || stopped || handler == null || !hasPending()) return true;
        scheduled = true;
        try { executor.execute(this::drain); return true; }
        catch (RejectedExecutionException e) { scheduled = false; return false; }
    }

    private C pollLocked() {
        if (!priority.isEmpty() && (priorityRun < 8 || normal.isEmpty())) {
            priorityRun++; return priority.removeFirst();
        }
        priorityRun = 0;
        return normal.pollFirst();
    }

    private void drain() {
        // Keep ownership if shutdown rejects rescheduling: already admitted messages are not lost.
        for (;;) {
            long deadline = System.nanoTime() + 2_000_000;
            for (int i = 0; i < 32; i++) {
                C next;
                synchronized (lock) {
                    if (stopped || (next = pollLocked()) == null) { scheduled = false; return; }
                }
                try { handler.handle(next); }
                catch (Exception e) { System.err.println("Actor handler failed: " + e); }
                finally { synchronized (lock) { processed++; } }
                if (System.nanoTime() >= deadline) break;
            }
            synchronized (lock) {
                scheduled = false;
                if (stopped || !hasPending()) return;
                if (scheduleLocked()) return;
                scheduled = true;
            }
        }
    }
}
