package io.github.sohrabhs.actor.core.mailbox;
public final class DeliveryException extends RuntimeException {
    private final Delivery result;
    public DeliveryException(Delivery result) { super("Message not admitted: " + result); this.result = result; }
    public Delivery result() { return result; }
}
