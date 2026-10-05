package io.github.dgavrikov.core.inbox.repository;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxStatus;

import java.util.List;

public interface InboxRepository {
    boolean saveStrictly(InboxEvent event);

    List<InboxEvent> fetchBatchForRecovery(int batchSize, long timeDepthSec);

    void changeStatusInBatch(List<String> messageIds, InboxStatus status, String reason);

    void purgeProcessed(int hoursDepth, int batchSize);
}
