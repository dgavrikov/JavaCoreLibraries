package io.github.dgavrikov.core.outbox.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Execution priority levels for outbox event routing and database recovery ordering.
 */
@Getter
@RequiredArgsConstructor
public enum OutboxPriority {
    LOW(0),
    MEDIUM(1),
    HIGH(2);

    private final int weight;

    public static OutboxPriority fromWeight(int weight) {
        return switch (weight) {
            case 2 -> HIGH;
            case 0 -> LOW;
            default -> defaultPriority();
        };
    }
    public static OutboxPriority defaultPriority() {
        return MEDIUM;
    };
}
