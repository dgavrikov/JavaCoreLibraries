package io.github.dgavrikov.core.outbox.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "io.github.dgavrikov.core.outbox")
public record OutboxProperties(
        @DefaultValue InMemoryQueue inMemoryQueue,
        @DefaultValue BatchPublisher batchPublisher,
        @DefaultValue RecoveryProps recoveryProps,
        @DefaultValue CleanupProps cleanupProps
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

    public record CleanupProps(
            @DefaultValue("0 0 0 * * *") String cronExpression,
            @DefaultValue("72") long depthInHour,
            @DefaultValue("10000") int batchSize
    ){
    }
}
