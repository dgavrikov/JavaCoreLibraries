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
     * Scenario 1: Factory method for the Application layer (Orchestration).
     * Provides access to a strictly typed plugin, enabling payload assembly
     * either inside or outside a transaction by fetching any related entities.
     *
     * @param eventType the type of the event
     * @param <T>       the type of the business context object
     * @return the strictly typed payload plugin
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
     * Scenario 2: Direct transparent persistence.
     * Accepts a strictly typed business context object, automatically extracting
     * the keyId, payload, and transport headers via the corresponding plugin.
     *
     * @param eventType the type of the event
     * @param context   the business context object containing data to be published
     * @param <T>       the type of the business context object
     */
    public <T> void saveEvent(OutboxEventType eventType, T context) {
        OutboxPayloadPlugin<T> plugin = getPlugin(eventType);

        String keyId = plugin.extractKeyId(context);
        String payload = plugin.createPayload(context);
        Map<String, String> headers = plugin.createHeaders(context);

        saveAndEnqueue(eventType, keyId, payload, headers);
    }

    /**
     * Scenario 3: Persistence of a pre-built event.
     * Used when the Application layer orchestrates the process externally, invokes
     * the plugin, performs complex mapping, and records the outbox event
     * without binding to a specific business entity lifecycle.
     *
     * @param eventType the type of the event
     * @param keyId     the partition key id used for routing (e.g., Kafka key)
     * @param payload   the pre-serialized event body (typically JSON)
     * @param headers   the metadata/transport headers map
     */
    public void savePrebuiltEvent(OutboxEventType eventType, String keyId, String payload, Map<String, String> headers) {
        saveAndEnqueue(eventType, keyId, payload, headers);
    }

    /**
     * Saves the event to the database within the current ACID transaction
     * and schedules its in-memory queue push strictly after successful commit.
     */
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
