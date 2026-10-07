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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

@Slf4j
public class InboxMaintenanceWorker implements ApplicationListener<ApplicationReadyEvent> {
    private final BlockingQueue<InboxEvent<?>> inboxMemoryQueue;
    private final Map<String, InboxPayloadPlugin<?>> pluginRegistry;
    private final InboxProperties inboxProperties;
    private final InboxRepository inboxRepository;
    private final TaskScheduler taskScheduler;
    private final int capacityThreshold;

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

    @Override
    public void onApplicationEvent(@NotNull ApplicationReadyEvent event) {
        taskScheduler.scheduleWithFixedDelay(this::runRecovery,
                Duration.ofMillis(inboxProperties.recoveryProps().recoveryIntervalDelayMs()));

        taskScheduler.schedule(this::runCleanup, new CronTrigger(inboxProperties.cleanupProps().cronExpression()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runRecovery() {
        if (inboxMemoryQueue.size() > capacityThreshold) {
            log.debug("Recovery engine skipped. Memory queue usage over 50%. Current size: {}", inboxMemoryQueue.size());
            return;
        }

        log.debug("Starting distributed database recovery sweep via FOR UPDATE SKIP LOCKED...");

        OffsetDateTime timeBoundary = OffsetDateTime.now()
                .minusSeconds(inboxProperties.recoveryProps().timeDepthSec());

        // Выполняем быструю транзакцию на чтение/блокировку. Коннект к БД освобождается сразу же!
        List<InboxEvent<?>> rawRecoveredEvents = inboxRepository.fetchBatchForRecovery(
                timeBoundary,
                inboxProperties.recoveryProps().batchSize());

        if (CollectionUtils.isEmpty(rawRecoveredEvents)) {
            return;
        }

        List<String> poisonPillIds = new ArrayList<>();

        // обогащаем контекст и раскладываем подымаемые события в in-memory очередь
        for (InboxEvent rawEvent : rawRecoveredEvents) {
            InboxPayloadPlugin plugin = pluginRegistry.get(rawEvent.eventType().name());
            if (plugin == null) {
                log.error("No plugin registered for type: {}. Event {} marked as failed.",
                        rawEvent.eventType().asString(), rawEvent.messageId());
                poisonPillIds.add(rawEvent.messageId());
                continue;
            }

            // Восстанавливаем типизированный POJO контекст из строки
            Optional<?> domainContextOpt = plugin.validate(rawEvent.payload());

            if (domainContextOpt.isEmpty()) {
                // Если при рекавери обнаружился яд (изменилась схема, побились данные) — убираем из цикла рекавери в FAILED
                log.error("Poison pill detected during recovery for messageId: {}. Marking as FAILED.", rawEvent.messageId());
                poisonPillIds.add(rawEvent.messageId());
                continue;
            }

            // Пересобираем ивент, внедряя восстановленный domainContext
            InboxEvent<?> enrichedEvent = InboxEvent.builder()
                    .messageId(rawEvent.messageId())
                    .eventType(rawEvent.eventType())
                    .payload(rawEvent.payload())
                    .domainContext(domainContextOpt.get())
                    .headers(rawEvent.headers())
                    .build();

            boolean offered = inboxMemoryQueue.offer(enrichedEvent);
            if (!offered) {
                // Очередь переполнена. Завершаем батч. Запись осталась в СУБД в статусе PROCESSING со свежим updated_at.
                log.warn("Queue capacity limit reached during recovery. Event {} and subsequent left in DB.", rawEvent.messageId());
                break;
            }
        }

        // Атомарно изолируем «отравленные» сообщения, которые не смогли пройти валидацию
        if (!poisonPillIds.isEmpty()) {
            inboxRepository.changeStatusInBatch(poisonPillIds, InboxStatus.FAILED, "Poison pill: validation failed during recovery");
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
