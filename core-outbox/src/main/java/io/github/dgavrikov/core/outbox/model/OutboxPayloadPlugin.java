package io.github.dgavrikov.core.outbox.model;

import java.util.Map;
import java.util.Objects;

public interface OutboxPayloadPlugin<T> {
    OutboxEventType getSupportedType();
    String createPayload(T sourceData);
    String extractKeyId(T sourceData);
    void sendEvent(OutboxEvent outboxEvent);

    default Map<String, String> createHeaders(T sourceData) {
        return Map.of();
    }
    default int getTpsLimit() {
        // 0 - No rate limit
        return 0;
    }
    default String getRateLimitGroupId() {
        return getSupportedType().asString();
    }
}
