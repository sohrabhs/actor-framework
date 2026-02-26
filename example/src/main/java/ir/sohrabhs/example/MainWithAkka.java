package ir.sohrabhs.example;

import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;
import ir.sohrabhs.actor.core.system.ActorSystemConfig;
import ir.sohrabhs.akka.AkkaActorSystemAdapter;
import ir.sohrabhs.example.actor.CounterBehaviorFactory;
import ir.sohrabhs.example.domain.CounterCommand;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Same Counter example, now running on Akka Typed + Akka Persistence.
 *
 * NOTICE: The domain code (CounterCommand, CounterEvent, CounterState, CounterBehaviorFactory)
 * is EXACTLY THE SAME. Only the system creation changes.
 *
 * This proves the abstraction works: swap adapter, keep domain.
 */
public class MainWithAkka {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Actor Framework Demo: Akka Adapter ===\n");

        // --- The ONLY difference: ActorSystem creation ---

        ActorSystemConfig config = ActorSystemConfig.builder("counter-system")
                .mailboxCapacity(1000)
                .build();

        // SWITCH: LocalActorSystem → AkkaActorSystemAdapter
        ActorSystem system = new AkkaActorSystemAdapter(config);

        CounterBehaviorFactory counterFactory = new CounterBehaviorFactory(5); // snapshot every 5

        // --- Initialize Shard Region ---
        // eventStore/snapshotStore are null because Akka manages its own persistence
        // via application.conf
        ShardRegion<CounterCommand> counterShard = system.initShardRegion(
                "Counter",
                counterFactory::create,
                null,
                null
        );

        // --- Send Commands ---

        System.out.println("\n--- Sending commands to counter-42 ---");

        counterShard.tell("42", new CounterCommand.Increment(10));
        counterShard.tell("42", new CounterCommand.Increment(5));
        counterShard.tell("42", new CounterCommand.Decrement(3));

        // Send to a different entity — proves entity isolation
        counterShard.tell("99", new CounterCommand.Increment(100));

        // Wait for async processing (Giving Akka a tiny bit more time to form the local cluster)
        Thread.sleep(1000);

        // --- Query Current Values ---

        System.out.println("\n--- Querying values ---");

        CompletableFuture<Integer> value42 = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(value42::complete));

        CompletableFuture<Integer> value99 = new CompletableFuture<>();
        counterShard.tell("99", new CounterCommand.GetValue(value99::complete));

        System.out.println("Counter 42 value: " + value42.get(2, TimeUnit.SECONDS));
        System.out.println("Counter 99 value: " + value99.get(2, TimeUnit.SECONDS));

        // --- Demonstrate Recovery & Snapshots ---

        System.out.println("\n--- Triggering more events to force snapshot ---");

        counterShard.tell("42", new CounterCommand.Increment(1));
        counterShard.tell("42", new CounterCommand.Increment(1));
        // At this point: 5 events for counter-42, should trigger snapshot

        Thread.sleep(1000);

        // NOTE: Unlike the Local adapter, we cannot easily call eventStore.allEvents() here.
        // Akka abstracts the journal away. To read events directly in Akka, you would use
        // the Akka Persistence Query API. However, the next GetValue call proves the state is correct.
        System.out.println("\n(Events are securely persisted in Akka's native journal)");

        CompletableFuture<Integer> finalValue = new CompletableFuture<>();
        counterShard.tell("42", new CounterCommand.GetValue(finalValue::complete));
        System.out.println("Counter 42 final value: " + finalValue.get(2, TimeUnit.SECONDS));

        // --- Cleanup ---
        System.out.println("\n--- Shutting down ---");
        system.terminate();
        System.out.println("Done.");
    }
}