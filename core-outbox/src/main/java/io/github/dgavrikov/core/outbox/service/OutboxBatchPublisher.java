package io.github.dgavrikov.core.outbox.service;

import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxPayloadPlugin;
import io.github.dgavrikov.core.outbox.model.OutboxStatus;
import io.github.dgavrikov.core.outbox.properties.OutboxProperties;
import io.github.dgavrikov.core.outbox.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
public class OutboxBatchPublisher implements ApplicationListener<ApplicationReadyEvent> {
    private final BlockingQueue<OutboxEvent> outboxMemoryQueue;
    private final Map<String, OutboxPayloadPlugin> factoryRegistry;
    // GroupID -> Лимитер
    private final Map<String, VirtualThreadRateLimiter> limitersRegistry = new ConcurrentHashMap<>();
    private final TaskScheduler outboxScheduler;
    private final OutboxProperties outboxProperties;
    private final OutboxRepository outboxRepository;

    public OutboxBatchPublisher(
            @Qualifier("outboxScheduler") TaskScheduler outboxScheduler,
            BlockingQueue<OutboxEvent> outboxMemoryQueue,
            Collection<OutboxPayloadPlugin> outboxPayloadPluginCollection,
            OutboxProperties outboxProperties,
            OutboxRepository outboxRepository) {
        this.outboxMemoryQueue = outboxMemoryQueue;
        this.outboxScheduler = outboxScheduler;
        this.outboxProperties = outboxProperties;
        this.outboxRepository = outboxRepository;
        factoryRegistry = CollectionUtils.isEmpty(outboxPayloadPluginCollection)
                ? Map.of()
                : outboxPayloadPluginCollection.stream()
                .collect(Collectors.toMap(
                        plugin -> plugin.getSupportedType().name(),
                        plugin -> plugin,
                        (existing, replacement) -> existing
                ));

        outboxPayloadPluginCollection.forEach(plugin -> {
            if (plugin.getTpsLimit() > 0) {
                limitersRegistry.computeIfAbsent(
                        plugin.getRateLimitGroupId(),
                        groupId -> new VirtualThreadRateLimiter(plugin.getTpsLimit())
                );
            }
        });
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        var fixedDelay = Duration.ofMillis(outboxProperties.batchPublisher().scanMemoryQueueIntervalDelayMs());
        var initialDelay = Duration.ofMillis(outboxProperties.batchPublisher().initialDelayMs());

        var periodicTrigger = new PeriodicTrigger(fixedDelay);
        periodicTrigger.setInitialDelay(initialDelay);

        outboxScheduler.schedule(this::drainQueueAndPublish, periodicTrigger);
    }

    public void drainQueueAndPublish() {
        List<OutboxEvent> events = new ArrayList<>(outboxProperties.batchPublisher().batchSize());

        outboxMemoryQueue.drainTo(events, outboxProperties.batchPublisher().batchSize());
        if (events.isEmpty()) {
            return;
        }

        log.trace("Drained batch of {} events from memory queue.", events.size());

        List<Long> successIds = new ArrayList<>(events.size());

        for (var event : events) {
            try {
                var plugin = factoryRegistry.get(event.eventType().name());
                if (plugin == null) {
                    var reason = "Outbox plugin not registered for " + event.eventType()
                            + ". Event #" + event.id() + " not send.";
                    log.warn(reason);
                    outboxRepository.updateEventStatus(event.id(), OutboxStatus.ERROR, reason);
                    continue;
                }

                var limiter = limitersRegistry.get(plugin.getRateLimitGroupId());
                if (limiter != null) {
                    limiter.acquire();
                }

                plugin.sendEvent(event);
                successIds.add(event.id());
            } catch (Exception e) {
                log.error("Fail to sent Event #{}.", event.id(), e);
            }
        }

        if (!successIds.isEmpty()) {
            try {
                outboxRepository.updateEventStatusBatch(successIds, OutboxStatus.SENT);
                log.trace("Successfully updated status to SENT for {} events.", successIds.size());
            } catch (Exception e) {
                log.error("Critical failure during batch status update for IDs: {}", successIds, e);
            }
        }
    }
}
