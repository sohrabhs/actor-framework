package ir.sohrabhs.actor.core.actor;

/**
 * A function that decides the supervision strategy based on the thrown exception.
 * Mirrors Akka's Decider.
 */
@FunctionalInterface
public interface SupervisionDecider {

    SupervisionStrategy decide(Throwable cause);

    /**
     * Default: restart on any exception.
     */
    static SupervisionDecider restartAlways() {
        return cause -> SupervisionStrategy.RESTART;
    }

    /**
     * Default: stop on any exception.
     */
    static SupervisionDecider stopAlways() {
        return cause -> SupervisionStrategy.STOP;
    }
}