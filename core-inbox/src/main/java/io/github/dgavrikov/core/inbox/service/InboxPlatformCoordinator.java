package io.github.dgavrikov.core.inbox.service;

import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import io.github.dgavrikov.core.inbox.repository.InboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

@Slf4j
public class InboxPlatformCoordinator {

    private final BlockingQueue<InboxEvent<?>> inboxMemoryQueue;
    private final Map<String, InboxPayloadPlugin<?>> pluginRegistry;
    private final InboxRepository inboxRepository;

    public InboxPlatformCoordinator(
            BlockingQueue<InboxEvent<?>> inboxMemoryQueue,
            Collection<InboxPayloadPlugin<?>> inboxPayloadPlugins,
            InboxRepository inboxRepository
    ) {
        this.inboxMemoryQueue = inboxMemoryQueue;
        this.inboxRepository = inboxRepository;
        this.pluginRegistry = CollectionUtils.isEmpty(inboxPayloadPlugins)
                ? Map.of()
                : inboxPayloadPlugins.stream()
                .collect(Collectors.toMap(
                        plugin -> plugin.getSupportedType().name(),
                        plugin -> plugin,
                        (existing, replacement) -> existing
                ));
    }

    /**
     * Универсальный и единственный метод-фасад для транспортного слоя.
     * Полностью инкапсулирует валидацию, защиту от Poison Pills, нативную дедупликацию в СУБД
     * и асинхронный пуш в low-latency очередь строго после успешного коммита транзакции.
     *
     * @param messageId     Уникальный идентификатор сообщения (например, Kafka Record Key / UUID)
     * @param eventTypeName Строковый идентификатор типа события (для маппинга на плагин)
     * @param rawPayload    Сырое текстовое тело сообщения (обычно JSON)
     * @param headers       Метаданные / транспортные заголовки
     * @return true, если сообщение успешно принято и запланировано к обработке.
     * false, если это дубликат ИЛИ Poison Pill (сообщение отсекается без падения транспорта).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean coordinate(String messageId, String eventTypeName, String rawPayload, Map<String, String> headers) {
        // 1. Ищем плагин во внутреннем реестре стартера
        InboxPayloadPlugin<?> plugin = pluginRegistry.get(eventTypeName);
        if (plugin == null) {
            log.error("No registered core-inbox plugin found for event type: [{}]. Dropping message.", eventTypeName);
            return false;
        }

        // 2. Инкапсулированная валидация до похода в СУБД (защита от Poison Pills)
        Optional<?> domainContextOpt = plugin.validate(rawPayload);
        if (domainContextOpt.isEmpty()) {
            log.error("Poison pill detected for messageId: [{}], type: [{}]. Message dropped before DB persist.",
                    messageId, eventTypeName);
            return false;
        }

        // 3. Собираем идеальный, уже провалидированный инфраструктурный рекорд
        InboxEvent<?> inboxEvent = InboxEvent.builder()
                .messageId(messageId)
                .eventType(plugin.getSupportedType())
                .payload(rawPayload)
                .domainContext(domainContextOpt.get()) // POJO готов для in-memory буфера
                .headers(headers != null ? headers : Map.of())
                .build();

        // 4. Персистим в СУБД со статусом PROCESSING (нативная дедупликация на PK ограничении)
        boolean isInserted = inboxRepository.save(inboxEvent);
        if (!isInserted) {
            log.debug("Duplicate message detected and skipped at DB primary-key layer: {}", messageId);
            return false;
        }

        // 5. Обеспечиваем транзакционную синхронизацию — уходим в память строго после коммита в СУБД
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    offerToMemoryQueue(inboxEvent);
                }
            });
        } else {
            // Фолбек для автокоммит-транспорта
            offerToMemoryQueue(inboxEvent);
        }

        return true;
    }

    private void offerToMemoryQueue(InboxEvent<?> event) {
        boolean queued = inboxMemoryQueue.offer(event);
        if (!queued) {
            // Очередь переполнена. Запись остается в СУБД в 'PROCESSING'.
            // Фоновый Recovery движок плавно поднимет её позже без утери данных.
            log.warn("Inbox In-Memory buffer is FULL. Event {} left in DB for async Recovery Engine sweep.", event.messageId());
        }
    }
}
