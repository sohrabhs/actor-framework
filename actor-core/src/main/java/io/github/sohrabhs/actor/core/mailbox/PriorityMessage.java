package io.github.sohrabhs.actor.core.mailbox;
/** High priority business traffic. FIFO within a lane; bounded bursts prevent starvation. */
public interface PriorityMessage { default boolean highPriority() { return true; } }
