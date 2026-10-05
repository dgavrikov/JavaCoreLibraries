package io.github.dgavrikov.core.inbox.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "io.github.dgavrikov.core.inbox")
public record InboxProperties(
        @DefaultValue InboxInMemoryQueue inMemoryQueue,
        @DefaultValue InboxWorkerProps workerProps,
        @DefaultValue InboxRecoveryProps recoveryProps,
        @DefaultValue InboxCleanupProps cleanupProps
) {
    public record InboxInMemoryQueue(
            @DefaultValue("10000") int capacity
    ) {}

    public record InboxWorkerProps(
            @DefaultValue("500") long initialDelayMs,
            @DefaultValue("25") long scanMemoryQueueIntervalDelayMs,
            @DefaultValue("50") int batchSize
    ) {}

    public record InboxRecoveryProps(
            @DefaultValue("5000") long initialDelayMs,
            @DefaultValue("60000") long recoveryIntervalDelayMs,
            @DefaultValue("100") int batchSize,
            @DefaultValue("30") long timeDepthSec
    ) {}

    public record InboxCleanupProps(
            @DefaultValue("0 0 0 * * *") String cronExpression,
            @DefaultValue("72") int depthInHour,
            @DefaultValue("10000") int batchSize
    ) {}
}
