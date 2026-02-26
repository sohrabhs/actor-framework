package ir.sohrabhs.akka;

import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.ReceiveBuilder;
import ir.sohrabhs.actor.core.actor.*;

/**
 * Bridges our core Behavior<C> to Akka Typed's AbstractBehavior<C>.
 *
 * DESIGN REASONING:
 * Our Behavior.onMessage returns a new Behavior (functional style).
 * Akka Typed also works this way. This bridge:
 * 1. Wraps our BehaviorFactory in Akka's Behaviors.setup()
 * 2. Delegates message handling to our Behavior.onMessage()
 * 3. Translates the returned Behavior back to Akka's Behavior
 *
 * This is a one-way bridge: Akka calls our code, never the reverse.
 */
public final class AkkaBehaviorBridge {

    private AkkaBehaviorBridge() {}

    /**
     * Convert our BehaviorFactory into an Akka Typed Behavior.
     */
    public static <C> akka.actor.typed.Behavior<C> toBehavior(BehaviorFactory<C> factory) {
        return akka.actor.typed.javadsl.Behaviors.setup(akkaCtx -> {
            // Wrap Akka's context in our ActorContext
            ActorContext<C> ourContext = new AkkaActorContextAdapter<>(akkaCtx);

            // Create our behavior
            Behavior<C> ourBehavior = factory.create(ourContext);

            // Return a wrapper that delegates to our behavior
            return new AkkaBehaviorWrapper<>(akkaCtx, ourBehavior, ourContext);
        });
    }

    /**
     * Akka Behavior that wraps our Behavior.
     */
    private static final class AkkaBehaviorWrapper<C> extends AbstractBehavior<C> {

        private Behavior<C> currentBehavior;
        private final ActorContext<C> ourContext;

        AkkaBehaviorWrapper(
                akka.actor.typed.javadsl.ActorContext<C> akkaCtx,
                Behavior<C> initialBehavior,
                ActorContext<C> ourContext) {
            super(akkaCtx);
            this.currentBehavior = initialBehavior;
            this.ourContext = ourContext;
        }

        @Override
        public Receive<C> createReceive() {
            return newReceiveBuilder()
                .onAnyMessage(this::onMessage)
                .build();
        }

        private akka.actor.typed.Behavior<C> onMessage(C message) {
            Behavior<C> next = currentBehavior.onMessage(ourContext, message);

            if (Behaviors.isStopped(next)) {
                return akka.actor.typed.javadsl.Behaviors.stopped();
            }

            if (!Behaviors.isSame(next)) {
                currentBehavior = next;
            }

            return this;
        }
    }
}