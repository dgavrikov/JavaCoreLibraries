package io.github.dgavrikov.core.outbox.service;

import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxEventType;
import io.github.dgavrikov.core.outbox.model.OutboxPayloadPlugin;
import io.github.dgavrikov.core.outbox.model.OutboxStatus;
import io.github.dgavrikov.core.outbox.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

@Slf4j
public class OutboxPlatformCoordinator {

    private final BlockingQueue<OutboxEvent> outboxMemoryQueue;
    private final Map<String, OutboxPayloadPlugin> factoryRegistry;
    private final OutboxRepository outboxRepository;

    public OutboxPlatformCoordinator(
            BlockingQueue<OutboxEvent> outboxMemoryQueue,
            Collection<OutboxPayloadPlugin> outboxPayloadPluginCollection,
            OutboxRepository outboxRepository) {
        this.outboxMemoryQueue = outboxMemoryQueue;
        this.outboxRepository = outboxRepository;
        factoryRegistry = CollectionUtils.isEmpty(outboxPayloadPluginCollection)
                ? Map.of()
                : outboxPayloadPluginCollection.stream()
                .collect(Collectors.toMap(
                        plugin -> plugin.getSupportedType().name(),
                        plugin -> plugin,
                        (existing, replacement) -> existing
                ));
    }

    public void saveEvent(String aggregateId, OutboxEventType eventType, Object obj) {
        var plugin = factoryRegistry.get(eventType.name());
        if (plugin == null)
            throw new IllegalArgumentException("Plugin not found for " + eventType);

        var payload = plugin.createPayload(obj);

        var event = outboxRepository.save(eventType, aggregateId, payload, OutboxStatus.NEW);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (event != null) {
                    var enqueued = outboxMemoryQueue.offer(event);
                    if (!enqueued) {
                        log.warn("Outbox memory queue is FULL! Event #{} left for recovery worker.", event.id());
                    }
                }
            }
        });
    }
}
