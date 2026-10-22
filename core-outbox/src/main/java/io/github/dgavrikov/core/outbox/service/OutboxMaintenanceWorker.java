package io.github.dgavrikov.core.outbox.service;

import io.github.dgavrikov.core.outbox.metric.OutboxMetricCounters;
import io.github.dgavrikov.core.outbox.metric.OutboxMetricService;
import io.github.dgavrikov.core.outbox.metric.OutboxMetricServices;
import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.properties.OutboxProperties;
import io.github.dgavrikov.core.outbox.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.BlockingQueue;

@Slf4j
public class OutboxMaintenanceWorker implements ApplicationListener<ApplicationReadyEvent> {
    private final static OutboxMetricServices METRIC_SERVICE = OutboxMetricServices.MAINTENANCE;

    private final BlockingQueue<OutboxEvent> outboxMemoryQueue;
    private final TaskScheduler outboxScheduler;
    private final OutboxProperties outboxProperties;
    private final OutboxRepository outboxRepository;
    private final OutboxMetricService outboxMetricService;
    private final int capacityThreshold;

    public OutboxMaintenanceWorker(BlockingQueue<OutboxEvent> outboxMemoryQueue,
                                   TaskScheduler outboxScheduler,
                                   OutboxProperties outboxProperties,
                                   OutboxRepository outboxRepository,
                                   OutboxMetricService outboxMetricService) {
        this.outboxMemoryQueue = outboxMemoryQueue;
        this.outboxScheduler = outboxScheduler;
        this.outboxProperties = outboxProperties;
        this.outboxRepository = outboxRepository;
        this.capacityThreshold = (int) (outboxProperties.inMemoryQueue().capacity() * 0.5);
        this.outboxMetricService = outboxMetricService;
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        var fixedDelay = Duration.ofMillis(outboxProperties.recoveryProps().recoveryIntervalDelayMs());
        var initialDelay = Duration.ofMillis(outboxProperties.recoveryProps().initialDelayMs());

        var periodicTrigger = new PeriodicTrigger(fixedDelay);
        periodicTrigger.setInitialDelay(initialDelay);

        var cleanupTrigger = new CronTrigger(outboxProperties.partitioning().cleanupCronExpression());

        var partitionManagedTrigger = new CronTrigger(outboxProperties.partitioning().manageCronExpression());

        outboxScheduler.schedule(this::recoveryLostEvents, periodicTrigger);
        outboxScheduler.schedule(this::purgeSentEvents, cleanupTrigger);
        outboxScheduler.schedule(this::managePartitions, partitionManagedTrigger);
        managePartitions();
    }

    private void recoveryLostEvents() {
        log.trace("Recovery outbox worker started.");

        if (outboxMemoryQueue.size() > capacityThreshold) {
            log.debug("Outbox memory queue is heavily loaded (size: {}). Skipping recovery.",
                    outboxMemoryQueue.size());
            outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.MEMORY_QUEUE_HEAVILY_LOADED);
            return;
        }

        var timeBoundary = OffsetDateTime.now()
                .minusSeconds(outboxProperties.recoveryProps().timeDepthSec());

        List<OutboxEvent> lostEvents = outboxRepository.findAbandonedEventsForUpdate(
                timeBoundary,
                outboxProperties.recoveryProps().batchSize()
        );

        if (lostEvents.isEmpty()) return;

        log.debug("Recovery outbox worker found {} events. Pushing to memory queue.", lostEvents.size());

        for (var event : lostEvents) {
            boolean added = outboxMemoryQueue.offer(event);
            if (!added) {
                log.info("Outbox memory queue is FULL! Recovery process is stop.");
                outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.MEMORY_QUEUE_IS_FULL);
                break;
            }
        }

        log.trace("Recovery outbox worker finished.");
    }

    /**
     * Executes strategic background partition lifecycle maintenance via Soft and Hard drop routines.
     * Completely eliminates WAL bloating and long locking pauses associated with old DELETE statements.
     */
    private void purgeSentEvents() {
        log.info("Starting strategic outbox partitioning purge process (Soft/Hard delete)...");
        var partProps = outboxProperties.partitioning();
        var today = LocalDate.now();

        int hardDeletedCount = 0;
        int softDeletedCount = 0;
        int skippedCount = 0;

        try {
            var partitions = outboxRepository.getActivePartitions();
            for (var part : partitions) {

                long daysOld = ChronoUnit.DAYS.between(part.startDate(), today);

                // Scenario 1: Compulsory absolute Hard Delete threshold processing
                if (daysOld >= partProps.countDaysHardDelete()) {
                    log.info("Hard Delete policy triggered for partition: {}. Age: {} days. Dropping table.", part, daysOld);
                    outboxRepository.dropPartition(part);
                    hardDeletedCount++;
                    continue;
                }

                // Scenario 2: Conditional Soft Delete validation (Drop only if all events are confirmed processed)
                if (daysOld >= partProps.countDaysSoftDelete()) {
                    log.debug("Evaluating Soft Delete eligibility requirements for partition: {}. Age: {} days.", part, daysOld);
                    boolean hasUnsent = outboxRepository.hasUnsentEventsInPartition(part);
                    if (!hasUnsent) {
                        log.info("Soft Delete verified: partition {} holds no pending/error events. Dropping table.", part);
                        outboxRepository.dropPartition(part);
                        softDeletedCount++;
                    } else {
                        log.warn("Partition {} qualifies chronologically for Soft Delete but contains incomplete logs! Drop skipped.", part);
                        skippedCount++;
                    }
                }
            }
            log.info("Strategic outbox partitioning purge process finished. Hard Deleted: {}, Soft Deleted: {}, Skipped: {}.",
                    hardDeletedCount, softDeletedCount, skippedCount);
        } catch (Exception e) {
            log.error("Critical failure during outbox partition lifecycle evaluation cycle", e);
            outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.CLEANUP_ERROR);
        }
    }

    /**
     * Proactively builds outbox partition tables for a configured chronological range.
     * Leverages zero-allocation StringBuilder strategy and reads database target layout dynamically from configurations.
     */
    private void managePartitions() {
        log.info("Starting automated outbox partition creation management...");
        var props = outboxProperties.partitioning();
        var today = LocalDate.now();

        int createdCount = 0;
        int failedCount = 0;

        for (int i = -props.daysBehind(); i <= props.daysAhead(); i++) {
            var targetDate = today.plusDays(i);

            try {
                var partition = outboxRepository.createPartitionIfNotExists(targetDate);
                log.debug("Partition {} verified or created successfully.", partition.name());
                createdCount++;
            } catch (Exception e) {
                log.error("Failed to create partition targetDate={}.", targetDate, e);
                outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.CLEANUP_ERROR);
                failedCount++;
            }
        }

        log.info("Automated outbox partition creation management finished. Verified/Created: {}, Failed: {}.",
                createdCount, failedCount);
    }

}
