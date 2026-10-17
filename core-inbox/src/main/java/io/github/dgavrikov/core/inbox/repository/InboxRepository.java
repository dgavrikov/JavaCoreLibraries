package io.github.dgavrikov.core.inbox.repository;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * High-throughput infrastructure data-access contract for managing transactional inbox state logs.
 * Encapsulates optimized native SQL operations, batch mutations, and distributed concurrency
 * mechanics to minimize query overhead on the underlying PostgreSQL cluster.
 */
public interface InboxRepository {

    /**
     * Persists a validated inbox event directly into the database using a strict non-blocking strategy.
     * Enforces immediate deduplication at the storage layer via native database primary key constraints.
     *
     * @param event The typed or wildcard immutable data container carrying raw payload and metadata.
     * @return true if the row was successfully recorded in a 'PROCESSING' state;
     *         false if a duplicate key conflict was caught and dropped at the database boundary.
     */
    boolean save(InboxEvent<?> event);

    /**
     * Polls a isolated chunk of stagnant or failed inbox event messages utilizing a high-performance
     * non-blocking locking strategy designed for distributed multi-pod cluster scaling.
     *
     * @param timeBoundary The historical age threshold point defining whether a PROCESSING event is abandoned.
     * @param batchSize    The maximum allowed allocation limit block size returned in a single round-trip.
     * @return A list containing pre-mapped wildcard events safely locked and updated via FOR UPDATE SKIP LOCKED.
     */
    List<InboxEvent<?>> fetchBatchForRecovery(OffsetDateTime timeBoundary, int batchSize);

    /**
     * Executes localized atomic status batch updates across a list of target primary keys.
     * Minimizes round-trips via aggregate split-batching queries, adjusting backoff schedules on failures.
     *
     * @param messageIds Collection containing global transaction identifiers needing a state shift.
     * @param status     The target lifecycle machine state (PROCESSED, FAILED) applied to the batch.
     * @param reason     Optional textual diagnostic log descriptor or exception message detailing a processing fault.
     */
    void changeStatusInBatch(List<String> messageIds, InboxStatus status, String reason);

    /**
     * Executes non-blocking partition-based deletion sweeps against historical records.
     * Mitigates lock escalation traps by evaluating items using a limited CTE strategy.
     *
     * @param retentionBoundary Time horizon point defining the maximum lifespan of finished records.
     * @param batchSize         Maximum row segment boundary targeted per single transaction sequence.
     * @return Total integer metric representing historical record entries wiped during the execution cycle.
     */
    long purgeProcessed(OffsetDateTime retentionBoundary, int batchSize);
}
