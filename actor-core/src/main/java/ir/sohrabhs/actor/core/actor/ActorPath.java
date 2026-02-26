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