package io.github.dgavrikov.core.outbox.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "io.github.dgavrikov.core.outbox")
public record OutboxProperties(
        @DefaultValue InMemoryQueue inMemoryQueue,
        @DefaultValue BatchPublisher batchPublisher,
        @DefaultValue RecoveryProps recoveryProps,
        @DefaultValue PartitioningProps partitioning
) {
    public record InMemoryQueue(
            @DefaultValue("10000") Integer capacity
    ) {
    }

    public record BatchPublisher(
            @DefaultValue("500") long initialDelayMs,
            @DefaultValue("25") long scanMemoryQueueIntervalDelayMs,
            @DefaultValue("50") int batchSize
    ){
    }

    public record RecoveryProps(
            @DefaultValue("5000") long initialDelayMs,
            @DefaultValue("60000") long recoveryIntervalDelayMs,
            @DefaultValue("100") int batchSize,
            @DefaultValue("30") long timeDepthSec
    ){
    }

    public record PartitioningProps(
            @DefaultValue("0 15 0 * * *") String manageCronExpression,
            @DefaultValue("0 0 1 * * *") String cleanupCronExpression,
            @DefaultValue("3") int countDaysSoftDelete, // If all status=SENT -> DROP
            @DefaultValue("5") int countDaysHardDelete, // DROP
            @DefaultValue("7") int daysBehind,
            @DefaultValue("7") int daysAhead
    ){}
}
