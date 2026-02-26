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
