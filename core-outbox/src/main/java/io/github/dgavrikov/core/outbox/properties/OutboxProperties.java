package io.github.dgavrikov.core.outbox.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "io.github.dgavrikov.core.outbox")
public record OutboxProperties(
        @DefaultValue InMemoryQueue inMemoryQueue,
        @DefaultValue PublisherProps publisherProps,
        @DefaultValue RecoveryProps recoveryProps,
        @DefaultValue CleanupProps cleanupProps
) {
    public record InMemoryQueue(
            @DefaultValue("10000") Integer capacity
    ) {
    }

    /**
     * Partitioned lane configurations for priority-based publishing.
     */
    public record PublisherProps(
            @DefaultValue LaneProps highLane,
            @DefaultValue LaneProps mediumLane,
            @DefaultValue LaneProps lowLane
    ){
    }

    /**
     * Background recovery worker configuration for abandoned events.
     */
    public record RecoveryProps(
            @DefaultValue("5000") long initialDelayMs,
            @DefaultValue("60000") long recoveryIntervalDelayMs,
            @DefaultValue("100") int batchSize,
            @DefaultValue("30") long timeDepthSec
    ){
    }

    /**
     * Historical data purge job configuration.
     */
    public record CleanupProps(
            @DefaultValue("0 0 0 * * *") String cronExpression,
            @DefaultValue("72") long depthInHour,
            @DefaultValue("10000") int batchSize
    ){
    }

    /**
     * Execution parameters for a single priority lane.
     */
    public record LaneProps(
            @DefaultValue("10000") int queueCapacity,
            @DefaultValue("1000") long initialDelayMs,
            @DefaultValue("25") long scanIntervalDelayMs,
            @DefaultValue("100") int batchSize
    ) {
    }
}
