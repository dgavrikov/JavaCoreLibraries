package io.github.dgavrikov.core.inbox.service;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import io.github.dgavrikov.core.inbox.model.InboxStatus;
import io.github.dgavrikov.core.inbox.properties.InboxProperties;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import io.github.dgavrikov.core.service.VirtualThreadRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
public class InboxBatchProcessor {
    private final BlockingQueue<InboxEvent> inboxMemoryQueue;
    private final Map<String, InboxPayloadPlugin<Object>> pluginRegistry;
    private final Map<String, VirtualThreadRateLimiter> limiters;
    private final InboxProperties properties;
    private final InboxRepository repository;
    private final ExecutorService virtualThreadExecutor;

    @SuppressWarnings("unchecked")
    public InboxBatchProcessor(
            TaskScheduler scheduler,
            BlockingQueue<InboxEvent> inboxMemoryQueue,
            Collection<InboxPayloadPlugin<?>> plugins,
            InboxProperties properties,
            InboxRepository repository
    ) {
        this.inboxMemoryQueue = inboxMemoryQueue;
        this.properties = properties;
        this.repository = repository;
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

        this.pluginRegistry = plugins.stream()
                .collect(Collectors.toMap(p -> p.getSupportedType().asString(), p -> (InboxPayloadPlugin<Object>) p));

        this.limiters = new ConcurrentHashMap<>();
        plugins.forEach(p -> {
            if (p.getTpsLimit() > 0) {
                limiters.computeIfAbsent(p.getRateLimitGroupId(), k -> new VirtualThreadRateLimiter(p.getTpsLimit()));
            }
        });

        // Инициализируем бесконечный low-latency цикл сканирования памяти через кастомный TaskScheduler
        scheduler.scheduleWithFixedDelay(this::processLoop,
                Duration.ofMillis(properties.workerProps().scanMemoryQueueIntervalDelayMs()));
    }

    private void processLoop() {
        int batchSize = properties.workerProps().batchSize();
        List<InboxEvent> batch = new ArrayList<>(batchSize);
        inboxMemoryQueue.drainTo(batch, batchSize);

        if (batch.isEmpty()) return;

        List<CompletableFuture<ExecutionResult>> futures = new ArrayList<>();

        for (InboxEvent event : batch) {
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

    private ExecutionResult executeSingleEvent(InboxEvent event) {
        InboxPayloadPlugin<Object> plugin = pluginRegistry.get(event.eventType().asString());
        if (plugin == null) {
            return new ExecutionResult(event.messageId(), false, "No plugin registered for type: " + event.eventType().asString());
        }

        // Троттлинг на базе нашего атомарного сдвига таймлайна (getAndAdd) без блокировки OS-threads
        VirtualThreadRateLimiter limiter = limiters.get(plugin.getRateLimitGroupId());
        if (limiter != null) {
            limiter.acquire();
        }

        try {
            Object domainContext = plugin.deserialize(event.payload());
            plugin.process(event, domainContext);
            return new ExecutionResult(event.messageId(), true, null);
        } catch (Exception e) {
            log.error("Error executing business logic for inbox event: {}", event.messageId(), e);
            return new ExecutionResult(event.messageId(), false, e.getMessage());
        }
    }

    private record ExecutionResult(String messageId, boolean isSuccess, String reason) {}
}
