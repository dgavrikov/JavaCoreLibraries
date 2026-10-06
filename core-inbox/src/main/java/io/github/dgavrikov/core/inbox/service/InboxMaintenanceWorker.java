package io.github.dgavrikov.core.inbox.service;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxStatus;
import io.github.dgavrikov.core.inbox.properties.InboxProperties;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.BlockingQueue;

@Slf4j
public class InboxMaintenanceWorker implements ApplicationListener<ApplicationReadyEvent> {
    private final BlockingQueue<InboxEvent> inboxMemoryQueue;
    private final InboxProperties inboxProperties;
    private final InboxRepository inboxRepository;
    private final TaskScheduler taskScheduler;
    private final int capacityThreshold;

    public InboxMaintenanceWorker(
            BlockingQueue<InboxEvent> inboxMemoryQueue,
            TaskScheduler scheduler,
            InboxProperties inboxProperties,
            InboxRepository inboxRepository
    ) {
        this.inboxMemoryQueue = inboxMemoryQueue;
        this.inboxProperties = inboxProperties;
        this.inboxRepository = inboxRepository;
        this.taskScheduler = scheduler;
        this.capacityThreshold = (int) (inboxProperties.inMemoryQueue().capacity() * 0.5);
    }

    @Override
    public void onApplicationEvent(@NotNull ApplicationReadyEvent event) {
        taskScheduler.scheduleWithFixedDelay(this::runRecovery,
                Duration.ofMillis(inboxProperties.recoveryProps().recoveryIntervalDelayMs()));

        taskScheduler.schedule(this::runCleanup, new CronTrigger(inboxProperties.cleanupProps().cronExpression()));
    }

    private void runRecovery() {
        if (inboxMemoryQueue.size() > capacityThreshold) {
            log.debug("Recovery engine skipped. Memory queue usage over 50%. Current size: {}", inboxMemoryQueue.size());
            return;
        }

        log.debug("Starting distributed database recovery sweep via FOR UPDATE SKIP LOCKED...");

        OffsetDateTime timeBoundary = OffsetDateTime.now()
                .minusSeconds(inboxProperties.recoveryProps().timeDepthSec());

        List<InboxEvent> recoveredEvents = inboxRepository.fetchBatchForRecovery(
                timeBoundary,
                inboxProperties.recoveryProps().batchSize()
        );

        for (InboxEvent event : recoveredEvents) {
            boolean offered = inboxMemoryQueue.offer(event);
            if (!offered) {
                log.warn("Queue capacity limit reached during recovery. Event {} left in DB for next sweep.", event.messageId());
                break;
            }
        }
    }

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
