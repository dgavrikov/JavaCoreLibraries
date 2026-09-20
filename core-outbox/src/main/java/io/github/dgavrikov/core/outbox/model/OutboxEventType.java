package io.github.dgavrikov.core.outbox.model;

public interface OutboxEventType {
    String name();

    default String asString() {
        return name();
    }
}
