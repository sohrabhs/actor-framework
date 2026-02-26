package ir.sohrabhs.akka;


import ir.sohrabhs.actor.core.actor.ActorIdentity;
import ir.sohrabhs.actor.core.actor.ActorPath;
import ir.sohrabhs.actor.core.actor.ActorRef;

/**
 * Wraps Akka's ActorRef in our ActorRef interface.
 */
public final class AkkaActorRefAdapter<C> implements ActorRef<C> {

    private final akka.actor.typed.ActorRef<C> akkaRef;

    public AkkaActorRefAdapter(akka.actor.typed.ActorRef<C> akkaRef) {
        this.akkaRef = akkaRef;
    }

    @Override
    public void tell(C message) {
        akkaRef.tell(message);
    }

    @Override
    public ActorPath path() {
        return ActorPath.of(akkaRef.path().toString());
    }

    @Override
    public ActorIdentity identity() {
        String name = akkaRef.path().name();
        String parent = akkaRef.path().parent().name();
        return new ActorIdentity(parent, name);
    }

    /** Unwrap for Akka-internal use */
    akka.actor.typed.ActorRef<C> unwrap() {
        return akkaRef;
    }
}