package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.*;

import java.util.List;
import java.util.Optional;

/**
 * Runtime cell for a persistent (event-sourced) actor.
 *
 * DESIGN REASONING:
 * This is where the magic happens. This cell:
 * 1. On creation: recovers state from EventStore/SnapshotStore
 * 2. On message: calls PersistentBehavior.onCommand → gets Effect
 * 3. Interprets the Effect: persists events, updates state, runs side effects
 * 4. Handles snapshotting based on PersistentBehavior.snapshotEvery()
 *
 * This maps directly to how Akka's EventSourcedBehavior works internally:
 * - Recovery phase: load snapshot, replay events
 * - Running phase: command → effect → persist → apply → callback
 *
 * @param <C> Command type
 * @param <E> Event type
 * @param <S> State type
 */
final class LocalPersistentActorCell<C, E, S> {

    private final ActorContext<C> context;
    private final PersistentBehavior<C, E, S> behavior;
    private final EventStore<E> eventStore;
    private final SnapshotStore<S> snapshotStore;
    private final String persistenceId;
    private final LocalActorRef<C> self;
    private final SupervisionDecider supervisionDecider;

    private S currentState;
    private long sequenceNumber;
    private long eventsSinceSnapshot;

    LocalPersistentActorCell(
            LocalActorRef<C> self,
            ActorContext<C> context,
            PersistentBehavior<C, E, S> behavior,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore,
            SupervisionDecider supervisionDecider) {
        this.self = self;
        this.context = context;
        this.behavior = behavior;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.persistenceId = behavior.identity().persistenceId();
        this.supervisionDecider = supervisionDecider;

        recover();
    }

    /**
     * Recovery: load snapshot + replay events.
     * This matches Akka's recovery behavior exactly.
     */
    private void recover() {
        currentState = behavior.emptyState();
        sequenceNumber = 0;
        eventsSinceSnapshot = 0;

        // Step 1: Try to load latest snapshot
        Optional<PersistedSnapshot<S>> snapshot = snapshotStore.loadLatest(persistenceId);
        if (snapshot.isPresent()) {
            PersistedSnapshot<S> snap = snapshot.get();
            currentState = snap.state();
            sequenceNumber = snap.sequenceNumber();
            context.log("Recovered snapshot at seqNr %d", sequenceNumber);
        }

        // Step 2: Replay events after snapshot
        List<PersistedEvent<E>> events = eventStore.loadEvents(persistenceId, sequenceNumber);
        for (PersistedEvent<E> persisted : events) {
            currentState = behavior.onEvent(currentState, persisted.event());
            sequenceNumber = persisted.sequenceNumber();
        }

        if (!events.isEmpty()) {
            context.log("Replayed %d events, seqNr now %d", events.size(), sequenceNumber);
        }

        // Step 3: Notify recovery complete
        behavior.onRecoveryComplete(context, currentState);
        context.log("Recovery complete. State: %s", currentState);
    }

    /**
     * Process a command message.
     * Called by the mailbox. Guaranteed single-threaded.
     */
    void processMessage(C command) {
        try {
            // Command handler produces an Effect
            Effect<E, S> effect = behavior.onCommand(currentState, command);

            if (effect.isUnhandled()) {
                context.log("Unhandled command: %s", command);
                return;
            }

            // Persist events
            for (E event : effect.events()) {
                sequenceNumber++;
                eventStore.persist(persistenceId, sequenceNumber, event);
                currentState = behavior.onEvent(currentState, event);
                eventsSinceSnapshot++;
            }

            // Check if we should snapshot
            boolean shouldSnapshot = effect.shouldSnapshot();
            if (!shouldSnapshot && behavior.snapshotEvery() > 0
                    && eventsSinceSnapshot >= behavior.snapshotEvery()) {
                shouldSnapshot = true;
            }

            if (shouldSnapshot && sequenceNumber > 0) {
                snapshotStore.save(persistenceId, sequenceNumber, currentState);
                eventsSinceSnapshot = 0;
                context.log("Snapshot saved at seqNr %d", sequenceNumber);
            }

            // Run side effects
            if (effect.sideEffect() != null) {
                effect.sideEffect().apply(currentState);
            }

        } catch (Exception e) {
            handleFailure(e, command);
        }
    }

    private void handleFailure(Exception e, C command) {
        SupervisionStrategy strategy = supervisionDecider.decide(e);
        switch (strategy) {
            case RESTART:
                context.log("Persistent actor restarting due to: %s. Re-recovering...", e.getMessage());
                recover();
                break;
            case STOP:
                context.log("Persistent actor stopping due to: %s", e.getMessage());
                self.mailbox().stop();
                break;
            case RESUME:
                context.log("Persistent actor resuming after: %s", e.getMessage());
                break;
            case ESCALATE:
                throw new RuntimeException("Escalated from persistent actor " + self.path(), e);
        }
    }
}