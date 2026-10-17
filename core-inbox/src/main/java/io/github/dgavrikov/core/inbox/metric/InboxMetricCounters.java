package io.github.dgavrikov.core.inbox.metric;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum InboxMetricCounters {
    MEMORY_QUEUE_HEAVILY_LOADED(
            "inbox_memory_queue_heavily_loaded",
            "Count of times the inbox in-memory queue exceeded its high-utilization threshold, indicating processing lag"
    ),
    PLUGIN_NOT_REGISTRY(
            "inbox_plugin_not_registry",
            "Total number of incoming events that could not be dispatched because a matching message handler or plugin was not registered"
    ),
    PAYLOAD_VALIDATION_ERROR(
            "inbox_payload_validation_error",
            "Total number of incoming messages that failed schema or business payload validation checks"
    ),
    MEMORY_QUEUE_IS_FULL(
            "inbox_memory_queue_is_full",
            "Count of times the inbox in-memory queue reached maximum capacity, causing incoming messages to be rejected or blocked"
    ),
    CLEANUP_ERROR(
            "inbox_cleanup_error",
            "Total number of errors encountered during the background cleanup or retention process of processed inbox messages"
    ),
    DUPLICATE_MESSAGE_DB_DATABASE(
            "inbox_duplicate_message_in_database",
            "Total number of duplicate messages detected and ignored based on database idempotency checks"
    ),
    EVENT_PROCESSING_FAILED(
            "inbox_event_processing_failed",
            "Total number of times an inbox event failed to execute its business logic during processing"
    );

    private final String counterName;
    private final String description;

}
