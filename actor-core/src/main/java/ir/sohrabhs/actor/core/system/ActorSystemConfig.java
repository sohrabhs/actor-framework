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