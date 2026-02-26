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