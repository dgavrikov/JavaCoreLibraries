package io.github.dgavrikov.core.outbox.service;

import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxPriority;
import io.github.dgavrikov.core.outbox.properties.OutboxProperties;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Encapsulates isolated in-memory blocking queues partitioned by {@link OutboxPriority}.
 */
public class OutboxPriorityQueues {
    private final Map<OutboxPriority, BlockingQueue<OutboxEvent>> queues = new EnumMap<>(OutboxPriority.class);
    private final Map<OutboxPriority, Integer> thresholds = new EnumMap<>(OutboxPriority.class);

    public OutboxPriorityQueues(OutboxProperties.PublisherProps publisherProps) {
        initLane(OutboxPriority.HIGH, publisherProps.highLane());
        initLane(OutboxPriority.MEDIUM, publisherProps.mediumLane());
        initLane(OutboxPriority.LOW, publisherProps.lowLane());
    }

    private void initLane(OutboxPriority priority, OutboxProperties.LaneProps props) {
        // Pre-allocate fixed-size array buffer to prevent per-node heap allocations under load
        queues.put(priority, new ArrayBlockingQueue<>(props.queueCapacity()));
        // Set 50% backpressure threshold per queue
        thresholds.put(priority, (int) (props.queueCapacity() * 0.5));
    }

    /**
     * Retrieves the target queue for the given priority, defaulting to MEDIUM.
     */
    public BlockingQueue<OutboxEvent> getQueue(OutboxPriority priority) {
        return queues.get(priority != null ? priority : OutboxPriority.MEDIUM);
    }

    /**
     * Checks if all priority queues have exceeded their 50% capacity threshold.
     */
    public boolean areAllQueuesHeavilyLoaded() {
        for (var entry : queues.entrySet()) {
            if (entry.getValue().size() <= thresholds.get(entry.getKey())) {
                return false;
            }
        }
        return true;
    }
}
