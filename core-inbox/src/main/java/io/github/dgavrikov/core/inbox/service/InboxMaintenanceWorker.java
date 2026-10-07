package io.github.dgavrikov.core.inbox.service;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import io.github.dgavrikov.core.inbox.model.InboxStatus;
import io.github.dgavrikov.core.inbox.properties.InboxProperties;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

/**
 * Distributed maintenance and system recovery supervisor for the Transactional Inbox infrastructure.
 * Manages background execution routines for two critical operational tracks:
 * 1) Non-blocking asynchronous event recovery using distributed database row-locks (FOR UPDATE SKIP LOCKED).
 * 2) Automated batched truncation and purging of historical log tables to prevent disk space degradation.
 * Designed explicitly to release database connection context frames before initiating in-memory task submissions.
 */
@Slf4j
public class InboxMaintenanceWorker implements ApplicationListener<ApplicationReadyEvent> {
    /** Heterogeneous internal low-latency memory buffer streaming target queue. */
    private final BlockingQueue<InboxEvent<?>> inboxMemoryQueue;

    /** System-wide extension plugin dictionary mapping explicit text discriminator names to functional handlers. */
    private final Map<String, InboxPayloadPlugin<?>> pluginRegistry;

    /** Localized immutable configuration property descriptor data record. */
    private final InboxProperties inboxProperties;

    /** Primary transactional data access database layer adapter hook. */
    private final InboxRepository inboxRepository;

    /** Isolated system scheduling framework task driver running background sweep heartbeats. */
    private final TaskScheduler taskScheduler;

    /** Reactive backpressure threshold metric calculated dynamically to guard against memory allocation overflow traps. */
    private final int capacityThreshold;

    /**
     * Initializes the background infrastructure maintenance supervisor worker instance and builds
     * localized metadata routing catalogs for safe emergency transaction log reconstruction.
     */
    public InboxMaintenanceWorker(
            BlockingQueue<InboxEvent<?>> inboxMemoryQueue,
            Collection<InboxPayloadPlugin<?>> plugins,
            TaskScheduler scheduler,
            InboxProperties inboxProperties,
            InboxRepository inboxRepository
    ) {
        this.inboxMemoryQueue = inboxMemoryQueue;
        this.inboxProperties = inboxProperties;
        this.inboxRepository = inboxRepository;
        this.taskScheduler = scheduler;
        this.capacityThreshold = (int) (inboxProperties.inMemoryQueue().capacity() * 0.5);

        this.pluginRegistry = CollectionUtils.isEmpty(plugins)
                ? Map.of()
                : plugins.stream()
                .collect(Collectors.toMap(
                        p -> p.getSupportedType().name(),
                        p -> p));
    }

    /**
     * Registers recurring operational heartbeats into the dedicated maintenance scheduler subsystem
     * immediately upon receiving container boot signals.
     */
    @Override
    public void onApplicationEvent(@NotNull ApplicationReadyEvent event) {
        taskScheduler.scheduleWithFixedDelay(this::runRecovery,
                Duration.ofMillis(inboxProperties.recoveryProps().recoveryIntervalDelayMs()));

        taskScheduler.schedule(this::runCleanup, new CronTrigger(inboxProperties.cleanupProps().cronExpression()));
    }

    /**
     * Executes non-blocking row-level recovery operations across a multi-pod cluster deployment.
     * Evaluates backpressure status flags before loading data out of the database layer,
     * and performs early validation checks to securely isolate poison pill corruption strings.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runRecovery() {
        if (inboxMemoryQueue.size() > capacityThreshold) {
            log.debug("Recovery engine skipped. Memory queue usage over 50%. Current size: {}", inboxMemoryQueue.size());
            return;
        }

        log.debug("Starting distributed database recovery sweep via FOR UPDATE SKIP LOCKED...");

        OffsetDateTime timeBoundary = OffsetDateTime.now()
                .minusSeconds(inboxProperties.recoveryProps().timeDepthSec());

        // High-Speed database transaction window: locks and updates rows instantly.
        // Returns the batch data frame and closes the connection context to safeguard the HikariCP pool.
        List<InboxEvent<?>> rawRecoveredEvents = inboxRepository.fetchBatchForRecovery(
                timeBoundary,
                inboxProperties.recoveryProps().batchSize());

        if (CollectionUtils.isEmpty(rawRecoveredEvents)) {
            return;
        }

        List<String> poisonPillIds = new ArrayList<>();

        // Re-validate and populate active domain models entirely decoupled from database transaction boundaries
        for (InboxEvent rawEvent : rawRecoveredEvents) {
            InboxPayloadPlugin plugin = pluginRegistry.get(rawEvent.eventType().name());
            if (plugin == null) {
                log.error("No plugin registered for type: {}. Event {} marked as failed.",
                        rawEvent.eventType().asString(), rawEvent.messageId());
                poisonPillIds.add(rawEvent.messageId());
                continue;
            }

            // Reconstruct the strongly-typed domain model POJO using the early validation interface barrier
            Optional<?> domainContextOpt = plugin.validate(rawEvent.payload());

            if (domainContextOpt.isEmpty()) {
                // Intercept payload schemas corrupted at rest (Poison Pills) and flag them for permanent isolation
                log.error("Poison pill detected during recovery for messageId: {}. Marking as FAILED.", rawEvent.messageId());
                poisonPillIds.add(rawEvent.messageId());
                continue;
            }

            // Allocate a new immutable record instance tracking the verified structural context
            InboxEvent<?> enrichedEvent = InboxEvent.builder()
                    .messageId(rawEvent.messageId())
                    .eventType(rawEvent.eventType())
                    .payload(rawEvent.payload())
                    .domainContext(domainContextOpt.get())
                    .headers(rawEvent.headers())
                    .build();

            boolean offered = inboxMemoryQueue.offer(enrichedEvent);
            if (!offered) {
                // Buffer capacity limit reached. Terminate execution loop immediately.
                // Records left behind remain safely configured in a 'PROCESSING' state inside PostgreSQL for a later cycle.
                log.warn("Queue capacity limit reached during recovery. Event {} and subsequent left in DB.", rawEvent.messageId());
                break;
            }
        }

        // Mass-update corrupted traces via a single aggregate network query sequence
        if (!poisonPillIds.isEmpty()) {
            inboxRepository.changeStatusInBatch(poisonPillIds, InboxStatus.FAILED, "Poison pill: validation failed during recovery");
        }
    }

    /**
     * Executes non-blocking, partitioned database record pruning tasks to trim historical logs.
     * Operates continuously until the underlying storage tables no longer contain matching target lines.
     */
    public void runCleanup() {
        log.info("Starting historical inbox logs cleanup...");

        OffsetDateTime retentionBoundary = OffsetDateTime.now()
                .minusHours(inboxProperties.cleanupProps().depthInHour());

        long deletedRows = 0L;
        try {
            while (true) {
                var delete = inboxRepository.purgeProcessed(retentionBoundary,
                        inboxProperties.cleanupProps().batchSize());
                deletedRows += delete;

                if (delete == 0)
                    break;
            }
        } catch (Exception e) {
            log.error("Error on cleanup historical process inbox.", e);
        }
        log.info("Inbox table purged. Deleted {} historical processed events.", deletedRows);
    }
}
