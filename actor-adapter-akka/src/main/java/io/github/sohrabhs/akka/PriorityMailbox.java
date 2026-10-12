package io.github.sohrabhs.akka;

import akka.actor.ActorSystem;
import akka.dispatch.BoundedStablePriorityMailbox;
import akka.dispatch.PriorityGenerator;
import com.typesafe.config.Config;
import io.github.sohrabhs.actor.core.mailbox.PriorityMessage;
import io.github.sohrabhs.actor.core.mailbox.UrgentMessage;

/** Non-blocking bounded Akka mailbox. Overflow is observable via Akka dead letters. */
public final class PriorityMailbox extends BoundedStablePriorityMailbox {
    public PriorityMailbox(ActorSystem.Settings settings, Config config) {
        super(new PriorityGenerator() {
            @Override public int gen(Object message) {
                return message instanceof UrgentMessage || message instanceof PriorityMessage p && p.highPriority() ? 0 : 1;
            }
        }, config.getInt("mailbox-capacity"), scala.concurrent.duration.Duration.Zero());
    }
}
