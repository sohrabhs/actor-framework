package io.github.sohrabhs.example;

import io.github.sohrabhs.actor.core.actor.*;
import io.github.sohrabhs.actor.core.mailbox.PriorityMessage;
import io.github.sohrabhs.actor.core.system.ActorSystemConfig;
import io.github.sohrabhs.local.LocalActorSystem;
import java.util.*;
import java.util.concurrent.*;

/** Bounded in-flight fan-out. 2500 children share a pool; no child owns a thread. */
public final class NestedWorkers {
    interface Command {}
    record Start() implements Command, PriorityMessage {}
    record Result(long value) implements Command, PriorityMessage {}
    record Work(long value,ActorRef<Command> replyTo) implements PriorityMessage {}

    public static BehaviorFactory<Command> coordinator(int count,CompletableFuture<Long> answer) {
        if(count<1) throw new IllegalArgumentException("count");
        return context -> new Behavior<>() {
            final List<ActorRef<Work>> workers=new ArrayList<>();
            int next, received;
            long total;
            public Behavior<Command> onMessage(ActorContext<Command> ctx,Command command) {
                if(command instanceof Start) {
                    if(!workers.isEmpty()) throw new IllegalStateException("Already started");
                    for(int i=0;i<count;i++) workers.add(ctx.spawn(c -> (child,work) -> {
                        work.replyTo.tell(new Result(work.value));
                        return Behaviors.same();
                    },"worker-"+i));
                    for(int i=0;i<Math.min(32,count);i++) dispatch(ctx);
                } else if(command instanceof Result result) {
                    total+=result.value; received++;
                    if(received==count) { answer.complete(total); return Behaviors.stopped(); }
                    if(next<count) dispatch(ctx);
                }
                return Behaviors.same();
            }
            private void dispatch(ActorContext<Command> ctx) { workers.get(next).tell(new Work(++next,ctx.self())); }
        };
    }
    public static void main(String[] args) throws Exception {
        int count=args.length==0 ? 2500 : Integer.parseInt(args[0]);
        var pool=Executors.newFixedThreadPool(Math.max(2,Runtime.getRuntime().availableProcessors()));
        var system=new LocalActorSystem(ActorSystemConfig.builder("nested-workers").mailboxCapacity(128).build(),pool);
        var result=new CompletableFuture<Long>();
        try {
            system.spawn(coordinator(count,result),"coordinator").tell(new Start());
            long sum=result.get(30,TimeUnit.SECONDS);
            if(sum!=(long)count*(count+1)/2) throw new AssertionError(sum);
            System.out.println(count+" actors: sum="+sum);
        } finally { system.terminate(); pool.awaitTermination(5,TimeUnit.SECONDS); }
    }
}
