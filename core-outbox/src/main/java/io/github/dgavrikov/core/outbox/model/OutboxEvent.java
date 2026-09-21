package io.github.dgavrikov.core.outbox.model;

import lombok.Builder;

import java.util.Map;

@Builder
public record OutboxEvent(
        Long id,
        OutboxEventType eventType,
        String keyId,
        String payload,
        Map<String, String> headers

) {
}
