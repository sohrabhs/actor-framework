package io.github.sohrabhs.local;

import io.github.sohrabhs.actor.core.actor.*;
import io.github.sohrabhs.actor.core.system.ActorSystemConfig;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class LocalLifecycleTest {
    @Test public void stopRemovesDescendantsAndAllowsNameReuse() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LocalActorSystem system = new LocalActorSystem(ActorSystemConfig.builder("test").build(), executor);
        AtomicReference<ActorContext<String>> parent = new AtomicReference<>();
        AtomicReference<ActorContext<String>> child = new AtomicReference<>();
        try {
            system.<String>spawn(ctx -> {
                parent.set(ctx);
                ctx.<String>spawn(c -> { child.set(c); c.spawn(g -> (x,m) -> Behaviors.same(), "grandchild"); return (x,m) -> Behaviors.same(); }, "child");
                return (x,m) -> Behaviors.same();
            }, "parent");
            ActorRef<String> original = parent.get().getChild("child");
            try { parent.get().spawn(c -> (x,m) -> Behaviors.same(), "child"); fail("duplicate accepted"); }
            catch (IllegalArgumentException expected) { }
            parent.get().stop(original);
            assertNull(parent.get().getChild("child"));
            assertNull(child.get().getChild("grandchild"));
            assertNotNull(parent.get().spawn(c -> (x,m) -> Behaviors.same(), "child"));
        } finally { system.terminate(); executor.awaitTermination(2, TimeUnit.SECONDS); }
    }

    @Test public void restartRecreatesBehavior() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        LocalActorSystem system = new LocalActorSystem(ActorSystemConfig.builder("test").build(), executor);
        AtomicInteger creations = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);
        try {
            ActorRef<String> ref = system.spawn(ctx -> { creations.incrementAndGet(); return (c,m) -> {
                if (m.equals("fail")) throw new IllegalStateException("test");
                done.countDown(); return Behaviors.same();
            }; }, "restart");
            ref.tell("fail"); ref.tell("ok");
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertEquals(2, creations.get());
        } finally { system.terminate(); executor.awaitTermination(2, TimeUnit.SECONDS); }
    }
}
