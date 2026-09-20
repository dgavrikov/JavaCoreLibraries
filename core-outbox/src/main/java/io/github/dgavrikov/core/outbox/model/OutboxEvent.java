package io.github.dgavrikov.core.outbox.model;

import lombok.Builder;

@Builder
public record OutboxEvent(
        Long id,
        OutboxEventType eventType,
        String aggregateId,
        String payload
) {
}
