# Actor Abstraction Layer - Complete Architecture & Implementation

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        APPLICATION LAYER                            │
│                                                                     │
│   ┌─────────────┐  ┌──────────────┐  ┌─────────────────────────┐   │
│   │ CounterActor│  │ OrderActor   │  │ Any Domain Actor        │   │
│   │ (defines    │  │ (defines     │  │ (defines behavior via   │   │
│   │  behavior)  │  │  behavior)   │  │  core abstractions)     │   │
│   └──────┬──────┘  └──────┬───────┘  └────────────┬────────────┘   │
│          │                │                        │                │
├──────────┼────────────────┼────────────────────────┼────────────────┤
│          ▼                ▼                        ▼                │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                     ACTOR-CORE (Ports)                      │    │
│  │                                                             │    │
│  │  ┌───────────┐ ┌──────────┐ ┌───────────┐ ┌────────────┐   │    │
│  │  │ Behavior  │ │ ActorRef │ │ ActorCtx  │ │ Mailbox    │   │    │
│  │  │ <Command> │ │ <Cmd>    │ │ <Cmd>     │ │ (abstract) │   │    │
│  │  └───────────┘ └──────────┘ └───────────┘ └────────────┘   │    │
│  │                                                             │    │
│  │  ┌──────────────┐ ┌──────────────┐ ┌────────────────────┐  │    │
│  │  │ ShardEnvelope│ │ Shard        │ │ ActorSystem        │  │    │
│  │  │              │ │ Region       │ │ (Port)             │  │    │
│  │  └──────────────┘ └──────────────┘ └────────────────────┘  │    │
│  │                                                             │    │
│  │  ┌──────────────┐ ┌──────────────┐ ┌────────────────────┐  │    │
│  │  │ EventStore   │ │ Snapshot     │ │ Persistence        │  │    │
│  │  │ (Port)       │ │ Store (Port) │ │ Effect<E,S>        │  │    │
│  │  └──────────────┘ └──────────────┘ └────────────────────┘  │    │
│  │                                                             │    │
│  │  ┌───────────────────────────────────────────────────────┐  │    │
│  │  │ SupervisionStrategy (Restart / Stop / Escalate)       │  │    │
│  │  └───────────────────────────────────────────────────────┘  │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                     │
│                    CORE LAYER (Zero Dependencies)                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌─────────────────────────────┐  ┌──────────────────────────────┐  │
│  │   actor-adapter-akka/       │  │   actor-adapter-local/       │  │
│  │                             │  │                              │  │
│  │  AkkaActorSystemAdapter    │  │  LocalActorSystem            │  │
│  │  AkkaActorRefAdapter       │  │  LocalActorRef               │  │
│  │  AkkaBehaviorBridge        │  │  InMemoryMailbox             │  │
│  │  AkkaShardAdapter          │  │  LocalShardRegion            │  │
│  │  AkkaPersistenceAdapter    │  │  InMemoryEventStore          │  │
│  │  AkkaSnapshotAdapter       │  │  InMemorySnapshotStore       │  │
│  │                             │  │                              │  │
│  │  Maps 1:1 to Akka Typed    │  │  Runs on Android / tests     │  │
│  │  Cluster Sharding          │  │  No external deps            │  │
│  │  Event Sourcing             │  │                              │  │
│  └─────────────────────────────┘  └──────────────────────────────┘  │
│                                                                     │
│                    INFRASTRUCTURE LAYER (Adapters)                   │
└─────────────────────────────────────────────────────────────────────┘
```

## Message Flow Through The System

```
 Client Code
     │
     │  shardRegion.tell("counter-42", new Increment(5))
     ▼
 ┌──────────────┐
 │ ShardRegion  │ ── Port (core interface)
 │ .tell(id,msg)│
 └──────┬───────┘
        │
        ▼
 ┌──────────────────────┐
 │ Adapter resolves     │ ── e.g., AkkaShardAdapter or LocalShardRegion
 │ entityId → ActorRef  │
 │ (lazy creation)      │
 └──────┬───────────────┘
        │
        ▼
 ┌──────────────┐
 │ ActorRef     │ ── Port
 │ .tell(msg)   │
 └──────┬───────┘
        │
        ▼
 ┌──────────────┐     ┌─────────────┐
 │ Mailbox      │────▶│ Actor       │
 │ (ordered,    │     │ .onMessage()│
 │  async,      │     │             │
 │  single-     │     │ returns     │
 │  threaded    │     │ Behavior or │
 │  delivery)   │     │ Effect      │
 └──────────────┘     └──────┬──────┘
                             │
                    ┌────────┴────────┐
                    ▼                 ▼
            ┌────────────┐    ┌────────────┐
            │ Behavior   │    │ Persist    │
            │ (stateless │    │ Effect     │
            │  transition│    │ (event →   │
            │  like Akka │    │  persist → │
            │  Typed)    │    │  callback) │
            └────────────┘    └─────┬──────┘
                                    │
                              ┌─────┴──────┐
                              ▼            ▼
                        ┌──────────┐ ┌──────────┐
                        │EventStore│ │Snapshot  │
                        │(Port)    │ │Store     │
                        │          │ │(Port)    │
                        └──────────┘ └──────────┘
```

## Recovery Flow

```
 Actor Created (by ShardRegion or Parent)
     │
     ▼
 ┌──────────────────────────┐
 │ Check SnapshotStore      │
 │ for latest snapshot      │
 └──────────┬───────────────┘
            │
     ┌──────┴──────┐
     │  Found?     │
     ▼             ▼
   [YES]         [NO]
     │             │
     ▼             │
 ┌──────────┐      │
 │ Restore  │      │
 │ state    │      │
 │ from     │      │
 │ snapshot │      │
 └────┬─────┘      │
      │            │
      ▼            ▼
 ┌──────────────────────────┐
 │ Load events from         │
 │ EventStore               │
 │ (after snapshot seqNr    │
 │  or from 0)              │
 └──────────┬───────────────┘
            │
            ▼
 ┌──────────────────────────┐
 │ Replay each event        │
 │ through eventHandler     │
 │ to rebuild state         │
 └──────────┬───────────────┘
            │
            ▼
 ┌──────────────────────────┐
 │ Actor ready to receive   │
 │ commands                 │
 └──────────────────────────┘
```

---

## 📁 Module Structure

```
actor-framework/
├── actor-core/
│   └── src/main/java/ir/sohrabhs/actor/core/
│       ├── actor/
│       │   ├── Behavior.java
│       │   ├── BehaviorFactory.java
│       │   ├── ActorRef.java
│       │   ├── ActorContext.java
│       │   ├── ActorPath.java
│       │   ├── ActorIdentity.java
│       │   └── SupervisionStrategy.java
│       ├── system/
│       │   ├── ActorSystem.java
│       │   └── ActorSystemConfig.java
│       ├── shard/
│       │   ├── ShardRegion.java
│       │   ├── ShardEnvelope.java
│       │   └── EntityIdExtractor.java
│       ├── persistence/
│       │   ├── PersistentBehavior.java
│       │   ├── Effect.java
│       │   ├── EventStore.java
│       │   ├── SnapshotStore.java
│       │   ├── PersistedEvent.java
│       │   └── PersistedSnapshot.java
│       └── mailbox/
│           └── Mailbox.java
│
├── actor-adapter-local/
│   └── src/main/java/ir/sohrabhs/local/
│       ├── LocalActorSystem.java
│       ├── LocalActorRef.java
│       ├── LocalActorContext.java
│       ├── LocalShardRegion.java
│       ├── InMemoryMailbox.java
│       ├── InMemoryEventStore.java
│       ├── InMemorySnapshotStore.java
│       └── LocalSupervisor.java
│
├── actor-adapter-akka/
│   └── src/main/java/ir/sohrabhs/akka/
│       ├── AkkaActorSystemAdapter.java
│       ├── AkkaActorRefAdapter.java
│       ├── AkkaBehaviorBridge.java
│       ├── AkkaShardAdapter.java
│       ├── AkkaPersistenceBridge.java
│       └── AkkaSupervisionMapper.java
│
└── example/
    └── src/main/java/ir/sohrabhs/example/
        ├── domain/
        │   ├── CounterCommand.java
        │   ├── CounterEvent.java
        │   └── CounterState.java
        ├── actor/
        │   └── CounterBehaviorFactory.java
        ├── MainWithLocal.java
        └── MainWithAkka.java
```

---

## 🗺️ Mapping Table: Core → Akka

```
┌─────────────────────────────┬────────────────────────────────────────────┐
│ Our Core Abstraction        │ Akka Typed Equivalent                      │
├─────────────────────────────┼────────────────────────────────────────────┤
│ Behavior<C>                 │ akka.actor.typed.Behavior<C>               │
│ Behaviors.same()            │ Behaviors.same()                           │
│ Behaviors.stopped()         │ Behaviors.stopped()                        │
│ BehaviorFactory<C>          │ Behaviors.setup(ctx -> ...)                │
│ ActorRef<C>.tell()          │ ActorRef<C>.tell()                         │
│ ActorContext<C>.spawn()     │ ActorContext<C>.spawn()                    │
│ ActorContext<C>.self()      │ ActorContext<C>.getSelf()                  │
│ ActorPath                   │ akka.actor.ActorPath                       │
│ ActorIdentity               │ EntityTypeKey + entityId                   │
│ ShardRegion<C>              │ ClusterSharding + EntityRef                │
│ ShardRegion.tell(id, msg)   │ sharding.entityRefFor(key, id).tell(msg)  │
│ PersistentBehavior<C,E,S>   │ EventSourcedBehavior<C,E,S>               │
│ PB.emptyState()             │ ESB.emptyState()                           │
│ PB.onCommand(state, cmd)    │ ESB.commandHandler()(state, cmd)           │
│ PB.onEvent(state, evt)      │ ESB.eventHandler()(state, evt)             │
│ Effect.persist(event)       │ Effect().persist(event)                    │
│ Effect.none().thenRun()     │ Effect().none().thenRun()                  │
│ EventStore (Port)           │ Akka Persistence Journal plugin            │
│ SnapshotStore (Port)        │ Akka Persistence Snapshot plugin           │
│ SupervisionStrategy.RESTART │ SupervisorStrategy.restart()               │
│ SupervisionDecider          │ SupervisorStrategy with Decider            │
│ ActorSystem.spawn()         │ ActorSystem.systemActorOf()                │
│ ActorSystem.terminate()     │ ActorSystem.terminate()                    │
│ Mailbox                     │ Akka Dispatcher + Mailbox                  │
└─────────────────────────────┴────────────────────────────────────────────┘
```

---

## 🧪 Verification: Domain Isolation Proof

```
File: CounterBehaviorFactory.java

Imports:
  ✅ ir.sohrabhs.actor.core.actor.ActorContext
  ✅ ir.sohrabhs.actor.core.actor.ActorIdentity
  ✅ ir.sohrabhs.actor.core.persistence.Effect
  ✅ ir.sohrabhs.actor.core.persistence.PersistentBehavior
  ✅ ir.sohrabhs.example.domain.CounterCommand
  ✅ ir.sohrabhs.example.domain.CounterEvent
  ✅ ir.sohrabhs.example.domain.CounterState

  ❌ ZERO akka.* imports
  ❌ ZERO ir.sohrabhs.local.* imports
  ❌ ZERO ir.sohrabhs.akka.* imports
  ❌ ZERO framework-specific imports

Conclusion: Domain is 100% portable.
Swap LocalActorSystem → AkkaActorSystemAdapter.
CounterBehaviorFactory compiles and runs WITHOUT ANY CHANGES.
```

---

[AkkaActorContextAdapter.java](actor-adapter-akka/src/main/java/ir/sohrabhs/akka/AkkaActorContextAdapter.java)
[AkkaActorRefAdapter.java](actor-adapter-akka/src/main/java/ir/sohrabhs/akka/AkkaActorRefAdapter.java)
[AkkaActorSystemAdapter.java](actor-adapter-akka/src/main/java/ir/sohrabhs/akka/AkkaActorSystemAdapter.java)
[AkkaBehaviorBridge.java](actor-adapter-akka/src/main/java/ir/sohrabhs/akka/AkkaBehaviorBridge.java)
[AkkaPersistenceBridge.java](actor-adapter-akka/src/main/java/ir/sohrabhs/akka/AkkaPersistenceBridge.java)
[AkkaShardAdapter.java](actor-adapter-akka/src/main/java/ir/sohrabhs/akka/AkkaShardAdapter.java)
[InMemoryEventStore.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/InMemoryEventStore.java)
[InMemoryMailbox.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/InMemoryMailbox.java)
[InMemorySnapshotStore.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/InMemorySnapshotStore.java)
[LocalActorCell.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/LocalActorCell.java)
[LocalActorContext.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/LocalActorContext.java)
[LocalActorRef.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/LocalActorRef.java)
[LocalActorSystem.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/LocalActorSystem.java)
[LocalPersistentActorCell.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/LocalPersistentActorCell.java)
[LocalShardRegion.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/LocalShardRegion.java)
[PersistentBehaviorWithContext.java](actor-adapter-local/src/main/java/ir/sohrabhs/local/PersistentBehaviorWithContext.java)
[ActorContext.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/ActorContext.java)
[ActorIdentity.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/ActorIdentity.java)
[ActorPath.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/ActorPath.java)
[ActorRef.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/ActorRef.java)
[Behavior.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/Behavior.java)
[BehaviorFactory.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/BehaviorFactory.java)
[Behaviors.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/Behaviors.java)
[SupervisionDecider.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/SupervisionDecider.java)
[SupervisionStrategy.java](actor-core/src/main/java/ir/sohrabhs/actor/core/actor/SupervisionStrategy.java)
[Mailbox.java](actor-core/src/main/java/ir/sohrabhs/actor/core/mailbox/Mailbox.java)
[Effect.java](actor-core/src/main/java/ir/sohrabhs/actor/core/persistence/Effect.java)
[EventStore.java](actor-core/src/main/java/ir/sohrabhs/actor/core/persistence/EventStore.java)
[PersistedEvent.java](actor-core/src/main/java/ir/sohrabhs/actor/core/persistence/PersistedEvent.java)
[PersistedSnapshot.java](actor-core/src/main/java/ir/sohrabhs/actor/core/persistence/PersistedSnapshot.java)
[PersistentBehavior.java](actor-core/src/main/java/ir/sohrabhs/actor/core/persistence/PersistentBehavior.java)
[SnapshotStore.java](actor-core/src/main/java/ir/sohrabhs/actor/core/persistence/SnapshotStore.java)
[EntityIdExtractor.java](actor-core/src/main/java/ir/sohrabhs/actor/core/shard/EntityIdExtractor.java)
[ShardEnvelope.java](actor-core/src/main/java/ir/sohrabhs/actor/core/shard/ShardEnvelope.java)
[ShardRegion.java](actor-core/src/main/java/ir/sohrabhs/actor/core/shard/ShardRegion.java)
[ActorSystem.java](actor-core/src/main/java/ir/sohrabhs/actor/core/system/ActorSystem.java)
[ActorSystemConfig.java](actor-core/src/main/java/ir/sohrabhs/actor/core/system/ActorSystemConfig.java)
[CounterBehaviorFactory.java](example/src/main/java/ir/sohrabhs/example/actor/CounterBehaviorFactory.java)
[CounterCommand.java](example/src/main/java/ir/sohrabhs/example/domain/CounterCommand.java)
[CounterEvent.java](example/src/main/java/ir/sohrabhs/example/domain/CounterEvent.java)
[CounterState.java](example/src/main/java/ir/sohrabhs/example/domain/CounterState.java)
[MainWithAkka.java](example/src/main/java/ir/sohrabhs/example/MainWithAkka.java)
[MainWithLocal.java](example/src/main/java/ir/sohrabhs/example/MainWithLocal.java)

```java
// actor-adapter-akka/src/main/java/com/actor/akka/AkkaActorContextAdapter.java
package ir.sohrabhs.akka;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;

/**
 * Adapts Akka's ActorContext to our ActorContext interface.
 */
public final class AkkaActorContextAdapter<C> implements ActorContext<C> {

    private final akka.actor.typed.javadsl.ActorContext<C> akkaCtx;

    public AkkaActorContextAdapter(akka.actor.typed.javadsl.ActorContext<C> akkaCtx) {
        this.akkaCtx = akkaCtx;
    }

    @Override
    public ActorRef<C> self() {
        return new AkkaActorRefAdapter<>(akkaCtx.getSelf());
    }

    @Override
    public ActorPath path() {
        return ActorPath.of(akkaCtx.getSelf().path().toString());
    }

    @Override
    public ActorIdentity identity() {
        // For shard entities, this would be set differently
        String name = akkaCtx.getSelf().path().name();
        String parent = akkaCtx.getSelf().path().parent().name();
        return new ActorIdentity(parent, name);
    }

    @Override
    public <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName) {
        akka.actor.typed.Behavior<M> akkaBehavior = AkkaBehaviorBridge.toBehavior(factory);
        akka.actor.typed.ActorRef<M> akkaRef = akkaCtx.spawn(akkaBehavior, childName);
        return new AkkaActorRefAdapter<>(akkaRef);
    }

    /**
     * Spawn a persistent child actor in Akka.
     *
     * CRITICAL IMPLEMENTATION NOTE:
     * When using Akka Persistence, the EventStore and SnapshotStore parameters
     * are IGNORED because Akka manages its own persistence plugins configured
     * in application.conf.
     *
     * This is intentional - the ports exist for:
     * 1. LocalActorSystem (which needs explicit stores)
     * 2. Testing (where you want in-memory stores)
     * 3. Alternative adapters (Orleans, custom implementations)
     *
     * For Akka, we delegate to Akka's persistence mechanism which reads
     * configuration from application.conf:
     * - akka.persistence.journal.plugin = "jdbc-journal" (or cassandra, etc.)
     * - akka.persistence.snapshot-store.plugin = "jdbc-snapshot-store"
     */
    @Override
    public <M, E, S> ActorRef<M> spawnPersistent(
            PersistentBehaviorFactory<M, E, S> persistentBehaviorFactory,
            String childName,
            EventStore<E> eventStore,      // Ignored in Akka adapter
            SnapshotStore<S> snapshotStore) { // Ignored in Akka adapter

        // Create our PersistentBehavior
        PersistentBehavior<M, E, S> ourBehavior = persistentBehaviorFactory.create(childName);

        // Bridge to Akka EventSourcedBehavior
        akka.actor.typed.Behavior<M> akkaBehavior = AkkaPersistenceBridge.toBehavior(ourBehavior);

        // Spawn the actor in Akka
        akka.actor.typed.ActorRef<M> akkaRef = akkaCtx.spawn(akkaBehavior, childName);

        return new AkkaActorRefAdapter<>(akkaRef);
    }

    @Override
    public void stop(ActorRef<?> child) {
        if (child instanceof AkkaActorRefAdapter) {
            akkaCtx.stop(((AkkaActorRefAdapter<?>) child).unwrap());
        }
    }

    @Override
    public <M> ActorRef<M> getChild(String childName) {
        // Akka Typed doesn't have direct child lookup by name in the public API.
        // In practice, you'd track children in a Map within the behavior.
        return null;
    }

    @Override
    public void log(String message, Object... args) {
        akkaCtx.getLog().info(String.format(message, args));
    }
}
```
```java
package ir.sohrabhs.akka;


import ir.sohrabhs.actor.core.actor.ActorIdentity;
import ir.sohrabhs.actor.core.actor.ActorPath;
import ir.sohrabhs.actor.core.actor.ActorRef;

/**
 * Wraps Akka's ActorRef in our ActorRef interface.
 */
public final class AkkaActorRefAdapter<C> implements ActorRef<C> {

    private final akka.actor.typed.ActorRef<C> akkaRef;

    public AkkaActorRefAdapter(akka.actor.typed.ActorRef<C> akkaRef) {
        this.akkaRef = akkaRef;
    }

    @Override
    public void tell(C message) {
        akkaRef.tell(message);
    }

    @Override
    public ActorPath path() {
        return ActorPath.of(akkaRef.path().toString());
    }

    @Override
    public ActorIdentity identity() {
        String name = akkaRef.path().name();
        String parent = akkaRef.path().parent().name();
        return new ActorIdentity(parent, name);
    }

    /** Unwrap for Akka-internal use */
    akka.actor.typed.ActorRef<C> unwrap() {
        return akkaRef;
    }
}
```
```java
package ir.sohrabhs.akka;

import akka.actor.typed.javadsl.Behaviors;
import ir.sohrabhs.actor.core.actor.ActorRef;
import ir.sohrabhs.actor.core.actor.BehaviorFactory;
import ir.sohrabhs.actor.core.actor.SupervisionDecider;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;
import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;
import ir.sohrabhs.actor.core.system.ActorSystemConfig;

/**
 * Akka Typed adapter for our ActorSystem interface.
 *
 * When you switch to this:
 * - Actors run on Akka's dispatcher
 * - Persistence uses Akka Persistence (Cassandra, JDBC, etc.)
 * - Sharding uses Akka Cluster Sharding
 * - Domain code: ZERO changes
 */
public final class AkkaActorSystemAdapter implements ActorSystem {

    private final akka.actor.typed.ActorSystem<Void> akkaSystem;
    private final ActorSystemConfig config;

    public AkkaActorSystemAdapter(ActorSystemConfig config) {
        this.config = config;
        this.akkaSystem = akka.actor.typed.ActorSystem.create(
            Behaviors.empty(),
            config.systemName()
        );
    }

    /** Use existing Akka system */
    public AkkaActorSystemAdapter(akka.actor.typed.ActorSystem<Void> akkaSystem, ActorSystemConfig config) {
        this.akkaSystem = akkaSystem;
        this.config = config;
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name) {
        akka.actor.typed.Behavior<C> akkaBehavior = AkkaBehaviorBridge.toBehavior(factory);
        akka.actor.typed.ActorRef<C> akkaRef = akkaSystem.systemActorOf(akkaBehavior, name, akka.actor.typed.Props.empty());
        return new AkkaActorRefAdapter<>(akkaRef);
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name, SupervisionDecider decider) {
        akka.actor.typed.Behavior<C> akkaBehavior = AkkaBehaviorBridge.toBehavior(factory);

        // Wrap with Akka supervision
        akka.actor.typed.Behavior<C> supervised = akka.actor.typed.javadsl.Behaviors.supervise(akkaBehavior)
            .onFailure(Exception.class, mapStrategy(decider));

        akka.actor.typed.ActorRef<C> akkaRef = akkaSystem.systemActorOf(supervised, name, akka.actor.typed.Props.empty());
        return new AkkaActorRefAdapter<>(akkaRef);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <C, E, S> ShardRegion<C> initShardRegion(
            String typeName,
            PersistentBehaviorFactory<C, E, S> behaviorFactory,
            EventStore<E> eventStore,      // Ignored — Akka uses its own persistence
            SnapshotStore<S> snapshotStore) { // Ignored — Akka uses its own snapshot store

        // NOTE: eventStore and snapshotStore are ignored when using Akka.
        // Akka Persistence manages its own journal and snapshot store
        // configured via application.conf (Cassandra, JDBC, etc.)
        // This is intentional: the ports exist for local/Android adapters.

        return new AkkaShardAdapter<>(
            akkaSystem,
            typeName,
            (Class<C>) Object.class, // In real code, pass the actual class
            entityId -> (PersistentBehavior<C, Object, Object>)(PersistentBehavior) behaviorFactory.create(entityId)
        );
    }

    @Override
    public String name() {
        return config.systemName();
    }

    @Override
    public void terminate() {
        akkaSystem.terminate();
    }

    public akka.actor.typed.ActorSystem<Void> unwrap() {
        return akkaSystem;
    }

    private akka.actor.typed.SupervisorStrategy mapStrategy(SupervisionDecider decider) {
        // Simple mapping — in production, you'd check specific exception types
        return akka.actor.typed.SupervisorStrategy.restart();
    }
}
```
```java
package ir.sohrabhs.akka;

import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.ReceiveBuilder;
import ir.sohrabhs.actor.core.actor.*;

/**
 * Bridges our core Behavior<C> to Akka Typed's AbstractBehavior<C>.
 *
 * DESIGN REASONING:
 * Our Behavior.onMessage returns a new Behavior (functional style).
 * Akka Typed also works this way. This bridge:
 * 1. Wraps our BehaviorFactory in Akka's Behaviors.setup()
 * 2. Delegates message handling to our Behavior.onMessage()
 * 3. Translates the returned Behavior back to Akka's Behavior
 *
 * This is a one-way bridge: Akka calls our code, never the reverse.
 */
public final class AkkaBehaviorBridge {

    private AkkaBehaviorBridge() {}

    /**
     * Convert our BehaviorFactory into an Akka Typed Behavior.
     */
    public static <C> akka.actor.typed.Behavior<C> toBehavior(BehaviorFactory<C> factory) {
        return akka.actor.typed.javadsl.Behaviors.setup(akkaCtx -> {
            // Wrap Akka's context in our ActorContext
            ActorContext<C> ourContext = new AkkaActorContextAdapter<>(akkaCtx);

            // Create our behavior
            Behavior<C> ourBehavior = factory.create(ourContext);

            // Return a wrapper that delegates to our behavior
            return new AkkaBehaviorWrapper<>(akkaCtx, ourBehavior, ourContext);
        });
    }

    /**
     * Akka Behavior that wraps our Behavior.
     */
    private static final class AkkaBehaviorWrapper<C> extends AbstractBehavior<C> {

        private Behavior<C> currentBehavior;
        private final ActorContext<C> ourContext;

        AkkaBehaviorWrapper(
                akka.actor.typed.javadsl.ActorContext<C> akkaCtx,
                Behavior<C> initialBehavior,
                ActorContext<C> ourContext) {
            super(akkaCtx);
            this.currentBehavior = initialBehavior;
            this.ourContext = ourContext;
        }

        @Override
        public Receive<C> createReceive() {
            return newReceiveBuilder()
                .onAnyMessage(this::onMessage)
                .build();
        }

        private akka.actor.typed.Behavior<C> onMessage(C message) {
            Behavior<C> next = currentBehavior.onMessage(ourContext, message);

            if (Behaviors.isStopped(next)) {
                return akka.actor.typed.javadsl.Behaviors.stopped();
            }

            if (!Behaviors.isSame(next)) {
                currentBehavior = next;
            }

            return this;
        }
    }
}
```
```java
package ir.sohrabhs.akka;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;
import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;

/**
 * Bridges our PersistentBehavior to Akka Typed Persistence's EventSourcedBehavior.
 *
 * DESIGN REASONING:
 * Akka Typed Persistence has EventSourcedBehavior with:
 * - emptyState()
 * - commandHandler() → returns akka Effect
 * - eventHandler() → returns new State
 *
 * Our PersistentBehavior has the exact same structure.
 * This bridge translates:
 * - Our Effect → Akka's Effect
 * - Our onCommand → Akka's commandHandler
 * - Our onEvent → Akka's eventHandler
 *
 * The domain code stays completely unaware of Akka.
 *
 * @param <C> Command
 * @param <E> Event
 * @param <S> State
 */
public final class AkkaPersistenceBridge {

    private AkkaPersistenceBridge() {}

    /**
     * Convert our PersistentBehavior to Akka's EventSourcedBehavior.
     */
    public static <C, E, S> Behavior<C> toBehavior(PersistentBehavior<C, E, S> ourBehavior) {
        return Behaviors.setup(akkaCtx -> {
            ActorContext<C> ourContext = new AkkaActorContextAdapter<>(akkaCtx);

            PersistenceId persistenceId = PersistenceId.ofUniqueId(
                ourBehavior.identity().persistenceId()
            );

            return new EventSourcedBehavior<C, E, S>(persistenceId) {

                @Override
                public S emptyState() {
                    return ourBehavior.emptyState();
                }

                @Override
                public CommandHandler<C, E, S> commandHandler() {
                    return newCommandHandlerBuilder()
                        .forAnyState()
                            .onAnyCommand((state, command) -> translateEffect(
                                            ourBehavior.onCommand(state, command),
                                            state, Effect()
                                    )
                            );
                }

                @Override
                public EventHandler<S, E> eventHandler() {
                    return newEventHandlerBuilder()
                        .forAnyState()
                        .onAnyEvent((state, event) -> ourBehavior.onEvent(state, event));
                }

                @Override
                public SignalHandler<S> signalHandler() {
                    return newSignalHandlerBuilder()
                            .onSignal(
                                    akka.persistence.typed.RecoveryCompleted.instance(),
                                    state -> {
                                        ourBehavior.onRecoveryComplete(ourContext, state);
                                    }
                            )
                            .build();
                }

                @Override
                public boolean shouldSnapshot(S state, E event, long sequenceNr) {
                    int interval = ourBehavior.snapshotEvery();
                    return interval > 0 && sequenceNr % interval == 0;
                }
            };
        });
    }

    /**
     * Translate our Effect to Akka's Effect.
     */
    private static <C, E, S> akka.persistence.typed.javadsl.Effect<E, S> translateEffect(
            ir.sohrabhs.actor.core.persistence.Effect<E, S> ourEffect,
            S currentState,
            EffectFactories<E, S> akkaEffectFactory) {

        if (ourEffect.isUnhandled()) {
            return akkaEffectFactory.unhandled();
        }

        if (ourEffect.events().isEmpty()) {
            if (ourEffect.sideEffect() != null) {
                ourEffect.sideEffect().apply(currentState);
            }
            return akkaEffectFactory.none();
        }

        if (ourEffect.events().size() == 1) {
            akka.persistence.typed.javadsl.EffectBuilder<E, S> effect =
                    akkaEffectFactory.persist(ourEffect.events().get(0));

            if (ourEffect.sideEffect() != null) {
                return effect.thenRun(newState -> {
                    ourEffect.sideEffect().apply(newState);
                });
            }
            return effect.thenNoReply();
        }

        // Multiple events
        akka.persistence.typed.javadsl.EffectBuilder<E, S> effect =
                akkaEffectFactory.persist(ourEffect.events());

        if (ourEffect.sideEffect() != null) {
            return effect.thenRun(newState -> {
                ourEffect.sideEffect().apply(newState);
            });
        }
        return effect.thenNoReply();
    }
}
```
```java
package ir.sohrabhs.akka;

import akka.actor.typed.ActorSystem;
import akka.cluster.sharding.typed.javadsl.ClusterSharding;
import akka.cluster.sharding.typed.javadsl.Entity;
import akka.cluster.sharding.typed.javadsl.EntityRef;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import ir.sohrabhs.actor.core.actor.ActorIdentity;
import ir.sohrabhs.actor.core.actor.ActorPath;
import ir.sohrabhs.actor.core.actor.ActorRef;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.shard.ShardRegion;

import java.util.function.Function;

/**
 * Adapts Akka Cluster Sharding to our ShardRegion interface.
 *
 * DESIGN REASONING:
 * This is the production adapter. When you migrate from local to cluster:
 * 1. Replace LocalActorSystem with AkkaActorSystemAdapter
 * 2. ShardRegion now uses Akka Cluster Sharding internally
 * 3. PersistentBehavior is bridged to Akka Persistence via AkkaPersistenceBridge
 * 4. NO CHANGES to domain or application code
 *
 * @param <C> Command type
 */
public final class AkkaShardAdapter<C> implements ShardRegion<C> {

    private final String typeName;
    private final ClusterSharding sharding;
    private final EntityTypeKey<C> typeKey;

    public AkkaShardAdapter(
            ActorSystem<?> akkaSystem,
            String typeName,
            Class<C> commandClass,
            Function<String, PersistentBehavior<C, ?, ?>> behaviorFactory) {

        this.typeName = typeName;
        this.sharding = ClusterSharding.get(akkaSystem);
        this.typeKey = EntityTypeKey.create(commandClass, typeName);

        // Initialize the shard region
        sharding.init(
            Entity.of(typeKey, entityContext -> {
                String entityId = entityContext.getEntityId();
                PersistentBehavior<C, ?, ?> ourBehavior = behaviorFactory.apply(entityId);
                return AkkaPersistenceBridge.toBehavior(ourBehavior);
            })
        );
    }

    @Override
    public void tell(String entityId, C message) {
        EntityRef<C> entityRef = sharding.entityRefFor(typeKey, entityId);
        entityRef.tell(message);
    }

    @Override
    public ActorRef<C> entityRefFor(String entityId) {
        EntityRef<C> entityRef = sharding.entityRefFor(typeKey, entityId);
        // Wrap in our ActorRef
        return new ActorRef<C>() {
            @Override
            public void tell(C message) {
                entityRef.tell(message);
            }

            @Override
            public ActorPath path() {
                return new ActorIdentity(typeName, entityId).toActorPath();
            }

            @Override
            public ActorIdentity identity() {
                return new ActorIdentity(typeName, entityId);
            }
        };
    }

    @Override
    public String typeName() {
        return typeName;
    }
}
```
```java
package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistedEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory event store for testing and local/Android execution.
 *
 * Thread-safe: multiple actors may share one EventStore instance.
 * Each persistenceId has its own isolated journal.
 */
public final class InMemoryEventStore<E> implements EventStore<E> {

    private final ConcurrentHashMap<String, List<PersistedEvent<E>>> journals =
        new ConcurrentHashMap<>();

    @Override
    public void persist(String persistenceId, long sequenceNumber, E event) {
        journals.computeIfAbsent(persistenceId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(new PersistedEvent<>(persistenceId, sequenceNumber, event, System.currentTimeMillis()));
    }

    @Override
    public List<PersistedEvent<E>> loadEvents(String persistenceId, long fromSequenceNumber) {
        List<PersistedEvent<E>> journal = journals.get(persistenceId);
        if (journal == null) {
            return Collections.emptyList();
        }
        synchronized (journal) {
            return journal.stream()
                .filter(e -> e.sequenceNumber() > fromSequenceNumber)
                .collect(Collectors.toList());
        }
    }

    @Override
    public long highestSequenceNumber(String persistenceId) {
        List<PersistedEvent<E>> journal = journals.get(persistenceId);
        if (journal == null || journal.isEmpty()) {
            return 0;
        }
        synchronized (journal) {
            return journal.get(journal.size() - 1).sequenceNumber();
        }
    }

    /** For testing: inspect all events */
    public List<PersistedEvent<E>> allEvents(String persistenceId) {
        return loadEvents(persistenceId, -1);
    }
}
```
```java
package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.mailbox.Mailbox;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe, single-consumer mailbox using a lock-free queue.
 *
 * DESIGN REASONING:
 * - ConcurrentLinkedQueue for lock-free enqueue from any thread
 * - Single-threaded processing via AtomicBoolean scheduling flag
 * - This guarantees: at most one message is processed at a time
 * - Android-friendly: no heavy locking, no Java 8+ API beyond ConcurrentLinkedQueue
 *
 * This is the key guarantee of the Actor Model: no concurrent processing within one actor.
 */
public final class InMemoryMailbox<C> implements Mailbox<C> {

    private final ConcurrentLinkedQueue<C> queue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final ExecutorService executor;
    private volatile MessageHandler<C> handler;
    private volatile boolean stopped = false;

    public InMemoryMailbox(ExecutorService executor) {
        this.executor = executor;
    }

    @Override
    public void enqueue(C message) {
        if (stopped) {
            return; // silently drop — matches Akka's dead letter behavior
        }
        queue.offer(message);
        scheduleProcessing();
    }

    @Override
    public void start(MessageHandler<C> handler) {
        this.handler = handler;
        scheduleProcessing();
    }

    @Override
    public void stop() {
        this.stopped = true;
        queue.clear();
    }

    @Override
    public boolean hasPending() {
        return !queue.isEmpty();
    }

    /**
     * Ensures only one processing task is scheduled at a time.
     * This is the mechanism that provides single-threaded illusion.
     */
    private void scheduleProcessing() {
        if (handler == null || stopped) return;

        if (scheduled.compareAndSet(false, true)) {
            executor.submit(this::processMessages);
        }
    }

    private void processMessages() {
        try {
            // Process a batch of messages (up to 10) before re-scheduling.
            // This prevents starvation of other actors sharing the executor.
            int processed = 0;
            C message;
            while (!stopped && processed < 10 && (message = queue.poll()) != null) {
                try {
                    handler.handle(message);
                } catch (Exception e) {
                    // Supervision handles this — for now, log and continue
                    System.err.println("[Mailbox] Exception processing message: " + e.getMessage());
                    e.printStackTrace();
                }
                processed++;
            }
        } finally {
            scheduled.set(false);
            // If there are still pending messages, re-schedule
            if (!stopped && !queue.isEmpty()) {
                scheduleProcessing();
            }
        }
    }
}
```
```java
package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.persistence.PersistedSnapshot;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory snapshot store.
 */
public final class InMemorySnapshotStore<S> implements SnapshotStore<S> {

    private final ConcurrentHashMap<String, PersistedSnapshot<S>> snapshots =
        new ConcurrentHashMap<>();

    @Override
    public void save(String persistenceId, long sequenceNumber, S state) {
        snapshots.put(persistenceId,
            new PersistedSnapshot<>(persistenceId, sequenceNumber, state, System.currentTimeMillis()));
    }

    @Override
    public Optional<PersistedSnapshot<S>> loadLatest(String persistenceId) {
        return Optional.ofNullable(snapshots.get(persistenceId));
    }

    @Override
    public void deleteUpTo(String persistenceId, long maxSequenceNumber) {
        PersistedSnapshot<S> current = snapshots.get(persistenceId);
        if (current != null && current.sequenceNumber() <= maxSequenceNumber) {
            snapshots.remove(persistenceId);
        }
    }
}
```
```java
package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;

/**
 * The runtime cell for a local actor. Holds the current behavior and processes messages.
 *
 * DESIGN REASONING:
 * In Akka, each actor has an ActorCell that manages:
 * - Current behavior
 * - Mailbox processing
 * - Supervision
 * - Behavior switching
 *
 * We mirror this. The cell receives messages from the mailbox and delegates to the behavior.
 * If the behavior returns a new behavior, we switch. If it returns stopped, we stop.
 */
final class LocalActorCell<C> {

    private final LocalActorRef<C> self;
    private final ActorContext<C> context;
    private volatile Behavior<C> currentBehavior;
    private final SupervisionDecider supervisionDecider;

    LocalActorCell(
            LocalActorRef<C> self,
            ActorContext<C> context,
            Behavior<C> initialBehavior,
            SupervisionDecider supervisionDecider) {
        this.self = self;
        this.context = context;
        this.currentBehavior = initialBehavior;
        this.supervisionDecider = supervisionDecider;
    }

    /**
     * Called by the mailbox for each message. Guaranteed single-threaded by mailbox.
     */
    void processMessage(C message) {
        try {
            Behavior<C> next = currentBehavior.onMessage(context, message);

            if (Behaviors.isStopped(next)) {
                self.mailbox().stop();
                return;
            }

            if (!Behaviors.isSame(next)) {
                currentBehavior = next;
            }
        } catch (Exception e) {
            handleFailure(e, message);
        }
    }

    private void handleFailure(Exception e, C message) {
        SupervisionStrategy strategy = supervisionDecider.decide(e);
        switch (strategy) {
            case RESTART:
                context.log("Actor restarting due to: %s", e.getMessage());
                // In a real restart, we'd re-run the behavior factory.
                // For simplicity in local adapter, we keep current behavior.
                break;
            case STOP:
                context.log("Actor stopping due to: %s", e.getMessage());
                self.mailbox().stop();
                break;
            case RESUME:
                context.log("Actor resuming after: %s", e.getMessage());
                break;
            case ESCALATE:
                context.log("Escalating failure: %s", e.getMessage());
                throw new RuntimeException("Escalated from actor " + self.path(), e);
        }
    }
}
```
```java
// actor-adapter-local/src/main/java/com/actor/local/LocalActorContext.java
package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Local implementation of ActorContext.
 * Manages child actors and provides spawning capabilities.
 */
public final class LocalActorContext<C> implements ActorContext<C> {

    private final ActorRef<C> self;
    private final ActorPath path;
    private final ActorIdentity identity;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ActorRef<?>> children = new ConcurrentHashMap<>();
    private final SupervisionDecider supervisionDecider;

    public LocalActorContext(
            ActorRef<C> self,
            ActorPath path,
            ActorIdentity identity,
            ExecutorService executor,
            SupervisionDecider supervisionDecider) {
        this.self = self;
        this.path = path;
        this.identity = identity;
        this.executor = executor;
        this.supervisionDecider = supervisionDecider;
    }

    @Override
    public ActorRef<C> self() {
        return self;
    }

    @Override
    public ActorPath path() {
        return path;
    }

    @Override
    public ActorIdentity identity() {
        return identity;
    }

    @Override
    public <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName) {
        ActorPath childPath = path.child(childName);

        InMemoryMailbox<M> mailbox = new InMemoryMailbox<>(executor);
        LocalActorRef<M> childRef = new LocalActorRef<>(childPath, null, mailbox);

        LocalActorContext<M> childContext = new LocalActorContext<>(
            childRef, childPath, null, executor, supervisionDecider
        );

        Behavior<M> behavior = factory.create(childContext);

        LocalActorCell<M> cell = new LocalActorCell<>(childRef, childContext, behavior, supervisionDecider);
        mailbox.start(cell::processMessage);

        children.put(childName, childRef);
        return childRef;
    }

    /**
     * NEW METHOD: Spawn a persistent child actor.
     *
     * This method creates a full persistent actor as a child:
     * 1. Creates mailbox and actor reference
     * 2. Derives ActorIdentity from parent's path + childName
     * 3. Creates a LocalPersistentActorCell (handles recovery + persistence)
     * 4. Wires everything together
     *
     * CRITICAL DESIGN DECISION:
     * The child's persistenceId is derived from the parent's context:
     * Format: "ParentType-ParentId-ChildName"
     * This ensures:
     * - Unique persistence streams for each child
     * - Hierarchical organization in event store
     * - Easy debugging (you can see the full path in events)
     *
     * Example: If parent is VwapStrategy|STR-ETH-2026 and child is "twap-executor",
     * the child's persistenceId becomes: "VwapStrategy-STR-ETH-2026-twap-executor"
     */
    @Override
    public <M, E, S> ActorRef<M> spawnPersistent(
            PersistentBehaviorFactory<M, E, S> persistentBehaviorFactory,
            String childName,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore) {

        // 1. Derive child identity from parent context
        String parentType = identity != null ? identity.typeName() : path.name();
        String parentId = identity != null ? identity.entityId() : path.name();
        ActorIdentity childIdentity = new ActorIdentity(
            parentType + "-Child",
            parentId + "-" + childName
        );
        ActorPath childPath = childIdentity.toActorPath();

        // 2. Create mailbox and reference
        InMemoryMailbox<M> mailbox = new InMemoryMailbox<>(executor);
        LocalActorRef<M> childRef = new LocalActorRef<>(childPath, childIdentity, mailbox);

        // 3. Create child context
        LocalActorContext<M> childContext = new LocalActorContext<>(
            childRef, childPath, childIdentity, executor, supervisionDecider
        );

        // 4. Create persistent behavior using the factory
        PersistentBehavior<M, E, S> persistentBehavior = persistentBehaviorFactory.create(childName);

        // 5. Create persistent actor cell (this handles recovery automatically)
        LocalPersistentActorCell<M, E, S> cell = new LocalPersistentActorCell<>(
            childRef, childContext, persistentBehavior, eventStore, snapshotStore, supervisionDecider
        );

        // 6. Wire mailbox to cell
        mailbox.start(cell::processMessage);

        // 7. Track child
        children.put(childName, childRef);

        return childRef;
    }

    @Override
    public void stop(ActorRef<?> child) {
        String childName = child.path().name();
        ActorRef<?> removed = children.remove(childName);
        if (removed instanceof LocalActorRef) {
            ((LocalActorRef<?>) removed).mailbox().stop();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <M> ActorRef<M> getChild(String childName) {
        return (ActorRef<M>) children.get(childName);
    }

    @Override
    public void log(String message, Object... args) {
        String formatted = args.length > 0 ? String.format(message, args) : message;
        System.out.println("[" + path.toStringPath() + "] " + formatted);
    }
}
```
```java
package ir.sohrabhs.local;


import ir.sohrabhs.actor.core.actor.ActorIdentity;
import ir.sohrabhs.actor.core.actor.ActorPath;
import ir.sohrabhs.actor.core.actor.ActorRef;
import ir.sohrabhs.actor.core.mailbox.Mailbox;

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
```
```java
package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;
import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;
import ir.sohrabhs.actor.core.system.ActorSystemConfig;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Local actor system implementation.
 * Suitable for Android, testing, and single-JVM deployments.
 */
public final class LocalActorSystem implements ActorSystem {

    private final ActorSystemConfig config;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ActorRef<?>> topLevelActors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ShardRegion<?>> shardRegions = new ConcurrentHashMap<>();

    public LocalActorSystem(ActorSystemConfig config, ExecutorService executor) {
        this.config = config;
        this.executor = executor;
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name) {
        return spawn(factory, name, config.defaultSupervision());
    }

    @Override
    public <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name, SupervisionDecider decider) {
        ActorPath path = ActorPath.root().child(name);

        InMemoryMailbox<C> mailbox = new InMemoryMailbox<>(executor);
        LocalActorRef<C> ref = new LocalActorRef<>(path, null, mailbox);

        LocalActorContext<C> context = new LocalActorContext<>(
            ref, path, null, executor, decider
        );

        Behavior<C> behavior = factory.create(context);
        LocalActorCell<C> cell = new LocalActorCell<>(ref, context, behavior, decider);

        mailbox.start(cell::processMessage);
        topLevelActors.put(name, ref);

        return ref;
    }

    @Override
    public <C, E, S> ShardRegion<C> initShardRegion(
            String typeName,
            PersistentBehaviorFactory<C, E, S> behaviorFactory,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore) {

        LocalShardRegion<C, E, S> region = new LocalShardRegion<>(
            typeName, behaviorFactory, eventStore, snapshotStore,
            executor, config.defaultSupervision()
        );

        shardRegions.put(typeName, region);
        return region;
    }

    @Override
    public String name() {
        return config.systemName();
    }

    @Override
    public void terminate() {
        topLevelActors.values().forEach(ref -> {
            if (ref instanceof LocalActorRef) {
                ((LocalActorRef<?>) ref).mailbox().stop();
            }
        });
        executor.shutdown();
    }
}
```
```java
package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.*;

import java.util.List;
import java.util.Optional;

/**
 * Runtime cell for a persistent (event-sourced) actor.
 *
 * DESIGN REASONING:
 * This is where the magic happens. This cell:
 * 1. On creation: recovers state from EventStore/SnapshotStore
 * 2. On message: calls PersistentBehavior.onCommand → gets Effect
 * 3. Interprets the Effect: persists events, updates state, runs side effects
 * 4. Handles snapshotting based on PersistentBehavior.snapshotEvery()
 *
 * KEY ARCHITECTURAL DECISION:
 * We do NOT store context in state (that breaks immutability).
 * Instead, we wrap the behavior's onCommand call with context injection.
 * This is how Akka Typed works internally.
 */
final class LocalPersistentActorCell<C, E, S> {

    private final ActorContext<C> context;
    private final PersistentBehavior<C, E, S> behavior;
    private final EventStore<E> eventStore;
    private final SnapshotStore<S> snapshotStore;
    private final String persistenceId;
    private final LocalActorRef<C> self;
    private final SupervisionDecider supervisionDecider;

    private S currentState;
    private long sequenceNumber;
    private long eventsSinceSnapshot;

    LocalPersistentActorCell(
            LocalActorRef<C> self,
            ActorContext<C> context,
            PersistentBehavior<C, E, S> behavior,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore,
            SupervisionDecider supervisionDecider) {
        this.self = self;
        this.context = context;
        this.behavior = behavior;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.persistenceId = behavior.identity().persistenceId();
        this.supervisionDecider = supervisionDecider;

        recover();
    }

    /**
     * Recovery: load snapshot + replay events.
     */
    private void recover() {
        currentState = behavior.emptyState();
        sequenceNumber = 0;
        eventsSinceSnapshot = 0;

        // Step 1: Try to load latest snapshot
        Optional<PersistedSnapshot<S>> snapshot = snapshotStore.loadLatest(persistenceId);
        if (snapshot.isPresent()) {
            PersistedSnapshot<S> snap = snapshot.get();
            currentState = snap.state();
            sequenceNumber = snap.sequenceNumber();
            context.log("Recovered snapshot at seqNr %d", sequenceNumber);
        }

        // Step 2: Replay events after snapshot
        List<PersistedEvent<E>> events = eventStore.loadEvents(persistenceId, sequenceNumber);
        for (PersistedEvent<E> persisted : events) {
            currentState = behavior.onEvent(currentState, persisted.event());
            sequenceNumber = persisted.sequenceNumber();
        }

        if (!events.isEmpty()) {
            context.log("Replayed %d events, seqNr now %d", events.size(), sequenceNumber);
        }

        // Step 3: Notify recovery complete
        behavior.onRecoveryComplete(context, currentState);
        context.log("Recovery complete. State: %s", currentState);
    }

    /**
     * Process a command message.
     *
     * CRITICAL UPDATE:
     * We now wrap the behavior's onCommand with a ContextualPersistentBehavior
     * that injects the context. This allows the behavior to access context
     * without storing it in state (which would break immutability).
     */
    void processMessage(C command) {
        try {
            // Wrap behavior to inject context
            ContextualPersistentBehavior<C, E, S> contextualBehavior =
                    new ContextualPersistentBehavior<>(behavior, context);

            // Call command handler with context-aware wrapper
            Effect<E, S> effect = contextualBehavior.onCommand(currentState, command);

            if (effect.isUnhandled()) {
                context.log("Unhandled command: %s", command);
                return;
            }

            // Persist events
            for (E event : effect.events()) {
                sequenceNumber++;
                eventStore.persist(persistenceId, sequenceNumber, event);
                currentState = behavior.onEvent(currentState, event);
                eventsSinceSnapshot++;
            }

            // Check if we should snapshot
            boolean shouldSnapshot = effect.shouldSnapshot();
            if (!shouldSnapshot && behavior.snapshotEvery() > 0
                    && eventsSinceSnapshot >= behavior.snapshotEvery()) {
                shouldSnapshot = true;
            }

            if (shouldSnapshot && sequenceNumber > 0) {
                snapshotStore.save(persistenceId, sequenceNumber, currentState);
                eventsSinceSnapshot = 0;
                context.log("Snapshot saved at seqNr %d", sequenceNumber);
            }

            // Run side effects
            if (effect.sideEffect() != null) {
                effect.sideEffect().apply(currentState);
            }

        } catch (Exception e) {
            handleFailure(e, command);
        }
    }

    private void handleFailure(Exception e, C command) {
        SupervisionStrategy strategy = supervisionDecider.decide(e);
        switch (strategy) {
            case RESTART:
                context.log("Persistent actor restarting due to: %s. Re-recovering...", e.getMessage());
                recover();
                break;
            case STOP:
                context.log("Persistent actor stopping due to: %s", e.getMessage());
                self.mailbox().stop();
                break;
            case RESUME:
                context.log("Persistent actor resuming after: %s", e.getMessage());
                break;
            case ESCALATE:
                throw new RuntimeException("Escalated from persistent actor " + self.path(), e);
        }
    }

    /**
     * Wrapper that injects ActorContext into PersistentBehavior.
     *
     * DESIGN REASONING:
     * This is the bridge between the pure PersistentBehavior contract
     * (which doesn't have context in its interface) and the runtime need
     * to provide context to behaviors.
     *
     * Instead of forcing behaviors to store context in state (impure),
     * we wrap the onCommand call and make context available through
     * a thread-local or closure. This matches how Akka Typed works.
     */
    private static final class ContextualPersistentBehavior<C, E, S>
            implements PersistentBehavior<C, E, S> {

        private final PersistentBehavior<C, E, S> delegate;
        private final ActorContext<C> context;

        ContextualPersistentBehavior(PersistentBehavior<C, E, S> delegate, ActorContext<C> context) {
            this.delegate = delegate;
            this.context = context;
        }

        @Override
        public ActorIdentity identity() {
            return delegate.identity();
        }

        @Override
        public S emptyState() {
            return delegate.emptyState();
        }

        /**
         * This is where the magic happens:
         * We provide context access through a pattern similar to
         * how the state is made available in the behavior.
         *
         * For behaviors that need context (like TwapActor, VwapActor),
         * they can access it through the PersistentBehaviorWithContext interface.
         */
        @Override
        public Effect<E, S> onCommand(S state, C command) {
            // If behavior implements the extended interface, provide context
            if (delegate instanceof PersistentBehaviorWithContext) {
                @SuppressWarnings("unchecked")
                PersistentBehaviorWithContext<C, E, S> contextAware =
                        (PersistentBehaviorWithContext<C, E, S>) delegate;
                return contextAware.onCommandWithContext(context, state, command);
            }
            // Otherwise, use standard interface
            return delegate.onCommand(state, command);
        }

        @Override
        public S onEvent(S state, E event) {
            return delegate.onEvent(state, event);
        }

        @Override
        public int snapshotEvery() {
            return delegate.snapshotEvery();
        }

        @Override
        public void onRecoveryComplete(ActorContext<?> context, S state) {
            delegate.onRecoveryComplete(context, state);
        }
    }
}
```
```java
package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.*;
import ir.sohrabhs.actor.core.persistence.*;
import ir.sohrabhs.actor.core.shard.ShardRegion;
import ir.sohrabhs.actor.core.system.ActorSystem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Local shard region: routes messages to entity actors on this JVM.
 *
 * DESIGN REASONING:
 * This is a "logical" shard region. It provides the same API as
 * Akka Cluster Sharding but everything runs locally.
 *
 * Key behaviors that match Akka:
 * - Lazy creation: actors are created on first message
 * - Deterministic routing: same entityId → same actor
 * - Each entity has its own mailbox (single-threaded processing)
 * - Entity actors are persistent (event-sourced)
 *
 * Migration to Akka Cluster Sharding:
 * Replace LocalShardRegion with AkkaShardAdapter.
 * The PersistentBehavior stays the same. The routing becomes distributed.
 */
public final class LocalShardRegion<C, E, S> implements ShardRegion<C> {

    private final String typeName;
    private final ActorSystem.PersistentBehaviorFactory<C, E, S> behaviorFactory;
    private final EventStore<E> eventStore;
    private final SnapshotStore<S> snapshotStore;
    private final ExecutorService executor;
    private final SupervisionDecider supervisionDecider;
    private final ConcurrentHashMap<String, LocalActorRef<C>> entities = new ConcurrentHashMap<>();

    public LocalShardRegion(
            String typeName,
            ActorSystem.PersistentBehaviorFactory<C, E, S> behaviorFactory,
            EventStore<E> eventStore,
            SnapshotStore<S> snapshotStore,
            ExecutorService executor,
            SupervisionDecider supervisionDecider) {
        this.typeName = typeName;
        this.behaviorFactory = behaviorFactory;
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.executor = executor;
        this.supervisionDecider = supervisionDecider;
    }

    @Override
    public void tell(String entityId, C message) {
        ActorRef<C> ref = entityRefFor(entityId);
        ref.tell(message);
    }

    @Override
    public ActorRef<C> entityRefFor(String entityId) {
        return entities.computeIfAbsent(entityId, this::createEntityActor);
    }

    @Override
    public String typeName() {
        return typeName;
    }

    private LocalActorRef<C> createEntityActor(String entityId) {
        ActorIdentity identity = new ActorIdentity(typeName, entityId);
        ActorPath actorPath = identity.toActorPath();

        // Create mailbox
        InMemoryMailbox<C> mailbox = new InMemoryMailbox<>(executor);
        LocalActorRef<C> ref = new LocalActorRef<>(actorPath, identity, mailbox);

        // Create context
        LocalActorContext<C> context = new LocalActorContext<>(
            ref, actorPath, identity, executor, supervisionDecider
        );

        // Create persistent behavior
        PersistentBehavior<C, E, S> persistentBehavior = behaviorFactory.create(entityId);

        // Create persistent actor cell (handles recovery + command processing)
        LocalPersistentActorCell<C, E, S> cell = new LocalPersistentActorCell<>(
            ref, context, persistentBehavior, eventStore, snapshotStore, supervisionDecider
        );

        // Wire mailbox to cell
        mailbox.start(cell::processMessage);

        return ref;
    }
}
```
```java
package ir.sohrabhs.local;

import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.persistence.Effect;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;

/**
 * OPTIONAL EXTENDED INTERFACE:
 * Behaviors that need access to ActorContext can implement this interface.
 *
 * This is a clean architectural pattern that:
 * 1. Keeps the core PersistentBehavior interface pure
 * 2. Allows opt-in context access for behaviors that need it
 * 3. Doesn't require storing context in state
 */
public interface PersistentBehaviorWithContext<C, E, S> extends PersistentBehavior<C, E, S> {
    /**
     * Command handler with explicit context access.
     * Override this instead of onCommand() when you need context capabilities.
     */
    Effect<E, S> onCommandWithContext(ActorContext<C> context, S state, C command);

    /**
     * Default implementation delegates to context-aware version.
     */
    @Override
    default Effect<E, S> onCommand(S state, C command) {
        throw new UnsupportedOperationException(
                "This behavior uses onCommandWithContext - context must be provided by runtime"
        );
    }
}
```
```java
// actor-core/src/main/java/com/actor/core/actor/ActorContext.java
package ir.sohrabhs.actor.core.actor;

import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;

/**
 * Provides capabilities to an actor during message processing.
 *
 * DESIGN REASONING:
 * Mirrors Akka Typed's ActorContext<T>. Provides:
 * - Self reference (for replying, scheduling, etc.)
 * - Child spawning (parent-child hierarchy)
 * - Path and identity access
 * - Logging hook (adapter provides implementation)
 *
 * This is passed to Behavior.onMessage() and BehaviorFactory.create().
 *
 * Maps to: akka.actor.typed.javadsl.ActorContext<T>
 *
 * @param <C> Command type of the owning actor
 */
public interface ActorContext<C> {

    /**
     * Reference to self. Used for:
     * - Telling others how to reply to this actor
     * - Self-messaging (timers, delayed processing)
     */
    ActorRef<C> self();

    /**
     * The path of this actor.
     */
    ActorPath path();

    /**
     * The identity of this actor (if entity-based).
     */
    ActorIdentity identity();

    /**
     * Spawn a child actor with the given name and behavior factory.
     *
     * The child's path will be: this.path()/childName
     *
     * Maps to: context.spawn(behavior, name) in Akka Typed
     *
     * @param <M> The child's command type
     * @param factory The behavior factory for the child
     * @param childName Unique name within this actor's children
     * @return Reference to the newly spawned child
     */
    <M> ActorRef<M> spawn(BehaviorFactory<M> factory, String childName);

    /**
     * Spawn a persistent (event-sourced) child actor.
     *
     * DESIGN REASONING:
     * This method allows parent actors to spawn children that are themselves
     * persistent. This is essential for hierarchical event-sourced systems
     * where a coordinator actor (e.g., VWAP) spawns sub-strategy actors (e.g., TWAP).
     *
     * The method requires explicit EventStore and SnapshotStore because:
     * 1. Child actors may need isolated persistence (separate streams)
     * 2. In production, different actors might use different databases
     * 3. The parent might be non-persistent while children are persistent
     *
     * Maps to: Akka's context.spawn(EventSourcedBehavior.create(...), name)
     *
     * @param <M> The child's command type
     * @param <E> The child's event type
     * @param <S> The child's state type
     * @param persistentBehaviorFactory Factory that creates the persistent behavior
     * @param childName Unique name within this actor's children
     * @param eventStore Event store for the child's events
     * @param snapshotStore Snapshot store for the child's state
     * @return Reference to the newly spawned persistent child
     */
    <M, E, S> ActorRef<M> spawnPersistent(
        PersistentBehaviorFactory<M, E, S> persistentBehaviorFactory,
        String childName,
        EventStore<E> eventStore,
        SnapshotStore<S> snapshotStore
    );

    /**
     * Factory for creating PersistentBehavior instances.
     * Takes childName as parameter so identity can be derived.
     */
    @FunctionalInterface
    interface PersistentBehaviorFactory<C, E, S> {
        PersistentBehavior<C, E, S> create(String childName);
    }

    /**
     * Stop a child actor.
     *
     * @param child The child reference to stop
     */
    void stop(ActorRef<?> child);

    /**
     * Get a child by name (if it exists).
     */
    <M> ActorRef<M> getChild(String childName);

    /**
     * Log a message. Adapter decides how (Android Log, SLF4J, etc.)
     */
    void log(String message, Object... args);
}
```
```java
package ir.sohrabhs.actor.core.actor;

import java.util.Objects;

/**
 * Combines a logical type name with a unique entity ID.
 * This is the key concept that maps to Akka Cluster Sharding's EntityTypeKey + entityId.
 *
 * Example: typeName="Counter", entityId="42"
 * Resulting path: /user/Counter/42
 */
public final class ActorIdentity {

    private final String typeName;
    private final String entityId;

    public ActorIdentity(String typeName, String entityId) {
        this.typeName = Objects.requireNonNull(typeName, "typeName cannot be null");
        this.entityId = Objects.requireNonNull(entityId, "entityId cannot be null");
    }

    public String typeName() {
        return typeName;
    }

    public String entityId() {
        return entityId;
    }

    /**
     * Derives the deterministic actor path from identity.
     * This is how we guarantee consistent routing.
     */
    public ActorPath toActorPath() {
        return ActorPath.root().child(typeName).child(entityId);
    }

    /**
     * Persistence ID for event sourcing.
     * Format matches Akka convention: "TypeName|entityId"
     */
    public String persistenceId() {
        return typeName + "|" + entityId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActorIdentity that = (ActorIdentity) o;
        return typeName.equals(that.typeName) && entityId.equals(that.entityId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(typeName, entityId);
    }

    @Override
    public String toString() {
        return "ActorIdentity{" + typeName + "/" + entityId + "}";
    }
}
```
```java
package ir.sohrabhs.actor.core.actor;

import java.util.Objects;

/**
 * Immutable actor path, mirroring Akka's hierarchical path model.
 * Examples: /user/counter/42, /user/order/abc/payment
 *
 * Design decision: String-based segments rather than typed hierarchy
 * because this maps cleanly to both Akka ActorPath and simple local lookup.
 */
public final class ActorPath {

    private static final String SEPARATOR = "/";
    private static final String USER_ROOT = "/user";

    private final String path;

    private ActorPath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("ActorPath cannot be null or empty");
        }
        this.path = path;
    }

    public static ActorPath of(String absolutePath) {
        return new ActorPath(absolutePath);
    }

    public static ActorPath root() {
        return new ActorPath(USER_ROOT);
    }

    /**
     * Creates a child path: /user/counter → /user/counter/42
     */
    public ActorPath child(String childName) {
        Objects.requireNonNull(childName, "Child name cannot be null");
        return new ActorPath(this.path + SEPARATOR + childName);
    }

    /**
     * Returns the name of the last segment: /user/counter/42 → "42"
     */
    public String name() {
        int lastSep = path.lastIndexOf(SEPARATOR);
        return lastSep >= 0 ? path.substring(lastSep + 1) : path;
    }

    /**
     * Returns the parent path: /user/counter/42 → /user/counter
     */
    public ActorPath parent() {
        int lastSep = path.lastIndexOf(SEPARATOR);
        if (lastSep <= 0) {
            return root();
        }
        return new ActorPath(path.substring(0, lastSep));
    }

    public String toStringPath() {
        return path;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ActorPath actorPath = (ActorPath) o;
        return path.equals(actorPath.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return "ActorPath{" + path + "}";
    }
}
```
```java
package ir.sohrabhs.actor.core.actor;

/**
 * A reference to an actor that can receive messages.
 *
 * DESIGN REASONING:
 * - This is the ONLY way to communicate with an actor (no direct method calls)
 * - tell() is fire-and-forget (async, non-blocking)
 * - No ask() in core — ask pattern is built on top via CompletableFuture in adapters
 * - The ref is serializable in concept (for future cluster support)
 *
 * Maps to: akka.actor.typed.ActorRef<T>
 *
 * @param <C> Command type this actor accepts
 */
public interface ActorRef<C> {

    /**
     * Send a message asynchronously. Fire-and-forget.
     * The message is enqueued in the actor's mailbox.
     */
    void tell(C message);

    /**
     * The path of the actor this ref points to.
     */
    ActorPath path();

    /**
     * The identity of the actor (if it's an entity actor).
     * May return null for non-entity actors.
     */
    ActorIdentity identity();
}
```
```java
package ir.sohrabhs.actor.core.actor;

/**
 * The fundamental abstraction for actor behavior.
 *
 * DESIGN REASONING:
 * In Akka Typed, a Behavior<T> is a function: (ActorContext<T>, T) → Behavior<T>.
 * We mirror this exactly but without any Akka dependency.
 *
 * Why functional-style?
 * - Immutable behavior transitions (no mutable state in behavior itself)
 * - State is encoded in the *next* Behavior returned
 * - This maps 1:1 to Akka Typed's Behaviors.receive()
 *
 * The returned Behavior becomes the actor's new behavior for the next message.
 * Returning Behaviors.same() means "keep current behavior".
 * Returning Behaviors.stopped() means "stop this actor".
 *
 * @param <C> Command type (the message type this actor handles)
 */
@FunctionalInterface
public interface Behavior<C> {

    /**
     * Called for each incoming message.
     *
     * @param context provides access to actor capabilities (spawning children, self ref, etc.)
     * @param command the incoming message
     * @return the next behavior (could be same, new state, or stopped)
     */
    Behavior<C> onMessage(ActorContext<C> context, C command);
}
```
```java
package ir.sohrabhs.actor.core.actor;

/**
 * Factory that creates the initial Behavior given an ActorContext.
 *
 * WHY THIS EXISTS:
 * In Akka Typed, Behaviors.setup(ctx -> ...) provides the context at creation time.
 * We need the same: defer behavior creation until the actor is actually spawned
 * and has a real context.
 *
 * The adapter calls create(context) when spawning, just like Akka calls
 * the setup function when creating the actor.
 *
 * @param <C> Command type
 */
public interface BehaviorFactory<C> {
    Behavior<C> create(ActorContext<C> context);
}
```
```java
package ir.sohrabhs.actor.core.actor;

/**
 * Factory methods for common behavior patterns.
 * Mirrors Akka Typed's Behaviors companion object.
 */
public final class Behaviors {

    private Behaviors() {
        // utility class
    }

    /**
     * Sentinel: keep the current behavior unchanged.
     * In Akka Typed, this is Behaviors.same.
     */
    @SuppressWarnings("unchecked")
    public static <C> Behavior<C> same() {
        return (Behavior<C>) SameBehavior.INSTANCE;
    }

    /**
     * Sentinel: stop this actor.
     * In Akka Typed, this is Behaviors.stopped.
     */
    @SuppressWarnings("unchecked")
    public static <C> Behavior<C> stopped() {
        return (Behavior<C>) StoppedBehavior.INSTANCE;
    }

    /**
     * Creates a behavior from a setup function that has access to ActorContext.
     * This is the primary entry point, mirroring Behaviors.setup in Akka Typed.
     *
     * The setup function runs once when the actor starts, and returns the initial behavior.
     */
    public static <C> BehaviorFactory<C> setup(SetupFunction<C> setupFn) {
        return new BehaviorFactory<C>() {
            @Override
            public Behavior<C> create(ActorContext<C> context) {
                return setupFn.apply(context);
            }
        };
    }

    /**
     * Creates a simple receive behavior (no setup needed).
     */
    public static <C> Behavior<C> receive(Behavior<C> behavior) {
        return behavior;
    }

    // --- Sentinel implementations ---

    public static boolean isSame(Behavior<?> b) {
        return b instanceof SameBehavior;
    }

    public static boolean isStopped(Behavior<?> b) {
        return b instanceof StoppedBehavior;
    }

    private enum SameBehavior implements Behavior<Object> {
        INSTANCE;

        @Override
        public Behavior<Object> onMessage(ActorContext<Object> context, Object command) {
            throw new UnsupportedOperationException(
                "SameBehavior is a sentinel and should never receive messages directly"
            );
        }
    }

    private enum StoppedBehavior implements Behavior<Object> {
        INSTANCE;

        @Override
        public Behavior<Object> onMessage(ActorContext<Object> context, Object command) {
            throw new UnsupportedOperationException(
                "StoppedBehavior is a sentinel and should never receive messages directly"
            );
        }
    }

    @FunctionalInterface
    public interface SetupFunction<C> {
        Behavior<C> apply(ActorContext<C> context);
    }
}
```
```java
package ir.sohrabhs.actor.core.actor;

/**
 * A function that decides the supervision strategy based on the thrown exception.
 * Mirrors Akka's Decider.
 */
@FunctionalInterface
public interface SupervisionDecider {

    SupervisionStrategy decide(Throwable cause);

    /**
     * Default: restart on any exception.
     */
    static SupervisionDecider restartAlways() {
        return cause -> SupervisionStrategy.RESTART;
    }

    /**
     * Default: stop on any exception.
     */
    static SupervisionDecider stopAlways() {
        return cause -> SupervisionStrategy.STOP;
    }
}
```
```java
package ir.sohrabhs.actor.core.actor;

/**
 * Defines how failures are handled.
 *
 * DESIGN REASONING:
 * Akka has a rich supervision tree. We provide the essential strategies
 * that map cleanly to Akka's SupervisorStrategy:
 * - RESTART: restart the actor, clearing state (Akka: SupervisorStrategy.restart)
 * - STOP: stop the actor permanently (Akka: SupervisorStrategy.stop)
 * - ESCALATE: propagate to parent (Akka: SupervisorStrategy.escalate)
 * - RESUME: ignore the failure, keep state (Akka: SupervisorStrategy.resume)
 *
 * The adapter translates these to the framework's actual supervision mechanism.
 */
public enum SupervisionStrategy {
    RESTART,
    STOP,
    ESCALATE,
    RESUME
}
```
```java
package ir.sohrabhs.actor.core.mailbox;

/**
 * Abstraction for an actor's message queue.
 *
 * DESIGN REASONING:
 * In Akka, each actor has a mailbox that provides ordering guarantees.
 * We abstract this so adapters can provide:
 * - In-memory queue (local)
 * - Android Handler/Looper based (Android)
 * - Akka's built-in dispatchers and mailboxes
 *
 * Key guarantees:
 * - Messages from the same sender are delivered in order
 * - Only one message is processed at a time (single-threaded illusion)
 *
 * @param <C> Command type
 */
public interface Mailbox<C> {

    /**
     * Enqueue a message for processing.
     * This must be thread-safe (can be called from any thread).
     */
    void enqueue(C message);

    /**
     * Start processing messages.
     * The provided handler is called for each message, one at a time.
     */
    void start(MessageHandler<C> handler);

    /**
     * Stop accepting and processing messages.
     */
    void stop();

    /**
     * Check if mailbox has pending messages.
     */
    boolean hasPending();

    @FunctionalInterface
    interface MessageHandler<C> {
        void handle(C message);
    }
}
```
```java
package ir.sohrabhs.actor.core.persistence;


import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Represents a persistence effect: what should happen after processing a command.
 *
 * DESIGN REASONING:
 * In Akka Typed Persistence, commandHandler returns an Effect<Event, State>.
 * The Effect describes:
 * - What events to persist
 * - What to do after persistence succeeds (side effects, reply, etc.)
 * - Whether to snapshot
 *
 * We mirror this exactly. The adapter interprets the Effect:
 * - Akka adapter: maps to Akka's Effect API
 * - Local adapter: persists to EventStore directly, then applies callbacks
 *
 * This is NOT a behavior — it's a description of what should happen.
 * The runtime (adapter) executes it.
 *
 * @param <E> Event type
 * @param <S> State type
 */
public final class Effect<E, S> {

    private final List<E> events;
    private final Function<S, Void> sideEffect;
    private final boolean snapshot;
    private final boolean noReply;

    private Effect(List<E> events, Function<S, Void> sideEffect, boolean snapshot) {
        this.events = Collections.unmodifiableList(events);
        this.sideEffect = sideEffect;
        this.snapshot = snapshot;
        this.noReply = false;
    }

    private Effect(boolean noReply) {
        this.events = Collections.emptyList();
        this.sideEffect = null;
        this.snapshot = false;
        this.noReply = noReply;
    }

    /**
     * Persist a single event.
     */
    public static <E, S> EffectBuilder<E, S> persist(E event) {
        return new EffectBuilder<>(Collections.singletonList(Objects.requireNonNull(event)));
    }

    /**
     * Persist multiple events (atomically).
     */
    public static <E, S> EffectBuilder<E, S> persistAll(List<E> events) {
        return new EffectBuilder<>(events);
    }

    /**
     * No events to persist, just run side effects.
     * Useful for read-only commands like GetValue.
     */
    public static <E, S> EffectBuilder<E, S> none() {
        return new EffectBuilder<>(Collections.emptyList());
    }

    /**
     * Unhandled command.
     */
    @SuppressWarnings("unchecked")
    public static <E, S> Effect<E, S> unhandled() {
        return new Effect<>(true);
    }

    public List<E> events() { return events; }
    public Function<S, Void> sideEffect() { return sideEffect; }
    public boolean shouldSnapshot() { return snapshot; }
    public boolean isUnhandled() { return noReply; }

    /**
     * Builder for fluent Effect construction.
     */
    public static final class EffectBuilder<E, S> {
        private final List<E> events;
        private Function<S, Void> sideEffect;
        private boolean snapshot = false;

        EffectBuilder(List<E> events) {
            this.events = events;
        }

        /**
         * Run a side effect after events are persisted.
         * The side effect receives the updated state.
         *
         * Example: reply to a caller, update a read model, etc.
         */
        public EffectBuilder<E, S> thenRun(Function<S, Void> sideEffect) {
            this.sideEffect = sideEffect;
            return this;
        }

        /**
         * Take a snapshot after persisting these events.
         */
        public EffectBuilder<E, S> thenSnapshot() {
            this.snapshot = true;
            return this;
        }

        public Effect<E, S> build() {
            return new Effect<>(events, sideEffect, snapshot);
        }
    }
}
```
```java
package ir.sohrabhs.actor.core.persistence;

import java.util.List;

/**
 * Port for event persistence.
 *
 * DESIGN REASONING:
 * This is a pure port (Hexagonal Architecture). The core defines WHAT it needs,
 * not HOW it's done. Adapters provide:
 * - InMemoryEventStore (for local/Android/testing)
 * - AkkaPersistenceAdapter (delegates to Akka Persistence)
 * - SQLiteEventStore (for Android production)
 * - JdbcEventStore, CassandraEventStore, etc.
 *
 * Maps to: Akka Persistence journal
 *
 * @param <E> Event type
 */
public interface EventStore<E> {

    /**
     * Persist a single event.
     *
     * @param persistenceId The entity's persistence ID
     * @param sequenceNumber Monotonically increasing sequence number
     * @param event The domain event to persist
     */
    void persist(String persistenceId, long sequenceNumber, E event);

    /**
     * Load all events for an entity, starting from a sequence number.
     *
     * @param persistenceId The entity's persistence ID
     * @param fromSequenceNumber Load events with seqNr > this value (exclusive)
     * @return Ordered list of persisted events
     */
    List<PersistedEvent<E>> loadEvents(String persistenceId, long fromSequenceNumber);

    /**
     * Get the highest sequence number for an entity.
     * Returns 0 if no events exist.
     */
    long highestSequenceNumber(String persistenceId);
}
```
```java
package ir.sohrabhs.actor.core.persistence;

import java.util.Objects;

/**
 * Wrapper around a domain event with metadata for persistence.
 *
 * @param <E> Event type
 */
public final class PersistedEvent<E> {

    private final String persistenceId;
    private final long sequenceNumber;
    private final E event;
    private final long timestamp;

    public PersistedEvent(String persistenceId, long sequenceNumber, E event, long timestamp) {
        this.persistenceId = Objects.requireNonNull(persistenceId);
        this.sequenceNumber = sequenceNumber;
        this.event = Objects.requireNonNull(event);
        this.timestamp = timestamp;
    }

    public String persistenceId() { return persistenceId; }
    public long sequenceNumber() { return sequenceNumber; }
    public E event() { return event; }
    public long timestamp() { return timestamp; }

    @Override
    public String toString() {
        return "PersistedEvent{id=" + persistenceId +
               ", seq=" + sequenceNumber +
               ", event=" + event + "}";
    }
}
```
```java
package ir.sohrabhs.actor.core.persistence;

import java.util.Objects;

/**
 * Wrapper around a state snapshot with metadata.
 *
 * @param <S> State type
 */
public final class PersistedSnapshot<S> {

    private final String persistenceId;
    private final long sequenceNumber;
    private final S state;
    private final long timestamp;

    public PersistedSnapshot(String persistenceId, long sequenceNumber, S state, long timestamp) {
        this.persistenceId = Objects.requireNonNull(persistenceId);
        this.sequenceNumber = sequenceNumber;
        this.state = Objects.requireNonNull(state);
        this.timestamp = timestamp;
    }

    public String persistenceId() { return persistenceId; }
    public long sequenceNumber() { return sequenceNumber; }
    public S state() { return state; }
    public long timestamp() { return timestamp; }

    @Override
    public String toString() {
        return "PersistedSnapshot{id=" + persistenceId +
               ", seq=" + sequenceNumber +
               ", state=" + state + "}";
    }
}
```
```java
package ir.sohrabhs.actor.core.persistence;


import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.actor.ActorIdentity;

/**
 * Defines an event-sourced actor's behavior.
 *
 * DESIGN REASONING:
 * In Akka Typed, EventSourcedBehavior<Command, Event, State> defines:
 * - emptyState(): the initial state
 * - commandHandler(state, command): returns Effect
 * - eventHandler(state, event): returns new state
 *
 * We mirror this exactly. The adapter wraps this into the actual
 * actor implementation:
 * - Akka adapter: wraps into EventSourcedBehavior
 * - Local adapter: wraps into a stateful Behavior that calls
 *   EventStore/SnapshotStore directly
 *
 * WHY SEPARATE FROM Behavior:
 * A PersistentBehavior is NOT a Behavior. It's a higher-level contract.
 * The adapter converts it into a Behavior by adding persistence machinery.
 * This is exactly how Akka Typed works: EventSourcedBehavior produces a Behavior<Command>.
 *
 * @param <C> Command type (messages this actor receives)
 * @param <E> Event type (facts that are persisted)
 * @param <S> State type (current state built from events)
 */
public interface PersistentBehavior<C, E, S> {

    /**
     * The identity of this persistent actor.
     * Used to derive persistenceId for event storage.
     */
    ActorIdentity identity();

    /**
     * The initial state when no events have been replayed.
     */
    S emptyState();

    /**
     * Handle a command given the current state.
     * Returns an Effect describing what events to persist.
     *
     * MUST BE PURE (no side effects during command handling).
     * Side effects go into Effect.thenRun().
     */
    Effect<E, S> onCommand(S state, C command);

    /**
     * Apply an event to the state, producing a new state.
     *
     * MUST BE PURE. No side effects. No exceptions.
     * This is called during:
     * - Recovery (replaying stored events)
     * - After persisting new events
     */
    S onEvent(S state, E event);

    /**
     * How often to snapshot. Returns the number of events between snapshots.
     * Return 0 to disable automatic snapshotting.
     * Default: every 100 events.
     */
    default int snapshotEvery() {
        return 100;
    }

    /**
     * Called after recovery is complete.
     * Useful for logging or initializing timers.
     */
    default void onRecoveryComplete(ActorContext<?> context, S state) {
        // default: no-op
    }
}
```
```java
package ir.sohrabhs.actor.core.persistence;

import java.util.Optional;

/**
 * Port for snapshot persistence.
 *
 * Maps to: Akka Persistence snapshot store
 *
 * @param <S> State type
 */
public interface SnapshotStore<S> {

    /**
     * Save a snapshot of the current state.
     */
    void save(String persistenceId, long sequenceNumber, S state);

    /**
     * Load the latest snapshot for an entity.
     *
     * @return The latest snapshot, or empty if none exists
     */
    Optional<PersistedSnapshot<S>> loadLatest(String persistenceId);

    /**
     * Delete snapshots up to a sequence number (for cleanup).
     */
    void deleteUpTo(String persistenceId, long maxSequenceNumber);
}
```
```java
package ir.sohrabhs.actor.core.shard;

/**
 * Extracts entity ID from a message.
 * 
 * This is used when messages are sent directly (without explicit entityId).
 * Maps to Akka's ShardRegion.MessageExtractor.
 *
 * @param <C> Command type
 */
@FunctionalInterface
public interface EntityIdExtractor<C> {
    String extractEntityId(C message);
}
```
```java
package ir.sohrabhs.actor.core.shard;

import java.util.Objects;

/**
 * A message addressed to a specific entity within a shard region.
 *
 * DESIGN REASONING:
 * In Akka Cluster Sharding, messages are wrapped in an envelope that carries
 * the entityId so the shard region can route the message to the correct entity actor.
 *
 * We use the same pattern. ShardRegion.tell(entityId, message) internally creates
 * a ShardEnvelope, routes it, and delivers the unwrapped message to the actor.
 *
 * @param <C> Command type
 */
public final class ShardEnvelope<C> {

    private final String entityId;
    private final C message;

    public ShardEnvelope(String entityId, C message) {
        this.entityId = Objects.requireNonNull(entityId, "entityId cannot be null");
        this.message = Objects.requireNonNull(message, "message cannot be null");
    }

    public String entityId() { return entityId; }
    public C message() { return message; }

    @Override
    public String toString() {
        return "ShardEnvelope{entityId='" + entityId + "', message=" + message + "}";
    }
}
```
```java
package ir.sohrabhs.actor.core.shard;


import ir.sohrabhs.actor.core.actor.ActorRef;

/**
 * A logical shard region that routes messages to entity actors.
 *
 * DESIGN REASONING:
 * This is the core abstraction for entity routing. It mirrors Akka Cluster Sharding's
 * ClusterSharding.init(Entity.of(typeKey, ctx -> behavior)).
 *
 * Key properties:
 * - Entities are created lazily on first message
 * - Entity actors are identified by entityId
 * - Routing is transparent to the caller
 * - The same entityId always maps to the same actor (within the system's lifecycle)
 *
 * Current: all entities are local (logical sharding)
 * Future: adapter can route to remote shard coordinators
 *
 * Maps to: akka.cluster.sharding.typed.javadsl.ClusterSharding
 *
 * @param <C> Command type of the entity actors
 */
public interface ShardRegion<C> {

    /**
     * Send a message to an entity.
     * If the entity doesn't exist yet, it will be created.
     *
     * This is the primary API. Maps to:
     * - Akka: entityRef.tell(message)
     * - where entityRef = sharding.entityRefFor(typeKey, entityId)
     */
    void tell(String entityId, C message);

    /**
     * Get a reference to a specific entity.
     * The entity will be created lazily when it receives its first message.
     *
     * Maps to: ClusterSharding.entityRefFor(typeKey, entityId)
     */
    ActorRef<C> entityRefFor(String entityId);

    /**
     * The type name of entities in this shard region.
     */
    String typeName();
}
```
```java
package ir.sohrabhs.actor.core.system;


import ir.sohrabhs.actor.core.actor.ActorRef;
import ir.sohrabhs.actor.core.actor.BehaviorFactory;
import ir.sohrabhs.actor.core.actor.SupervisionDecider;
import ir.sohrabhs.actor.core.persistence.EventStore;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.actor.core.persistence.SnapshotStore;
import ir.sohrabhs.actor.core.shard.ShardRegion;

/**
 * The top-level entry point for the actor system.
 *
 * DESIGN REASONING:
 * This is the outermost port. The application creates an ActorSystem via an adapter.
 * The system provides:
 * - Actor spawning
 * - Shard region creation
 * - Persistence integration
 * - Lifecycle management
 *
 * Maps to: akka.actor.typed.ActorSystem
 */
public interface ActorSystem {

    /**
     * Spawn a top-level actor.
     *
     * Maps to: ActorSystem.systemActorOf or guardian actor spawning children
     */
    <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name);

    /**
     * Spawn a top-level actor with supervision.
     */
    <C> ActorRef<C> spawn(BehaviorFactory<C> factory, String name, SupervisionDecider decider);

    /**
     * Initialize a shard region for entity actors.
     *
     * DESIGN REASONING:
     * This mirrors Akka's ClusterSharding.init(Entity.of(typeKey, createBehavior)).
     * The PersistentBehaviorFactory creates PersistentBehavior instances per entityId.
     *
     * @param typeName Logical type name (e.g., "Counter")
     * @param behaviorFactory Creates PersistentBehavior for each entity
     * @param <C> Command type
     * @param <E> Event type
     * @param <S> State type
     * @return A ShardRegion that routes messages to entity actors
     */
    <C, E, S> ShardRegion<C> initShardRegion(
        String typeName,
        PersistentBehaviorFactory<C, E, S> behaviorFactory,
        EventStore<E> eventStore,
        SnapshotStore<S> snapshotStore
    );

    /**
     * The system name.
     */
    String name();

    /**
     * Shut down the actor system and all actors.
     */
    void terminate();

    /**
     * Factory for creating PersistentBehavior instances per entity.
     */
    @FunctionalInterface
    interface PersistentBehaviorFactory<C, E, S> {
        PersistentBehavior<C, E, S> create(String entityId);
    }
}
```
```java
package ir.sohrabhs.actor.core.system;


import ir.sohrabhs.actor.core.actor.SupervisionDecider;

/**
 * Configuration for the actor system.
 */
public final class ActorSystemConfig {

    private final String systemName;
    private final int defaultMailboxCapacity;
    private final SupervisionDecider defaultSupervision;

    private ActorSystemConfig(Builder builder) {
        this.systemName = builder.systemName;
        this.defaultMailboxCapacity = builder.defaultMailboxCapacity;
        this.defaultSupervision = builder.defaultSupervision;
    }

    public String systemName() { return systemName; }
    public int defaultMailboxCapacity() { return defaultMailboxCapacity; }
    public SupervisionDecider defaultSupervision() { return defaultSupervision; }

    public static Builder builder(String systemName) {
        return new Builder(systemName);
    }

    public static final class Builder {
        private final String systemName;
        private int defaultMailboxCapacity = 1000;
        private SupervisionDecider defaultSupervision = SupervisionDecider.restartAlways();

        private Builder(String systemName) {
            this.systemName = systemName;
        }

        public Builder mailboxCapacity(int capacity) {
            this.defaultMailboxCapacity = capacity;
            return this;
        }

        public Builder defaultSupervision(SupervisionDecider decider) {
            this.defaultSupervision = decider;
            return this;
        }

        public ActorSystemConfig build() {
            return new ActorSystemConfig(this);
        }
    }
}
```

```java
package ir.sohrabhs.example.actor;


import ir.sohrabhs.actor.core.actor.ActorContext;
import ir.sohrabhs.actor.core.actor.ActorIdentity;
import ir.sohrabhs.actor.core.persistence.Effect;
import ir.sohrabhs.actor.core.persistence.PersistentBehavior;
import ir.sohrabhs.example.domain.CounterCommand;
import ir.sohrabhs.example.domain.CounterEvent;
import ir.sohrabhs.example.domain.CounterState;

/**
 * Defines the Counter entity's persistent behavior.
 *
 * THIS IS THE KEY FILE: Notice there is ZERO dependency on any actor framework.
 * No Akka imports. No local adapter imports. Pure core abstractions.
 *
 * This same class works with:
 * - LocalActorSystem (for Android/testing)
 * - AkkaActorSystem (for production cluster)
 * - Any future adapter
 *
 * Maps to: Akka Typed EventSourcedBehavior<CounterCommand, CounterEvent, CounterState>
 */
public final class CounterBehaviorFactory {

    private final int snapshotInterval;

    public CounterBehaviorFactory() {
        this(5); // snapshot every 5 events for demo
    }

    public CounterBehaviorFactory(int snapshotInterval) {
        this.snapshotInterval = snapshotInterval;
    }

    /**
     * Creates a PersistentBehavior for a specific counter entity.
     *
     * This is the factory method called by ShardRegion for each entityId.
     * Maps to: Entity.of(typeKey, ctx -> EventSourcedBehavior.create(...))
     */
    public PersistentBehavior<CounterCommand, CounterEvent, CounterState> create(String entityId) {
        return new CounterPersistentBehavior(entityId, snapshotInterval);
    }

    /**
     * The actual PersistentBehavior implementation.
     */
    private static final class CounterPersistentBehavior
            implements PersistentBehavior<CounterCommand, CounterEvent, CounterState> {

        private final ActorIdentity identity;
        private final int snapshotInterval;

        CounterPersistentBehavior(String entityId, int snapshotInterval) {
            this.identity = new ActorIdentity("Counter", entityId);
            this.snapshotInterval = snapshotInterval;
        }

        @Override
        public ActorIdentity identity() {
            return identity;
        }

        @Override
        public CounterState emptyState() {
            return CounterState.empty();
        }

        /**
         * Command handler: pure function (state, command) → Effect
         *
         * DESIGN REASONING:
         * We use if/else instead of visitor pattern for simplicity
         * (this is Java, not Scala). In real code, you might use
         * pattern matching or a command handler map.
         */
        @Override
        public Effect<CounterEvent, CounterState> onCommand(CounterState state, CounterCommand command) {
            if (command instanceof CounterCommand.Increment) {
                CounterCommand.Increment inc = (CounterCommand.Increment) command;
                return Effect.<CounterEvent, CounterState>persist(
                    new CounterEvent.Incremented(inc.amount())
                ).build();
            }

            if (command instanceof CounterCommand.Decrement) {
                CounterCommand.Decrement dec = (CounterCommand.Decrement) command;
                return Effect.<CounterEvent, CounterState>persist(
                    new CounterEvent.Decremented(dec.amount())
                ).build();
            }

            if (command instanceof CounterCommand.GetValue) {
                CounterCommand.GetValue get = (CounterCommand.GetValue) command;
                // No events to persist — just reply with current state
                return Effect.<CounterEvent, CounterState>none()
                    .thenRun(s -> {
                        get.replyTo().accept(s.value());
                        return null;
                    })
                    .build();
            }

            return Effect.unhandled();
        }

        /**
         * Event handler: pure function (state, event) → new state
         *
         * Called during:
         * - Recovery (replaying events from store)
         * - After persisting a new event
         *
         * MUST be pure. No side effects.
         */
        @Override
        public CounterState onEvent(CounterState state, CounterEvent event) {
            if (event instanceof CounterEvent.Incremented) {
                return state.withValue(state.value() + ((CounterEvent.Incremented) event).amount());
            }
            if (event instanceof CounterEvent.Decremented) {
                return state.withValue(state.value() - ((CounterEvent.Decremented) event).amount());
            }
            return state;
        }

        @Override
        public int snapshotEvery() {
            return snapshotInterval;
        }

        @Override
        public void onRecoveryComplete(ActorContext<?> context, CounterState state) {
            context.log("Counter '%s' recovery complete. Current value: %d",
                identity.entityId(), state.value());
        }
    }
}
```
```java
package ir.sohrabhs.example.domain;

/**
 * Commands for the Counter actor.
 * These are pure domain objects. No actor framework dependency.
 */
public interface CounterCommand {

    final class Increment implements CounterCommand {
        private final int amount;

        public Increment(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Increment{" + amount + "}";
        }
    }

    final class Decrement implements CounterCommand {
        private final int amount;

        public Decrement(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Decrement{" + amount + "}";
        }
    }

    /**
     * GetValue uses a callback pattern (not ask pattern) to stay framework-agnostic.
     * The replyTo is a simple functional interface.
     */
    final class GetValue implements CounterCommand {
        private final ValueConsumer replyTo;

        public GetValue(ValueConsumer replyTo) {
            this.replyTo = replyTo;
        }

        public ValueConsumer replyTo() { return replyTo; }

        @Override
        public String toString() {
            return "GetValue{}";
        }
    }

    @FunctionalInterface
    interface ValueConsumer {
        void accept(int value);
    }
}
```
```java
package ir.sohrabhs.example.domain;

import java.io.Serializable;

/**
 * Events that the Counter actor persists.
 * Pure domain facts. Immutable. Serializable (for persistence).
 */
public interface CounterEvent extends Serializable {

    final class Incremented implements CounterEvent {
        private static final long serialVersionUID = 1L;
        private final int amount;

        public Incremented(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Incremented{" + amount + "}";
        }
    }

    final class Decremented implements CounterEvent {
        private static final long serialVersionUID = 1L;
        private final int amount;

        public Decremented(int amount) {
            this.amount = amount;
        }

        public int amount() { return amount; }

        @Override
        public String toString() {
            return "Decremented{" + amount + "}";
        }
    }
}
```
```java
package ir.sohrabhs.example.domain;

import java.io.Serializable;

/**
 * The state of a Counter actor.
 * Immutable value object.
 */
public final class CounterState implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int value;

    public CounterState(int value) {
        this.value = value;
    }

    public static CounterState empty() {
        return new CounterState(0);
    }

    public int value() {
        return value;
    }

    public CounterState withValue(int newValue) {
        return new CounterState(newValue);
    }

    @Override
    public String toString() {
        return "CounterState{value=" + value + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return value == ((CounterState) o).value;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }
}
```
```java
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

        ActorSystemConfig config = ActorSystemConfig.builder("actor-system")
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
```
```java
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
 * No Akka. No external dependencies. Android-compatible.
 */
public class MainWithLocal {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Actor Framework Demo: Local Adapter ===\n");

        // --- Infrastructure Setup (would be in DI/composition root) ---

        ActorSystemConfig config = ActorSystemConfig.builder("actor-system")
            .mailboxCapacity(1000)
            .build();

        ActorSystem system = new LocalActorSystem(config, Executors.newFixedThreadPool(
                Math.max(2, Runtime.getRuntime().availableProcessors())
        ));

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
```