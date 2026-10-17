package io.github.dgavrikov.core.outbox.model;

@FunctionalInterface
public interface OutboxEventType {
    String name();

    default String asString() {
        return name();
    }
}
