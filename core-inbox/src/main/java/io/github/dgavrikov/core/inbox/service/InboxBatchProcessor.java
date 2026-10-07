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

@Slf4j
public class InboxBatchProcessor implements ApplicationListener<ApplicationReadyEvent> {
    // Очередь содержит разнородные события, поэтому используем wildcard
    private final BlockingQueue<InboxEvent<?>> inboxMemoryQueue;
    private final Map<String, InboxPayloadPlugin<?>> pluginRegistry;
    private final ConcurrentMap<String, VirtualThreadRateLimiter> limiters = new ConcurrentHashMap<>();
    private final InboxProperties properties;
    private final InboxRepository repository;
    private final ExecutorService virtualThreadExecutor;
    private final TaskScheduler taskScheduler;

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

    @Override
    public void onApplicationEvent(@NotNull ApplicationReadyEvent event) {
        taskScheduler.scheduleWithFixedDelay(this::processLoop,
                Duration.ofMillis(properties.workerProps().scanMemoryQueueIntervalDelayMs()));
    }

    private void processLoop() {
        int batchSize = properties.workerProps().batchSize();
        List<InboxEvent<?>> batch = new ArrayList<>(batchSize);
        inboxMemoryQueue.drainTo(batch, batchSize);

        if (batch.isEmpty()) return;

        List<CompletableFuture<ExecutionResult>> futures = new ArrayList<>();

        for (InboxEvent<?> event : batch) {
            futures.add(CompletableFuture.supplyAsync(() -> executeSingleEvent(event), virtualThreadExecutor));
        }

        // Ждем завершения пачки виртуальных потоков
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // Агрегируем результаты для Split-Batching апдейта в СУБД
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

        // Выполняем точечные пакетные обновления — резко снижаем нагрузку на СУБД
        if (!processedIds.isEmpty()) {
            repository.changeStatusInBatch(processedIds, InboxStatus.PROCESSED, null);
        }
        failedGroupWithReason.forEach((reason, ids) ->
                repository.changeStatusInBatch(ids, InboxStatus.FAILED, reason)
        );
    }

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
            // Happy path: Объект уже находится внутри события после валидации в консьюмере.
            // Повторный вызов ObjectMapper-а и аллокации в куче полностью исключены.
            executePlugin(plugin, event);
            return new ExecutionResult(event.messageId(), true, null);
        } catch (Exception e) {
            log.error("Error executing business logic for inbox event: {}", event.messageId(), e);
            return new ExecutionResult(event.messageId(), false, e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executePlugin(InboxPayloadPlugin plugin, InboxEvent<?> event) throws Exception {
        // Безопасно передаем доменный контекст, распакованный на этапе валидации
        plugin.process(event);
    }

    private record ExecutionResult(String messageId, boolean isSuccess, String reason) {}
}
