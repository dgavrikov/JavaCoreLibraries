package io.github.dgavrikov.core.outbox.service;

import io.github.dgavrikov.core.outbox.metric.OutboxMetricCounters;
import io.github.dgavrikov.core.outbox.metric.OutboxMetricService;
import io.github.dgavrikov.core.outbox.metric.OutboxMetricServices;
import io.github.dgavrikov.core.outbox.model.*;
import io.github.dgavrikov.core.outbox.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class OutboxPlatformCoordinator {
    private final static OutboxMetricServices METRIC_SERVICE = OutboxMetricServices.COORDINATOR;

    private final OutboxPriorityQueues priorityQueues;
    private final Map<String, OutboxPayloadPlugin<?>> factoryRegistry;
    private final OutboxRepository outboxRepository;
    private final OutboxMetricService outboxMetricService;

    public OutboxPlatformCoordinator(
            OutboxPriorityQueues priorityQueues,
            Collection<OutboxPayloadPlugin<?>> outboxPayloadPluginCollection,
            OutboxRepository outboxRepository,
            OutboxMetricService outboxMetricService) {
        this.priorityQueues = priorityQueues;
        this.outboxRepository = outboxRepository;
        factoryRegistry = CollectionUtils.isEmpty(outboxPayloadPluginCollection)
                ? Map.of()
                : outboxPayloadPluginCollection.stream()
                .collect(Collectors.toMap(
                        plugin -> plugin.getSupportedType().name(),
                        plugin -> plugin,
                        (existing, replacement) -> existing
                ));
        this.outboxMetricService = outboxMetricService;
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
            outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.PLUGIN_NOT_REGISTRY);
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
    public <T> OutboxEvent saveEvent(OutboxEventType eventType, T context) {
        OutboxPayloadPlugin<T> plugin = getPlugin(eventType);

        String keyId = plugin.extractKeyId(context);
        String payload = plugin.createPayload(context);
        Map<String, String> headers = plugin.createHeaders(context);
        var priority = plugin.getPriority();

        return saveAndEnqueue(eventType, priority, keyId, payload, headers);
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
    public OutboxEvent savePrebuiltEvent(OutboxEventType eventType,
                                         String keyId,
                                         String payload,
                                         Map<String, String> headers) {
        return saveAndEnqueue(eventType, OutboxPriority.defaultPriority(), keyId, payload, headers);
    }

    /**
     * Scenario 3: Persistence of a pre-built event.
     * Used when the Application layer orchestrates the process externally, invokes
     * the plugin, performs complex mapping, and records the outbox event
     * without binding to a specific business entity lifecycle.
     *
     * @param eventType the type of the event
     * @param priority  the priority message
     * @param keyId     the partition key id used for routing (e.g., Kafka key)
     * @param payload   the pre-serialized event body (typically JSON)
     * @param headers   the metadata/transport headers map
     */
    public OutboxEvent savePrebuiltEvent(OutboxEventType eventType,
                                         OutboxPriority priority,
                                         String keyId, String payload,
                                         Map<String, String> headers) {
        return saveAndEnqueue(eventType, priority != null ? priority : OutboxPriority.defaultPriority(), keyId, payload, headers);
    }

    /**
     * Saves the event to the database within the current ACID transaction
     * and schedules its in-memory queue push strictly after successful commit.
     */
    private OutboxEvent saveAndEnqueue(OutboxEventType eventType,
                                       OutboxPriority priority,
                                       String keyId, String payload,
                                       Map<String, String>
                                               headers) {
        var event = outboxRepository.save(eventType, priority, keyId, payload, headers, OutboxStatus.NEW);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                if (event != null) {
                    var targetQueue = priorityQueues.getQueue(event.priority());
                    try {
                        boolean enqueued = targetQueue.offer(event, 250, TimeUnit.MILLISECONDS);
                        if (!enqueued) {
                            outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.MEMORY_QUEUE_IS_FULL);
                            log.warn("Outbox {} memory queue is FULL! Event #{} left for recovery worker.",
                                    event.priority(), event.id());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.error("Thread interrupted while attempting to insert Event #{} into {} queue.",
                                event.id(), event.priority(), e);
                    }
                }
            }
        });
        return event;
    }
}
