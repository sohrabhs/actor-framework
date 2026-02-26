package ir.sohrabhs.actor.core.mailbox;

/**
 * Abstraction for an actor's message queue.
 *
 * DESIGN REASONING:
 * In Akka, each actor has a mailbox that provides ordering guarantees.
 * We abstract this so adapters can provide:
 * - In-memory queue (local)
 * - Android Handler/Looper based (Android)
 * - Akka's built-in dispatchers and mailboxes
 *
 * Key guarantees:
 * - Messages from the same sender are delivered in order
 * - Only one message is processed at a time (single-threaded illusion)
 *
 * @param <C> Command type
 */
public interface Mailbox<C> {

    /**
     * Enqueue a message for processing.
     * This must be thread-safe (can be called from any thread).
     */
    void enqueue(C message);

    /**
     * Start processing messages.
     * The provided handler is called for each message, one at a time.
     */
    void start(MessageHandler<C> handler);

    /**
     * Stop accepting and processing messages.
     */
    void stop();

    /**
     * Check if mailbox has pending messages.
     */
    boolean hasPending();

    @FunctionalInterface
    interface MessageHandler<C> {
        void handle(C message);
    }
}