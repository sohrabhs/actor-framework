package ir.sohrabhs.actor.core.actor;

/**
 * Factory methods for common behavior patterns.
 * Mirrors Akka Typed's Behaviors companion object.
 */
public final class Behaviors {

    private Behaviors() {
        // utility class
    }

    /**
     * Sentinel: keep the current behavior unchanged.
     * In Akka Typed, this is Behaviors.same.
     */
    @SuppressWarnings("unchecked")
    public static <C> Behavior<C> same() {
        return (Behavior<C>) SameBehavior.INSTANCE;
    }

    /**
     * Sentinel: stop this actor.
     * In Akka Typed, this is Behaviors.stopped.
     */
    @SuppressWarnings("unchecked")
    public static <C> Behavior<C> stopped() {
        return (Behavior<C>) StoppedBehavior.INSTANCE;
    }

    /**
     * Creates a behavior from a setup function that has access to ActorContext.
     * This is the primary entry point, mirroring Behaviors.setup in Akka Typed.
     *
     * The setup function runs once when the actor starts, and returns the initial behavior.
     */
    public static <C> BehaviorFactory<C> setup(SetupFunction<C> setupFn) {
        return new BehaviorFactory<C>() {
            @Override
            public Behavior<C> create(ActorContext<C> context) {
                return setupFn.apply(context);
            }
        };
    }

    /**
     * Creates a simple receive behavior (no setup needed).
     */
    public static <C> Behavior<C> receive(Behavior<C> behavior) {
        return behavior;
    }

    // --- Sentinel implementations ---

    public static boolean isSame(Behavior<?> b) {
        return b instanceof SameBehavior;
    }

    public static boolean isStopped(Behavior<?> b) {
        return b instanceof StoppedBehavior;
    }

    private enum SameBehavior implements Behavior<Object> {
        INSTANCE;

        @Override
        public Behavior<Object> onMessage(ActorContext<Object> context, Object command) {
            throw new UnsupportedOperationException(
                "SameBehavior is a sentinel and should never receive messages directly"
            );
        }
    }

    private enum StoppedBehavior implements Behavior<Object> {
        INSTANCE;

        @Override
        public Behavior<Object> onMessage(ActorContext<Object> context, Object command) {
            throw new UnsupportedOperationException(
                "StoppedBehavior is a sentinel and should never receive messages directly"
            );
        }
    }

    @FunctionalInterface
    public interface SetupFunction<C> {
        Behavior<C> apply(ActorContext<C> context);
    }
}