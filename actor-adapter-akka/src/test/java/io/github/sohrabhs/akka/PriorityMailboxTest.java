package io.github.sohrabhs.akka;

import akka.dispatch.Envelope;
import com.typesafe.config.ConfigFactory;
import io.github.sohrabhs.actor.core.mailbox.PriorityMessage;
import org.junit.Test;
import static org.junit.Assert.*;

public class PriorityMailboxTest {
    record High(int value) implements PriorityMessage {}
    @Test public void highPriorityAndEqualPriorityFifoMatchTheCoreContract() {
        var mailbox=new PriorityMailbox(null,ConfigFactory.parseString("mailbox-capacity=8"));
        var queue=mailbox.create(scala.Option.empty(),scala.Option.empty());
        queue.enqueue(null,new Envelope("normal-1",null));
        queue.enqueue(null,new Envelope(new High(1),null));
        queue.enqueue(null,new Envelope("normal-2",null));
        queue.enqueue(null,new Envelope(new High(2),null));
        assertEquals(new High(1),queue.dequeue().message());
        assertEquals(new High(2),queue.dequeue().message());
        assertEquals("normal-1",queue.dequeue().message());
        assertEquals("normal-2",queue.dequeue().message());
        assertFalse(queue.hasMessages());
        assertEquals(scala.concurrent.duration.Duration.Zero(),mailbox.pushTimeOut());
    }
}
