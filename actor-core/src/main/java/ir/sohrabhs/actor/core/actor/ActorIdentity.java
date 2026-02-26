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