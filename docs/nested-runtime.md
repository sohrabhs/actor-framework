# Local actor runtime and priority delivery

Each actor owns a bounded mailbox. Actors share the executor supplied to `LocalActorSystem`; no per-actor threads or eagerly allocated capacity-sized arrays are used. A drain yields after 32 messages or approximately 2 ms (a running handler cannot be preempted). Never block an actor waiting for another actor on the same pool.

`UrgentMessage` and `PriorityMessage.highPriority()` select the high lane. FIFO is preserved within each lane. Local processes at most eight consecutive high messages when normal messages are waiting. One eighth of capacity, for capacities >= 16, is reserved against normal admissions. Accepted traffic is never evicted to make room for newer traffic.

Use `ActorRef.tryTell` to handle `ACCEPTED`, `FULL`, `STOPPED`, or `EXECUTOR_UNAVAILABLE`. `tell` throws `DeliveryException` on Local rejection. This intentionally replaces the previous silent drop-oldest behavior. Retry only rejected admissions, without blocking actor workers. A successful admission is neither completion nor disk durability. Actor failure, explicit stop, process death, and application handler failure require their own business protocol. Stop cancels queued work; `InMemoryMailbox.stats()` counts accepted, rejected, processed and cancelled-on-stop traffic.

Akka uses the included `actor-framework.priority-mailbox` bounded stable priority configuration. Its underlying asynchronous delivery cannot synchronously confirm admission: `tryTell` reports `SENT_UNCONFIRMED`; overflow follows Akka dead-letter semantics. The Local fairness burst and reservation are not promised by the Akka mailbox. Use Local on Android and on a single server when synchronous admission is required; do not infer end-to-end delivery guarantees for a remote/cluster deployment.

Parent stop and system termination close descendants; duplicate names are rejected. Normal restart rebuilds the factory and stops the previous children. `onStop` hooks are for idempotent resource cleanup and may run on a stopping thread, so they must not mutate algorithm state. Persistent child journal IDs use the full parent path to avoid collisions between equal local child names. This changes the storage namespace of persistent children relative to the old implementation; migrate existing child journals before an upgrade that must recover them. Existing top-level entity journal IDs are unchanged.

Run:

```sh
mvn test
java -cp example/target/classes:actor-core/target/classes:actor-adapter-local/target/classes io.github.sohrabhs.example.NestedWorkers 2500
```

The example admits at most 32 requests in flight, collects `3126250`, then stops the actor tree. The adapter tests exercise concurrent producers, bounded overflow without eviction, priority/FIFO, fairness, rejected executor recovery, subtree cleanup, restart and Akka priority selection. These are JVM correctness checks, not a phone latency or thermal benchmark.

Local persistent behaviors can override `failureStrategy`; null retains the system decider. The engine selects STOP for its composed parent facade, closing descendants instead of replaying trading side effects after a handler failure. This Local override does not claim full Akka supervision parity.
