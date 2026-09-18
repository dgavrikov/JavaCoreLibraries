package io.github.dgavrikov.core.outbox.model;

public interface OutboxPayloadPlugin {
    OutboxEventType getSupportedType();
    String createPayload(Object sourceData);
    void sendEvent(OutboxEvent outboxEvent);
    default int getTpsLimit() {
        // 0 - No rate limit
        return 0;
    }
    default String getRateLimitGroupId() {
        return getSupportedType().asString();
    }
}
