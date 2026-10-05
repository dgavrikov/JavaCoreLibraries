package io.github.dgavrikov.core.inbox.model;

@FunctionalInterface
public interface InboxEventType {
    String name();

    default String asString() {
        return name();
    }

}
