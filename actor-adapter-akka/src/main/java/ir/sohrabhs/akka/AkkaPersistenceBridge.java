package ir.sohrabhs.akka;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;
import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;

/**
 * Bridges our PersistentBehavior to Akka Typed Persistence's EventSourcedBehavior.
 *
 * DESIGN REASONING:
 * Akka Typed Persistence has EventSourcedBehavior with:
 * - emptyState()
 * - commandHandler() → returns akka Effect
 * - eventHandler() → returns new State
 *
 * Our PersistentBehavior has the exact same structure.
 * This bridge translates:
 * - Our Effect → Akka's Effect
 * - Our onCommand → Akka's commandHandler
 * - Our onEvent → Akka's eventHandler
 * - Our Effect.stop() → Akka's Effect().stop() (passivation)
 *
 * The domain code stays completely unaware of Akka.
 *
 * @param <C> Command
 * @param <E> Event
 * @param <S> State
 */
public final class AkkaPersistenceBridge {

    private AkkaPersistenceBridge() {}

    /**
     * Convert our PersistentBehavior to Akka's EventSourcedBehavior.
     */
    public static <C, E, S> Behavior<C> toBehavior(PersistentBehavior<C, E, S> ourBehavior) {
        return Behaviors.setup(akkaCtx -> {
            ActorContext<C> ourContext = new AkkaActorContextAdapter<>(akkaCtx);

            PersistenceId persistenceId = PersistenceId.ofUniqueId(
                    ourBehavior.identity().persistenceId()
            );

            return new EventSourcedBehavior<C, E, S>(persistenceId) {

                @Override
                public S emptyState() {
                    return ourBehavior.emptyState();
                }

                @Override
                public CommandHandler<C, E, S> commandHandler() {
                    return newCommandHandlerBuilder()
                            .forAnyState()
                            .onAnyCommand((state, command) -> translateEffect(
                                            ourBehavior.onCommand(state, command),
                                            state, Effect()
                                    )
                            );
                }

                @Override
                public EventHandler<S, E> eventHandler() {
                    return newEventHandlerBuilder()
                            .forAnyState()
                            .onAnyEvent((state, event) -> ourBehavior.onEvent(state, event));
                }

                @Override
                public SignalHandler<S> signalHandler() {
                    return newSignalHandlerBuilder()
                            .onSignal(
                                    akka.persistence.typed.RecoveryCompleted.instance(),
                                    state -> {
                                        ourBehavior.onRecoveryComplete(ourContext, state);
                                    }
                            )
                            .build();
                }

                @Override
                public boolean shouldSnapshot(S state, E event, long sequenceNr) {
                    int interval = ourBehavior.snapshotEvery();
                    return interval > 0 && sequenceNr % interval == 0;
                }
            };
        });
    }

    /**
     * Translate our Effect to Akka's Effect.
     *
     * Now handles Effect.shouldStop() by mapping to Akka's Effect().stop().
     */
    private static <C, E, S> akka.persistence.typed.javadsl.Effect<E, S> translateEffect(
            ir.sohrabhs.actor.core.persistence.Effect<E, S> ourEffect,
            S currentState,
            EffectFactories<E, S> akkaEffectFactory) {

        if (ourEffect.isUnhandled()) {
            return akkaEffectFactory.unhandled();
        }

        // Build the base effect (persist or none)
        akka.persistence.typed.javadsl.EffectBuilder<E, S> baseEffect;

        if (ourEffect.events().isEmpty()) {
            baseEffect = akkaEffectFactory.none();
        } else if (ourEffect.events().size() == 1) {
            baseEffect = akkaEffectFactory.persist(ourEffect.events().get(0));
        } else {
            baseEffect = akkaEffectFactory.persist(ourEffect.events());
        }

        // Apply side effects
        if (ourEffect.sideEffect() != null) {
            baseEffect = (akka.persistence.typed.javadsl.EffectBuilder<E, S>)
                    baseEffect.thenRun(newState -> {
                        ourEffect.sideEffect().apply(newState);
                    });
        }

        // Apply stop if requested
        if (ourEffect.shouldStop()) {
            return baseEffect.thenStop();
        }

        return baseEffect.thenNoReply();
    }
}