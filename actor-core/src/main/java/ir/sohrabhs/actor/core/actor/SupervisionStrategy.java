package ir.sohrabhs.actor.core.actor;

/**
 * Defines how failures are handled.
 *
 * DESIGN REASONING:
 * Akka has a rich supervision tree. We provide the essential strategies
 * that map cleanly to Akka's SupervisorStrategy:
 * - RESTART: restart the actor, clearing state (Akka: SupervisorStrategy.restart)
 * - STOP: stop the actor permanently (Akka: SupervisorStrategy.stop)
 * - ESCALATE: propagate to parent (Akka: SupervisorStrategy.escalate)
 * - RESUME: ignore the failure, keep state (Akka: SupervisorStrategy.resume)
 *
 * The adapter translates these to the framework's actual supervision mechanism.
 */
public enum SupervisionStrategy {
    RESTART,
    STOP,
    ESCALATE,
    RESUME
}