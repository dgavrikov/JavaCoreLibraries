package io.github.dgavrikov.core.inbox.model;

/**
 * A lightweight functional discriminator designed to eliminate heavy string allocations
 * and provide static type contracts for message taxonomy routing inside the coordinator registry.
 */
@FunctionalInterface
public interface InboxEventType {

    /**
     * @return The unique string representation name of the event type.
     */
    String name();

    /**
     * @return The string name value, falling back to the name() implementation.
     */
    default String asString() {
        return name();
    }
}
