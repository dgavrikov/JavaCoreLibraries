package io.github.dgavrikov.core.inbox.model;

import lombok.Builder;

import java.util.Map;

/**
 * An immutable platform record representing a captured transactional inbox event log entry.
 * This object serves as the unified data carrier across the database layer, the low-latency
 * in-memory streaming buffer, and the virtual-thread-backed execution plugins.
 *
 * @param <T> The specific domain context DTO type encapsulated after verification.
 */
@Builder
public record InboxEvent<T>(
        /**
         * The natural unique identifier extracted from the source broker (e.g., Kafka Record Key).
         * Serves directly as the Database Primary Key to guarantee exact-once ingestion via native SQL constraints.
         */
        String messageId,

        /** Metadata descriptor type determining which plugin routes and processes this event. */
        InboxEventType eventType,

        /** The raw, unfiltered string body (typically JSON) persisted to the СУБД for auditability and recovery. */
        String payload,

        /**
         * A pre-deserialized, structurally sound POJO context prepared on the transport edge
         * to eliminate redundant memory allocations during virtual thread execution loops.
         */
        T domainContext,

        /**
         * An immutable flat map of transport metadata headers used for distributed tracing
         * and technical routing (e.g., Kafka partition, offset, or W3C traceparent context).
         */
        Map<String, String> headers
) {
}
