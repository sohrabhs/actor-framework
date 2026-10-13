package io.github.sohrabhs.actor.core.persistence;

import io.github.sohrabhs.actor.core.actor.*;

public final class PersistentBehaviors {
    private PersistentBehaviors() {}
    /** Namespace storage independently of child factories which only receive a local name. */
    public static <C,E,S> PersistentBehavior<C,E,S> withIdentity(PersistentBehavior<C,E,S> delegate,ActorIdentity identity) {
        return new PersistentBehaviorWithContext<>() {
            public ActorIdentity identity() { return identity; }
            public SupervisionStrategy failureStrategy(Throwable failure) { return delegate.failureStrategy(failure); }
            public S emptyState() { return delegate.emptyState(); }
            @SuppressWarnings("unchecked")
            public Effect<E,S> onCommandWithContext(ActorContext<C> context,S state,C command) {
                if(delegate instanceof PersistentBehaviorWithContext<?,?,?> contextual)
                    return ((PersistentBehaviorWithContext<C,E,S>)contextual).onCommandWithContext(context,state,command);
                return delegate.onCommand(state,command);
            }
            public S onEvent(S state,E event) { return delegate.onEvent(state,event); }
            public int snapshotEvery() { return delegate.snapshotEvery(); }
            public void onRecoveryComplete(ActorContext<?> context,S state) { delegate.onRecoveryComplete(context,state); }
        };
    }
}
