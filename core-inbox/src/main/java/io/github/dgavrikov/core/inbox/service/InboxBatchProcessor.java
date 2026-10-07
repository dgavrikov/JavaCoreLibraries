package io.github.dgavrikov.core.inbox.service;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import io.github.dgavrikov.core.inbox.model.InboxStatus;
import io.github.dgavrikov.core.inbox.properties.InboxProperties;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import io.github.dgavrikov.core.service.VirtualThreadRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Asynchronous, high-throughput batch processing coordinator for incoming transaction log event streams.
 * Coordinates memory buffer draining, schedules individual non-blocking task execution chunks over
 * Java 21 Virtual Threads, applies granular lock-free rate limiting, and minimizes network overhead via split-batching DB state flushes.
 */
@Slf4j
public class InboxBatchProcessor implements ApplicationListener<ApplicationReadyEvent> {
    /**
     * Heterogeneous in-memory buffer queue utilizing wildcards to transport diverse domain context objects.
     */
    private final BlockingQueue<InboxEvent<?>> inboxMemoryQueue;

    /**
     * Localized system plugin catalog mapping explicit string event descriptors to single extension components.
     */
    private final Map<String, InboxPayloadPlugin<?>> pluginRegistry;

    /**
     * High-performance thread-safe storage pooling isolated rate-limiting instances mapped by routing group identifiers.
     */
    private final ConcurrentMap<String, VirtualThreadRateLimiter> limiters = new ConcurrentHashMap<>();

    /**
     * Localized type-safe immutable startup property settings metadata record.
     */
    private final InboxProperties properties;

    /**
     * Base transactional infrastructure data access interface handler.
     */
    private final InboxRepository repository;

    /**
     * Scalable thread-per-task non-blocking executor engine backed natively by JVM carrier streams.
     */
    private final ExecutorService virtualThreadExecutor;

    /**
     * High-speed platform scheduling driver instance handling loop cycle heartbeats.
     */
    private final TaskScheduler taskScheduler;

    /**
     * Initializes the core batch execution pipeline, builds internal plugin routing tables,
     * and seeds non-blocking throttling groups based on provided extension constraints.
     */
    public InboxBatchProcessor(
            TaskScheduler taskScheduler,
            BlockingQueue<InboxEvent<?>> inboxMemoryQueue,
            Collection<InboxPayloadPlugin<?>> plugins,
            InboxProperties properties,
            InboxRepository repository
    ) {
        this.taskScheduler = taskScheduler;
        this.inboxMemoryQueue = inboxMemoryQueue;
        this.properties = properties;
        this.repository = repository;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        this.pluginRegistry = CollectionUtils.isEmpty(plugins)
                ? Map.of()
                : plugins.stream()
                .collect(Collectors.toMap(
                        p -> p.getSupportedType().name(),
                        p -> p));

        plugins.forEach(p -> {
            if (p.getTpsLimit() > 0) {
                limiters.computeIfAbsent(
                        p.getRateLimitGroupId(),
                        groupId -> new VirtualThreadRateLimiter(p.getTpsLimit()));
            }
        });
    }

    /**
     * Integrates with the Spring lifecycle subsystem to kick off background scanning routines
     * immediately after the application container completes context preparation.
     */
    @Override
    public void onApplicationEvent(@NotNull ApplicationReadyEvent event) {
        taskScheduler.scheduleWithFixedDelay(this::processLoop,
                Duration.ofMillis(properties.workerProps().scanMemoryQueueIntervalDelayMs()));
    }

    /**
     * Principal orchestrator loop draining memory segments and coordinating async fork-join sequences.
     * Collates distributed processing feedback profiles and passes them to the persistence layer
     * in a single batch query context, slashing network round-trips.
     */
    private void processLoop() {
        int batchSize = properties.workerProps().batchSize();
        List<InboxEvent<?>> batch = new ArrayList<>(batchSize);
        inboxMemoryQueue.drainTo(batch, batchSize);

        if (batch.isEmpty()) return;

        List<CompletableFuture<ExecutionResult>> futures = new ArrayList<>();

        for (InboxEvent<?> event : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> executeSingleEvent(event), virtualThreadExecutor));
        }

        // Aggregate execution barriers across active Virtual Threads without pinning underlying carrier workers
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Separate and aggregate multi-state profiles to optimize batch storage modifications
        List<String> processedIds = new ArrayList<>();
        Map<String, List<String>> failedGroupWithReason = new HashMap<>();

        for (var future : futures) {
            ExecutionResult res = future.join();
            if (res.isSuccess()) {
                processedIds.add(res.messageId());
            } else {
                failedGroupWithReason.computeIfAbsent(res.reason(), k -> new ArrayList<>()).add(res.messageId());
            }
        }

        // Commit state changes via an optimized split-batch configuration
        if (!processedIds.isEmpty()) {
            repository.changeStatusInBatch(processedIds, InboxStatus.PROCESSED, null);
        }
        failedGroupWithReason.forEach((reason, ids) ->
                repository.changeStatusInBatch(ids, InboxStatus.FAILED, reason)
        );
    }

    /**
     * Isolated processing unit wrapping a singular execution task sequence inside a virtual worker context.
     * Evaluates throttling bounds and routes validated domain records directly into business layer code.
     *
     * @param event The multi-type wildcard data record entry popped out of the streaming block.
     * @return An internal execution diagnostic tuple structure reporting completion status or failure logs.
     */
    private ExecutionResult executeSingleEvent(InboxEvent<?> event) {
        InboxPayloadPlugin<?> plugin = pluginRegistry.get(event.eventType().name());
        if (plugin == null) {
            return new ExecutionResult(event.messageId(), false, "No plugin registered for type: " + event.eventType().asString());
        }

        VirtualThreadRateLimiter limiter = limiters.get(plugin.getRateLimitGroupId());
        if (limiter != null) {
            limiter.acquire();
        }

        try {
            // Happy path: Domain context is pre-loaded on the edge to minimize GC pressure and bypass repetitive Jackson overhead
            executePlugin(plugin, event);
            return new ExecutionResult(event.messageId(), true, null);
        } catch (Exception e) {
            log.error("Error executing business logic for inbox event: {}", event.messageId(), e);
            return new ExecutionResult(event.messageId(), false, e.getMessage());
        }
    }

    /**
     * Internal bridging hook bridging heterogeneous type erased instances safely into plugin boundaries.
     * Suppresses unchecked raw type warnings, forcing compile-time parameters into strict runtime logic channels.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executePlugin(InboxPayloadPlugin plugin, InboxEvent<?> event) throws Exception {
        // Безопасно передаем доменный контекст, распакованный на этапе валидации
        plugin.process(event);
    }

    /**
     * Immutable diagnostic log structure tracking async completion frames across virtual thread limits.
     */
    private record ExecutionResult(String messageId, boolean isSuccess, String reason) {
    }
}
