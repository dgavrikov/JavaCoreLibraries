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
    private final Map<String, OutboxPayloadPlugin<?>> factoryRegistry;
    private final OutboxRepository outboxRepository;

    public OutboxPlatformCoordinator(
            BlockingQueue<OutboxEvent> outboxMemoryQueue,
            Collection<OutboxPayloadPlugin<?>> outboxPayloadPluginCollection,
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

    /**
     * Сценарий 1: Фабричный метод для Application-слоя (Оркестрация).
     * Позволяет достать строго типизированный плагин наружу, чтобы собрать payload
     * за рамками или внутри транзакции, подтянув любые связанные сущности.
     * @param eventType Тип события.
     */
    @SuppressWarnings("unchecked")
    public <T> OutboxPayloadPlugin<T> getPlugin(OutboxEventType eventType) {
        var plugin = factoryRegistry.get(eventType.name());
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin not found for event type: " + eventType);
        }
        return (OutboxPayloadPlugin<T>) plugin;
    }

    /**
     * Сценарий 2: Прямое сквозное сохранение.
     * Принимает строго типизированный контекст T, сам извлекает keyId, payload и headers через плагин.
     * @param eventType Тип события.
     * @param <T>
     */
    public <T> void saveEvent(OutboxEventType eventType, T context) {
        OutboxPayloadPlugin<T> plugin = getPlugin(eventType);

        String keyId = plugin.extractKeyId(context);
        String payload = plugin.createPayload(context);
        Map<String, String> headers = plugin.createHeaders(context);

        saveAndEnqueue(eventType, keyId, payload, headers);
    }

    /**
     * Сценарий 3: Сохранение предсобранного ивента.
     * Используется, когда Application-слой сам вызвал плагин, сделал сложный маппинг,
     * и хочет просто зафиксировать отправку в БД без привязки к конкретной бизнес-сущности.
     * @param eventType Тип события.
     * @param keyId Ключ сообщения, например в кафку.
     * @param payload Тело сообщения.
     * @param headers Заголовки сообщения.
     */
    public void savePrebuiltEvent(OutboxEventType eventType, String keyId, String payload, Map<String, String> headers) {
        saveAndEnqueue(eventType, keyId, payload, headers);
    }

    private void saveAndEnqueue(OutboxEventType eventType, String keyId, String payload, Map<String, String> headers) {
        var event = outboxRepository.save(eventType, keyId, payload, headers, OutboxStatus.NEW);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (event != null) {
                    try {
                        boolean enqueued = outboxMemoryQueue.offer(event, 250, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (!enqueued) {
                            log.warn("Outbox memory queue is FULL! Event #{} left for recovery worker.", event.id());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Thread interrupted while attempting to insert Event #{} into the queue.", event.id(), e);
                    }
                }
            }
        });
    }
}
