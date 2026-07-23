package io.github.sohrabhs.local;


import io.github.sohrabhs.actor.core.actor.ActorIdentity;
import io.github.sohrabhs.actor.core.actor.ActorPath;
import io.github.sohrabhs.actor.core.actor.ActorRef;
import io.github.sohrabhs.actor.core.mailbox.Mailbox;

/**
 * Local actor reference that delivers messages via a mailbox.
 */
public final class LocalActorRef<C> implements ActorRef<C> {

    private final ActorPath path;
    private final ActorIdentity identity; // nullable for non-entity actors
    private final Mailbox<C> mailbox;

    public LocalActorRef(ActorPath path, ActorIdentity identity, Mailbox<C> mailbox) {
        this.path = path;
        this.identity = identity;
        this.mailbox = mailbox;
    }

    @Override
    public void tell(C message) {
        mailbox.enqueue(message);
    }

    @Override
    public ActorPath path() {
        return path;
    }

    @Override
    public ActorIdentity identity() {
        return identity;
    }

    Mailbox<C> mailbox() {
        return mailbox;
    }

    @Override
    public String toString() {
        return "LocalActorRef{" + path.toStringPath() + "}";
    }
}