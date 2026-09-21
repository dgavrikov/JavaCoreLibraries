package io.github.dgavrikov.core.outbox.repository;

import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxEventType;
import io.github.dgavrikov.core.outbox.model.OutboxStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Data access contract for managing the Transactional Outbox event log persistence.
 * All implementations must guarantee compatibility with high-throughput transactional
 * systems and strict concurrency isolation profiles.
 */
public interface OutboxRepository {

    /**
     * Updates the status and rejection reason for a specific outbox event.
     * Typically invoked to mark an event as {@link OutboxStatus#ERROR} when transport validation
     * or routing plugin resolution fails.
     *
     * @param eventId      the unique identifier of the outbox event
     * @param outboxStatus the target status to transition into
     * @param reason       the description of the failure or error context; nullable
     */
    void updateEventStatus(Long eventId, OutboxStatus outboxStatus, String reason);

    /**
     * Persists a new outbox event to the database storage.
     * This operation must execute within the active business ACID transaction boundary
     * to guarantee atomic "State-Change + Event-Log" capture.
     *
     * @param eventType    the metadata type descriptor of the event
     * @param keyId        the partition routing key id (e.g., Kafka record partition key)
     * @param payload      the pre-serialized event body content represented as JSONB/JSON string
     * @param headers      the flat transport metadata key-value map; must be serialized to JSONB/JSON
     * @param outboxStatus the initial status of the event (typically {@link OutboxStatus#NEW})
     * @return the fully populated and persisted {@link OutboxEvent} instance including generated ID
     */
    OutboxEvent save(OutboxEventType eventType, String keyId, String payload, Map<String, String> headers, OutboxStatus outboxStatus);

    /**
     * Finds and atomically locks abandoned or stuck events that haven't been processed in time.
     * Implementation must use a non-blocking row-level lock strategy (e.g., {@X_SQL FOR UPDATE SKIP LOCKED})
     * to prevent inter-pod contention across parallel multi-pod cluster nodes.
     *
     * @param dateTime  the historical time boundary threshold; events updated before this time are considered stuck
     * @param batchSize the maximum number of records to retrieve and lock in a single execution pass
     * @return a strictly ordered list of abandoned events captured and locked for the current node's pipeline
     */
    List<OutboxEvent> findAbandonedEventsForUpdate(OffsetDateTime dateTime, int batchSize);

    /**
     * Purges successfully dispatched historical events from the active database storage.
     * Must be executed using isolated batch transactions (e.g., {@code REQUIRES_NEW}) and non-blocking
     * locks to minimize WAL/Transaction-Log bloating and avoid interference with mainline business traffic.
     *
     * @param retentionBoundary the point in time before which historical data is eligible for purging
     * @param batchSize         the maximum number of records to delete in a single atomic SQL statement pass
     * @return the total number of deleted rows in the current invocation pass
     */
    long deleteSentEventsOlderThan(OffsetDateTime retentionBoundary, int batchSize);

    /**
     * Performs a high-performance batch status update for a collection of successfully processed events.
     * This method leverages optimized collection unrolling (e.g., {@code ANY(CAST(:ids AS BIGINT[]))})
     * to reduce database round-trips and maximize systemic throughput.
     *
     * @param successIds the collection of outbox event IDs that were successfully dispatched to the message broker
     * @param outboxStatus the target status to transition into (typically {@link OutboxStatus#SENT})
     */
    void updateEventStatusBatch(List<Long> successIds, OutboxStatus outboxStatus);
}
