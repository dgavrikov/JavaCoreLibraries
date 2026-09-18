package io.github.dgavrikov.core.outbox.repository;

import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxEventType;
import io.github.dgavrikov.core.outbox.model.OutboxStatus;

import java.time.OffsetDateTime;
import java.util.List;

public interface OutboxRepository {
    void updateEventStatus(Long eventId, OutboxStatus outboxStatus, String reason);

    OutboxEvent save(OutboxEventType eventType, String aggregateId, String payload, OutboxStatus outboxStatus);

    List<OutboxEvent> findAbandonedEventsForUpdate(OffsetDateTime dateTime, int batchSize);

    long deleteSentEventsOlderThan(OffsetDateTime retentionBoundary, int batchSize);

    void updateEventStatusBatch(List<Long> successIds, OutboxStatus outboxStatus);
}
