package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;

/**
 * The runtime cell for a local actor. Holds the current behavior and processes messages.
 *
 * DESIGN REASONING:
 * In Akka, each actor has an ActorCell that manages:
 * - Current behavior
 * - Mailbox processing
 * - Supervision
 * - Behavior switching
 *
 * We mirror this. The cell receives messages from the mailbox and delegates to the behavior.
 * If the behavior returns a new behavior, we switch. If it returns stopped, we stop.
 */
final class LocalActorCell<C> {

    private final LocalActorRef<C> self;
    private final ActorContext<C> context;
    private volatile Behavior<C> currentBehavior;
    private final SupervisionDecider supervisionDecider;

    LocalActorCell(
            LocalActorRef<C> self,
            ActorContext<C> context,
            Behavior<C> initialBehavior,
            SupervisionDecider supervisionDecider) {
        this.self = self;
        this.context = context;
        this.currentBehavior = initialBehavior;
        this.supervisionDecider = supervisionDecider;
    }

    /**
     * Called by the mailbox for each message. Guaranteed single-threaded by mailbox.
     */
    void processMessage(C message) {
        try {
            Behavior<C> next = currentBehavior.onMessage(context, message);

            if (Behaviors.isStopped(next)) {
                self.mailbox().stop();
                return;
            }

            if (!Behaviors.isSame(next)) {
                currentBehavior = next;
            }
        } catch (Exception e) {
            handleFailure(e, message);
        }
    }

    private void handleFailure(Exception e, C message) {
        SupervisionStrategy strategy = supervisionDecider.decide(e);
        switch (strategy) {
            case RESTART:
                context.log("Actor restarting due to: %s", e.getMessage());
                // In a real restart, we'd re-run the behavior factory.
                // For simplicity in local adapter, we keep current behavior.
                break;
            case STOP:
                context.log("Actor stopping due to: %s", e.getMessage());
                self.mailbox().stop();
                break;
            case RESUME:
                context.log("Actor resuming after: %s", e.getMessage());
                break;
            case ESCALATE:
                context.log("Escalating failure: %s", e.getMessage());
                throw new RuntimeException("Escalated from actor " + self.path(), e);
        }
    }
}