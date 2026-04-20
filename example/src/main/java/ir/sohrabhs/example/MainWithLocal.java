package ir.sohrabhs.example;

import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;
import ir.sohrabhs.actor.core.system.ActorSystemConfig;
import ir.sohrabhs.example.actor.CounterBehaviorFactory;
import ir.sohrabhs.example.domain.CounterCommand;
import ir.sohrabhs.example.domain.CounterEvent;
import ir.sohrabhs.example.domain.CounterState;
import ir.sohrabhs.local.InMemoryEventStore;
import ir.sohrabhs.local.InMemorySnapshotStore;
import ir.sohrabhs.local.LocalActorSystem;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the Counter actor running on the local adapter.
 * Now includes stop, passivation, and recovery demonstration.
 */
public class MainWithLocal {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Actor Framework Demo: Local Adapter ===\n");

        // --- Infrastructure Setup ---

        ActorSystemConfig config = ActorSystemConfig.builder("actor-system")
                .mailboxCapacity(1000)
                .build();

        ActorSystem system = new LocalActorSystem(config, Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors())
        ));

        InMemoryEventStore<CounterEvent> eventStore = new InMemoryEventStore<>();
        InMemorySnapshotStore<CounterState> snapshotStore = new InMemorySnapshotStore<>();

        CounterBehaviorFactory counterFactory = new CounterBehaviorFactory(5);

        ShardRegion<CounterCommand> counterShard = system.initShardRegion(
                "Counter",
                counterFactory::create,
                eventStore,
                snapshotStore
        );

        // =====================================================
        // PHASE 1: Normal operations
        // =====================================================

        System.out.println("--- Phase 1: Normal operations ---");

        counterShard.tell("42", new CounterCommand.Increment(10));
        counterShard.tell("42", new CounterCommand.Increment(5));
        counterShard.tell("42", new CounterCommand.Decrement(3));
        counterShard.tell("99", new CounterCommand.Increment(100));

        Thread.sleep(500);

        CompletableFuture<Integer> value42 = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(value42::complete));

        CompletableFuture<Integer> value99 = new CompletableFuture<>();
        counterShard.tell("99", new CounterCommand.GetValue(value99::complete));

        System.out.println("Counter 42 value: " + value42.get(2, TimeUnit.SECONDS));  // 12
        System.out.println("Counter 99 value: " + value99.get(2, TimeUnit.SECONDS));  // 100
        System.out.println("Active entities: " + counterShard.activeEntityCount());     // 2
        System.out.println("Counter 42 active: " + counterShard.isActive("42"));       // true

        // =====================================================
        // PHASE 2: Stop via command (self-passivation)
        // =====================================================

        System.out.println("\n--- Phase 2: Stop counter-42 via Stop command ---");

        counterShard.tell("42", CounterCommand.Stop.INSTANCE);
        Thread.sleep(500);

        System.out.println("Counter 42 active after stop: " + counterShard.isActive("42")); // false
        System.out.println("Active entities after stop: " + counterShard.activeEntityCount()); // 1

        // =====================================================
        // PHASE 3: Recovery after stop
        // =====================================================

        System.out.println("\n--- Phase 3: Send message to stopped counter-42 (triggers recovery) ---");

        counterShard.tell("42", new CounterCommand.Increment(100));
        Thread.sleep(500);

        CompletableFuture<Integer> recoveredValue = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(recoveredValue::complete));

        System.out.println("Counter 42 value after recovery + increment: "
                + recoveredValue.get(2, TimeUnit.SECONDS)); // 112 (12 + 100)
        System.out.println("Counter 42 active after recovery: " + counterShard.isActive("42")); // true

        // =====================================================
        // PHASE 4: External stop (via shard region)
        // =====================================================

        System.out.println("\n--- Phase 4: External stop of counter-99 ---");

        boolean stopped = counterShard.stop("99");
        System.out.println("Counter 99 stopped: " + stopped);  // true
        System.out.println("Active entities: " + counterShard.activeEntityCount()); // 1

        // Recovery again
        counterShard.tell("99", new CounterCommand.Increment(50));
        Thread.sleep(500);

        CompletableFuture<Integer> recovered99 = new CompletableFuture<>();
        counterShard.tell("99", new CounterCommand.GetValue(recovered99::complete));
        System.out.println("Counter 99 value after recovery + increment: "
                + recovered99.get(2, TimeUnit.SECONDS)); // 150

        // =====================================================
        // PHASE 5: Stop all
        // =====================================================

        System.out.println("\n--- Phase 5: Stop all entities ---");

        System.out.println("Active before stopAll: " + counterShard.activeEntityCount());
        int stoppedCount = counterShard.stopAll();
        System.out.println("Stopped " + stoppedCount + " entities");
        System.out.println("Active after stopAll: " + counterShard.activeEntityCount()); // 0

        // =====================================================
        // PHASE 6: Verify events are still in store
        // =====================================================

        System.out.println("\n--- Phase 6: Event store inspection ---");
        System.out.println("Events for Counter|42: " + eventStore.allEvents("Counter|42"));
        System.out.println("Events for Counter|99: " + eventStore.allEvents("Counter|99"));
        System.out.println("Highest seqNr for Counter|42: " + eventStore.highestSequenceNumber("Counter|42"));
        System.out.println("Highest seqNr for Counter|99: " + eventStore.highestSequenceNumber("Counter|99"));

        // --- Cleanup ---
        System.out.println("\n--- Shutting down ---");
        system.terminate();
        System.out.println("Done.");
    }
}