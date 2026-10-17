package io.github.dgavrikov.core.outbox.metric;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
public enum OutboxMetricCounters {

    PLUGIN_NOT_REGISTRY(
            "outbox_plugin_not_registry",
            "Total number of events dropped due to a missing or unregistered processing plugin"
    ),

    EVENT_SENDING_FAILED(
            "outbox_event_sending_failed",
            "Total number of failed attempts to publish events to the external message broker/system"
    ),

    MEMORY_QUEUE_HEAVILY_LOADED(
            "outbox_memory_queue_heavily_loaded",
            "Count of times the in-memory buffer exceeded its high-utilization threshold (backpressure warning)"
    ),

    MEMORY_QUEUE_IS_FULL(
            "outbox_memory_queue_is_full",
            "Count of times the in-memory buffer was completely full, causing tasks to be rejected or blocked"
    ),

    CLEANUP_ERROR(
            "outbox_cleanup_errors",
            "Total number of errors encountered during the background execution of the outbox table cleanup process"
    );

    private final String counterName;
    private final String description;
}

