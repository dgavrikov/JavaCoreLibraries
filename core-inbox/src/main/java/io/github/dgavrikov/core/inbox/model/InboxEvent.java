package io.github.dgavrikov.core.inbox.model;

import lombok.Builder;

import java.util.Map;

@Builder
public record InboxEvent(
        String messageId,
        InboxEventType eventType,
        String payload,
        Map<String, String> headers
) {
}
