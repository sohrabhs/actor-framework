package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.actor.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Local implementation of ActorContext.
 * Manages child actors and provides spawning capabilities.
 */
public final class LocalActorContext<C> implements ActorContext<C> {

    private final ActorRef<C> self;
    private final ActorPath path;
    private final ActorIdentity identity;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ActorRef<?>> children = new ConcurrentHashMap<>();
    private final SupervisionDecider supervisionDecider;

    public LocalActorContext(
            ActorRef<C> self,
            ActorPath path,
            ActorIdentity identity,
            ExecutorService executor,
            SupervisionDecider supervisionDecider) {
        this.self = self;
        this.path = path;
        this.identity = identity;
        this.executor = executor;
        this.supervisionDecider = supervisionDecider;
    }

    @Override
    public ActorRef<C> self() {
        return self;
    }

    @Override
    public ActorPath path() {
        return path;
    }

    @Override
    public ActorIdentity identity() {
        return identity;
    }

    @Override
    public <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName) {
        ActorPath childPath = path.child(childName);

        InMemoryMailbox<M> mailbox = new InMemoryMailbox<>(executor);
        LocalActorRef<M> childRef = new LocalActorRef<>(childPath, null, mailbox);

        LocalActorContext<M> childContext = new LocalActorContext<>(
            childRef, childPath, null, executor, supervisionDecider
        );

        Behavior<M> behavior = factory.create(childContext);

        LocalActorCell<M> cell = new LocalActorCell<>(childRef, childContext, behavior, supervisionDecider);
        mailbox.start(cell::processMessage);

        children.put(childName, childRef);
        return childRef;
    }

    @Override
    public void stop(ActorRef<?> child) {
        String childName = child.path().name();
        ActorRef<?> removed = children.remove(childName);
        if (removed instanceof LocalActorRef) {
            ((LocalActorRef<?>) removed).mailbox().stop();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <M> ActorRef<M> getChild(String childName) {
        return (ActorRef<M>) children.get(childName);
    }

    @Override
    public void log(String message, Object... args) {
        String formatted = args.length > 0 ? String.format(message, args) : message;
        System.out.println("[" + path.toStringPath() + "] " + formatted);
    }
}