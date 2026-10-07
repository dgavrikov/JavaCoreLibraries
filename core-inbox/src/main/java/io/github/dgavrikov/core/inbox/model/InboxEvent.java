package io.github.dgavrikov.core.inbox.model;

import lombok.Builder;

import java.util.Map;

@Builder
public record InboxEvent<T>(
        String messageId,
        InboxEventType eventType,
        String payload,
        T domainContext,
        Map<String, String> headers
) {
}
