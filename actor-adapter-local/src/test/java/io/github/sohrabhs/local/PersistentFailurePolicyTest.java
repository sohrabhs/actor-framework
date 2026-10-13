package io.github.sohrabhs.local;
import io.github.sohrabhs.actor.core.actor.*;
import io.github.sohrabhs.actor.core.persistence.*;
import io.github.sohrabhs.actor.core.system.ActorSystemConfig;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
public class PersistentFailurePolicyTest {
    @Test public void explicitStopPolicyClosesDescendantsInsteadOfReplayingParent() throws Exception {
        var workers=Executors.newSingleThreadExecutor();
        var system=new LocalActorSystem(ActorSystemConfig.builder("failure").build(),workers);
        var stopped=new CountDownLatch(1); var child=new AtomicReference<ActorRef<String>>();
        try {
            var region=system.<String,String,Integer>initShardRegion("parent",id->new io.github.sohrabhs.actor.core.persistence.PersistentBehaviorWithContext<>() {
                public ActorIdentity identity(){ return new ActorIdentity("parent",id); }
                public Integer emptyState(){ return 0; }
                public Integer onEvent(Integer state,String event){ return state; }
                public SupervisionStrategy failureStrategy(Throwable failure){ return SupervisionStrategy.STOP; }
                public Effect<String,Integer> onCommandWithContext(ActorContext<String> context,Integer state,String command){
                    context.<String>spawn(c->{ child.set(c.self()); c.onStop(stopped::countDown); return (ctx,m)->Behaviors.same(); },"child");
                    throw new IllegalStateException("parent failed");
                }
            },new InMemoryEventStore<>(),new InMemorySnapshotStore<>());
            region.tell("one","fail");
            assertTrue(stopped.await(3,TimeUnit.SECONDS));
            assertEquals(io.github.sohrabhs.actor.core.mailbox.Delivery.STOPPED,child.get().tryTell("late"));
        } finally { system.terminate(); workers.awaitTermination(3,TimeUnit.SECONDS); }
    }
}
