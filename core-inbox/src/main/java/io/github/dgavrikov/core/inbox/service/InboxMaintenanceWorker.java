package io.github.dgavrikov.core.inbox.service;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.properties.InboxProperties;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;

@Slf4j
public class InboxMaintenanceWorker {
    private final BlockingQueue<InboxEvent> inboxMemoryQueue;
    private final InboxProperties properties;
    private final InboxRepository repository;
    private final int capacityThreshold;

    public InboxMaintenanceWorker(
            BlockingQueue<InboxEvent> inboxMemoryQueue,
            TaskScheduler scheduler,
            InboxProperties properties,
            InboxRepository repository
    ) {
        this.inboxMemoryQueue = inboxMemoryQueue;
        this.properties = properties;
        this.repository = repository;
        this.capacityThreshold = (int) (properties.inMemoryQueue().capacity() * 0.5); // 50% порог Backpressure Guard

        // Планировщик восстановления хвостов (Recovery Engine)
        scheduler.scheduleWithFixedDelay(this::runRecovery,
                Duration.ofMillis(properties.recoveryProps().recoveryIntervalDelayMs()));

        // Планировщик очистки старых записей (Purge Engine)
        scheduler.schedule(this::runCleanup, new CronTrigger(properties.cleanupProps().cronExpression()));
    }

    private void runRecovery() {
        // Backpressure Guard: защищаем память и снижаем паразитную нагрузку на БД
        if (inboxMemoryQueue.size() > capacityThreshold) {
            log.debug("Recovery engine skipped. Memory queue usage over 50%. Current size: {}", inboxMemoryQueue.size());
            return;
        }

        log.debug("Starting distributed database recovery sweep via FOR UPDATE SKIP LOCKED...");

        List<InboxEvent> recoveredEvents = repository.fetchBatchForRecovery(
                properties.recoveryProps().batchSize(),
                properties.recoveryProps().timeDepthSec()
        );

        for (InboxEvent event : recoveredEvents) {
            boolean offered = inboxMemoryQueue.offer(event);
            if (!offered) {
                // Если память внезапно забилась во время вычитки, откатываем статус обратно в NEW одной операцией
                repository.changeStatusInBatch(List.of(event.messageId()), io.github.dgavrikov.core.inbox.model.InboxStatus.NEW, "Queue overflow during recovery");
            }
        }
    }

    private void runCleanup() {
        log.info("Starting historical inbox logs cleanup...");
        repository.purgeProcessed(
                properties.cleanupProps().depthInHour(),
                properties.cleanupProps().batchSize()
        );
    }
}
