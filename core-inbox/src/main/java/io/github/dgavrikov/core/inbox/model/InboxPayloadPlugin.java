package io.github.dgavrikov.core.inbox.model;

/**
 * Инфраструктурный контракт для бизнес-обработчиков инбокса.
 *
 * @param <T> Специфичный тип DTO доменного события.
 */
public interface InboxPayloadPlugin<T> {
    InboxEventType getSupportedType();

    /**
     * Десериализация payload в типизированный бизнес-контекст без магии и рефлексии верхнего уровня
     */
    T deserialize(String rawPayload);

    /**
     * Точка выполнения чистой бизнес-логики в рантайме виртуального потока
     */
    void process(InboxEvent event, T domainContext) throws Exception;

    /**
     * Лимит пропускной способности (TPS) под конкретный тип события / группу
     */
    default int getTpsLimit() {
        return 0; // Без лимита по умолчанию
    }

    default String getRateLimitGroupId() {
        return getSupportedType().asString();
    }
}
