package io.github.dgavrikov.core.inbox.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe configuration properties for the Core Transactional Inbox infrastructure.
 * Maps the "io.github.dgavrikov.core.inbox" prefix directly from environment variables
 * or application YAML definitions following the 12-Factor App design pattern.
 */
@ConfigurationProperties(prefix = "io.github.dgavrikov.core.inbox")
public record InboxProperties(
        /** Internal low-latency streaming memory buffer properties. */
        @DefaultValue InboxInMemoryQueue inMemoryQueue,

        /** Asynchronous virtual-thread worker pipeline configurations. */
        @DefaultValue InboxWorkerProps workerProps,

        /** Distributed background recovery engine properties. */
        @DefaultValue InboxRecoveryProps recoveryProps,

        /** Historical processed logs database maintenance and retention properties. */
        @DefaultValue InboxCleanupProps cleanupProps
) {
    /**
     * Low-latency in-memory storage buffer metrics.
     */
    public record InboxInMemoryQueue(
            /** The maximum bound capacity of the ArrayBlockingQueue to prevent application OOM. */
            @DefaultValue("10000") int capacity
    ) {}

    /**
     * Core worker configuration for pulling elements from memory and dispatching to virtual threads.
     */
    public record InboxWorkerProps(
            /** The delay period in milliseconds before the asynchronous processing loop starts. */
            @DefaultValue("500") long initialDelayMs,

            /** Interval spacing in milliseconds to aggressively drain and scan the in-memory queue. */
            @DefaultValue("25") long scanMemoryQueueIntervalDelayMs,

            /** Maximum allocation window size sliced out of the memory buffer per runtime dispatch cycle. */
            @DefaultValue("50") int batchSize
    ) {}

    /**
     * Recovery scheduler constants to rebuild thread execution chains for abandoned database logs.
     */
    public record InboxRecoveryProps(
            /** Initial quiet window time in milliseconds before starting the distributed recovery sweeps. */
            @DefaultValue("5000") long initialDelayMs,

            /** The sleep cycle delay in milliseconds between concurrent FOR UPDATE SKIP LOCKED database sweeps. */
            @DefaultValue("60000") long recoveryIntervalDelayMs,

            /** Allocation limit block size extracted out of PostgreSQL per single recovery instance cycle. */
            @DefaultValue("100") int batchSize,

            /** Time depth window metric in seconds used to define stale messages stuck in a PROCESSING state. */
            @DefaultValue("30") long timeDepthSec
    ) {}

    /**
     * Retention and log storage optimization constants.
     */
    public record InboxCleanupProps(
            /** Cron execution expression governing the execution pattern of the historical tables cleanup scheduler. */
            @DefaultValue("0 0 0 * * *") String cronExpression,

            /** Time threshold depth constraint measured in hours; messages older than this are wiped from storage. */
            @DefaultValue("72") int depthInHour,

            /** Sliced partition execution limit boundary used to split mass deletes into localized sub-transactions. */
            @DefaultValue("10000") int batchSize
    ) {}
}
