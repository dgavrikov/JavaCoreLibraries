package io.github.dgavrikov.core.inbox.model;

import java.util.Optional;

/**
 * Инфраструктурный контракт для бизнес-обработчиков инбокса.
 *
 * @param <T> Специфичный тип DTO доменного события.
 */
public interface InboxPayloadPlugin<T> {
    InboxEventType getSupportedType();

    /**
     * Совмещает десериализацию и структурную/бизнес валидацию на самом входе.
     * @param rawPayload сырая строка из транспорта (Kafka/REST)
     * @return Optional с типизированным контекстом, либо Optional.empty() если это мусор/яд
     */
    Optional<T> validate(String rawPayload);

    /**
     * Точка выполнения чистой бизнес-логики в рантайме виртуального потока
     */
    void process(InboxEvent<T> event) throws Exception;

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
