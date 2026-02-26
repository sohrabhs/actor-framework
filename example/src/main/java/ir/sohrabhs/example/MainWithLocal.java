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
import java.util.concurrent.TimeUnit;

/**
 * Demonstrates the Counter actor running on the local adapter.
 * No Akka. No external dependencies. Android-compatible.
 */
public class MainWithLocal {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Actor Framework Demo: Local Adapter ===\n");

        // --- Infrastructure Setup (would be in DI/composition root) ---

        ActorSystemConfig config = ActorSystemConfig.builder("counter-system")
            .mailboxCapacity(1000)
            .build();

        ActorSystem system = new LocalActorSystem(config);

        InMemoryEventStore<CounterEvent> eventStore = new InMemoryEventStore<>();
        InMemorySnapshotStore<CounterState> snapshotStore = new InMemorySnapshotStore<>();

        CounterBehaviorFactory counterFactory = new CounterBehaviorFactory(5); // snapshot every 5

        // --- Initialize Shard Region ---
        // This mirrors: ClusterSharding.init(Entity.of(typeKey, ctx -> behavior))

        ShardRegion<CounterCommand> counterShard = system.initShardRegion(
            "Counter",
            counterFactory::create,
            eventStore,
            snapshotStore
        );

        // --- Send Commands ---

        System.out.println("\n--- Sending commands to counter-42 ---");

        // These commands are routed to the entity actor "counter-42"
        // The actor is created lazily on first message
        counterShard.tell("42", new CounterCommand.Increment(10));
        counterShard.tell("42", new CounterCommand.Increment(5));
        counterShard.tell("42", new CounterCommand.Decrement(3));

        // Send to a different entity — proves entity isolation
        counterShard.tell("99", new CounterCommand.Increment(100));

        // Wait for async processing
        Thread.sleep(500);

        // --- Query Current Values ---

        System.out.println("\n--- Querying values ---");

        CompletableFuture<Integer> value42 = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(value42::complete));

        CompletableFuture<Integer> value99 = new CompletableFuture<>();
        counterShard.tell("99", new CounterCommand.GetValue(value99::complete));

        System.out.println("Counter 42 value: " + value42.get(2, TimeUnit.SECONDS));
        System.out.println("Counter 99 value: " + value99.get(2, TimeUnit.SECONDS));

        // --- Demonstrate Recovery ---

        System.out.println("\n--- Triggering more events to force snapshot ---");

        counterShard.tell("42", new CounterCommand.Increment(1));
        counterShard.tell("42", new CounterCommand.Increment(1));
        // At this point: 5 events for counter-42, should trigger snapshot

        Thread.sleep(500);

        // Verify events in store
        System.out.println("\nEvents for Counter|42: " + eventStore.allEvents("Counter|42"));
        System.out.println("Highest seqNr for Counter|42: " + eventStore.highestSequenceNumber("Counter|42"));

        CompletableFuture<Integer> finalValue = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(finalValue::complete));
        System.out.println("Counter 42 final value: " + finalValue.get(2, TimeUnit.SECONDS));

        // --- Cleanup ---
        System.out.println("\n--- Shutting down ---");
        system.terminate();
        System.out.println("Done.");
    }
}