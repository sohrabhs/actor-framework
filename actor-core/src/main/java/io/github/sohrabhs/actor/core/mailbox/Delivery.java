package io.github.sohrabhs.actor.core.mailbox;
/** Admission only, never a business acknowledgement or a durability guarantee. */
public enum Delivery { ACCEPTED, SENT_UNCONFIRMED, FULL, STOPPED, EXECUTOR_UNAVAILABLE }
