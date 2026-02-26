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