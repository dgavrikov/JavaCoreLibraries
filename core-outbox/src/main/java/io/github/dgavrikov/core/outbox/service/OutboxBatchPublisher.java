package io.github.dgavrikov.core.outbox.service;

import io.github.dgavrikov.core.outbox.metric.OutboxMetricCounters;
import io.github.dgavrikov.core.outbox.metric.OutboxMetricService;
import io.github.dgavrikov.core.outbox.metric.OutboxMetricServices;
import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxPayloadPlugin;
import io.github.dgavrikov.core.outbox.model.OutboxPriority;
import io.github.dgavrikov.core.outbox.model.OutboxStatus;
import io.github.dgavrikov.core.outbox.properties.OutboxProperties;
import io.github.dgavrikov.core.outbox.repository.OutboxRepository;
import io.github.dgavrikov.core.service.VirtualThreadRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jspecify.annotations.NonNull;
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
    private final static OutboxMetricServices METRIC_SERVICE = OutboxMetricServices.PUBLISHER;

    private final OutboxPriorityQueues priorityQueues;
    private final Map<String, OutboxPayloadPlugin<?>> factoryRegistry;
    private final Map<String, VirtualThreadRateLimiter> limitersRegistry = new ConcurrentHashMap<>();
    private final TaskScheduler outboxScheduler;
    private final OutboxProperties outboxProperties;
    private final OutboxRepository outboxRepository;
    private final OutboxMetricService outboxMetricService;

    public OutboxBatchPublisher(
            TaskScheduler outboxScheduler,
            OutboxPriorityQueues priorityQueues,
            Collection<OutboxPayloadPlugin<?>> outboxPayloadPluginCollection,
            OutboxProperties outboxProperties,
            OutboxRepository outboxRepository,
            OutboxMetricService outboxMetricService) {
        this.priorityQueues = priorityQueues;
        this.outboxScheduler = outboxScheduler;
        this.outboxProperties = outboxProperties;
        this.outboxRepository = outboxRepository;
        this.outboxMetricService = outboxMetricService;

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
                        groupId -> new io.github.dgavrikov.core.service.VirtualThreadRateLimiter(plugin.getTpsLimit())
                );
            }
        });
    }

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        scheduleLane(OutboxPriority.HIGH, outboxProperties.publisherProps().highLane());
        scheduleLane(OutboxPriority.MEDIUM, outboxProperties.publisherProps().mediumLane());
        scheduleLane(OutboxPriority.LOW, outboxProperties.publisherProps().lowLane());
    }

    private void scheduleLane(OutboxPriority priority, OutboxProperties.LaneProps laneProps) {
        var fixedDelay = Duration.ofMillis(laneProps.scanIntervalDelayMs());
        var initialDelay = Duration.ofMillis(laneProps.initialDelayMs());

        var periodicTrigger = new PeriodicTrigger(fixedDelay);
        periodicTrigger.setInitialDelay(initialDelay);

        var targetQueue = priorityQueues.getQueue(priority);
        int batchSize = laneProps.batchSize();

        // Bind queue and batchSize via a one-time startup lambda (zero runtime GC allocation)
        outboxScheduler.schedule(() -> drainQueueAndPublish(targetQueue, batchSize), periodicTrigger);
    }

    private void drainQueueAndPublish(BlockingQueue<OutboxEvent> targetQueue, int batchSize) {
        List<OutboxEvent> events = new ArrayList<>(batchSize);

        targetQueue.drainTo(events, batchSize);
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
                    outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.PLUGIN_NOT_REGISTRY);
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
                outboxMetricService.increment(METRIC_SERVICE, OutboxMetricCounters.EVENT_SENDING_FAILED);
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
