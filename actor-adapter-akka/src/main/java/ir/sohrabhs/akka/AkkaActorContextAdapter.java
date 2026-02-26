package ir.sohrabhs.akka;

import ir.sohrabhs.actor.core.actor.*;

/**
 * Adapts Akka's ActorContext to our ActorContext interface.
 */
public final class AkkaActorContextAdapter<C> implements ActorContext<C> {

    private final akka.actor.typed.javadsl.ActorContext<C> akkaCtx;

    public AkkaActorContextAdapter(akka.actor.typed.javadsl.ActorContext<C> akkaCtx) {
        this.akkaCtx = akkaCtx;
    }

    @Override
    public ActorRef<C> self() {
        return new AkkaActorRefAdapter<>(akkaCtx.getSelf());
    }

    @Override
    public ActorPath path() {
        return ActorPath.of(akkaCtx.getSelf().path().toString());
    }

    @Override
    public ActorIdentity identity() {
        // For shard entities, this would be set differently
        String name = akkaCtx.getSelf().path().name();
        String parent = akkaCtx.getSelf().path().parent().name();
        return new ActorIdentity(parent, name);
    }

    @Override
    public <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName) {
        akka.actor.typed.Behavior<M> akkaBehavior = AkkaBehaviorBridge.toBehavior(factory);
        akka.actor.typed.ActorRef<M> akkaRef = akkaCtx.spawn(akkaBehavior, childName);
        return new AkkaActorRefAdapter<>(akkaRef);
    }

    @Override
    public void stop(ActorRef<?> child) {
        if (child instanceof AkkaActorRefAdapter) {
            akkaCtx.stop(((AkkaActorRefAdapter<?>) child).unwrap());
        }
    }

    @Override
    public <M> ActorRef<M> getChild(String childName) {
        // Akka Typed doesn't have direct child lookup by name.
        // In practice, you'd track children in a Map.
        return null;
    }

    @Override
    public void log(String message, Object... args) {
        akkaCtx.getLog().info(String.format(message, args));
    }
}