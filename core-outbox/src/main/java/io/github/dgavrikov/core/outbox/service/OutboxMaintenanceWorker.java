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
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Background maintenance worker responsible for abandoned event recovery and historical data purging.
 */
@Slf4j
public class OutboxMaintenanceWorker implements ApplicationListener<ApplicationReadyEvent> {
    private final static OutboxMetricServices METRIC_SERVICE = OutboxMetricServices.MAINTENANCE;

    private final OutboxPriorityQueues priorityQueues;
    private final TaskScheduler outboxScheduler;
    private final OutboxProperties outboxProperties;
    private final OutboxRepository outboxRepository;
    private final OutboxMetricService outboxMetricService;

    public OutboxMaintenanceWorker(OutboxPriorityQueues priorityQueues,
                                   TaskScheduler outboxScheduler,
                                   OutboxProperties outboxProperties,
                                   OutboxRepository outboxRepository,
                                   OutboxMetricService outboxMetricService) {
        this.priorityQueues = priorityQueues;
        this.outboxScheduler = outboxScheduler;
        this.outboxProperties = outboxProperties;
        this.outboxRepository = outboxRepository;
        this.outboxMetricService = outboxMetricService;
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        var fixedDelay = Duration.ofMillis(outboxProperties.recoveryProps().recoveryIntervalDelayMs());
        var initialDelay = Duration.ofMillis(outboxProperties.recoveryProps().initialDelayMs());

        var periodicTrigger = new PeriodicTrigger(fixedDelay);
        periodicTrigger.setInitialDelay(initialDelay);

        var cleanupTrigger = new CronTrigger(outboxProperties.cleanupProps().cronExpression());

        outboxScheduler.schedule(this::recoveryLostEvents, periodicTrigger);
        outboxScheduler.schedule(this::purgeSentEvents, cleanupTrigger);
    }

    private void recoveryLostEvents() {
        log.trace("Recovery outbox worker started.");

        if (priorityQueues.areAllQueuesHeavilyLoaded()) {
            log.debug("All outbox priority queues are heavily loaded. Skipping recovery.");
            outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.MEMORY_QUEUE_HEAVILY_LOADED);
            return;
        }

        var timeBoundary = OffsetDateTime.now()
                .minusSeconds(outboxProperties.recoveryProps().timeDepthSec());

        // Fetch batch ordered by priority DESC
        List<OutboxEvent> lostEvents = outboxRepository.findAbandonedEventsForUpdate(
                timeBoundary,
                outboxProperties.recoveryProps().batchSize()
        );

        if (lostEvents.isEmpty()) {
            return;
        }

        log.debug("Recovery outbox worker found {} events. Distributing to priority queues.", lostEvents.size());

        for (var event : lostEvents) {
            var targetQueue = priorityQueues.getQueue(event.priority());
            boolean added = targetQueue.offer(event);
            if (!added) {
                // Do not break the loop: lower/other priority queues might still have free capacity
                log.info("Outbox {} queue is FULL! Skipping Event #{}.", event.priority(), event.id());
                outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.MEMORY_QUEUE_IS_FULL);
            }
        }

        log.trace("Recovery outbox worker finished.");
    }

    private void purgeSentEvents() {
        log.info("Starting purge of successfully sent outbox events...");

        OffsetDateTime retentionBoundary = OffsetDateTime.now()
                .minusHours(outboxProperties.cleanupProps().depthInHour());

        long deletedRows = 0L;
        try {
            while (true) {
                var delete = outboxRepository.deleteSentEventsOlderThan(retentionBoundary,
                        outboxProperties.cleanupProps().batchSize());
                deletedRows += delete;

                if (delete == 0)
                    break;
            }
        } catch (Exception e) {
            log.error("Error on purge process outbox");
            outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.CLEANUP_ERROR);
            throw new RuntimeException(e);
        }
        log.info("Outbox table purged. Deleted {} historical events.", deletedRows);
    }

}
