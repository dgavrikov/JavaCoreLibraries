package io.github.dgavrikov.core.inbox.repository;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxStatus;

import java.time.OffsetDateTime;
import java.util.List;

public interface InboxRepository {
    boolean save(InboxEvent event);

    List<InboxEvent> fetchBatchForRecovery(OffsetDateTime timeBoundary, int batchSize);

    void changeStatusInBatch(List<String> messageIds, InboxStatus status, String reason);

    long purgeProcessed(OffsetDateTime retentionBoundary, int batchSize);
}
