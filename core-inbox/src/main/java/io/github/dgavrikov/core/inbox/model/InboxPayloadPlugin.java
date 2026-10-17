package io.github.dgavrikov.core.inbox.model;

import java.util.Optional;

/**
 * Infrastructure extension contract for inbox domain event handlers.
 * Implementations are decoupled from transport and scheduling primitives, acting as
 * standalone, dependency-injected processing targets for the virtual thread runtime.
 *
 * @param <T> The specific DTO type of the business domain event managed by this instance.
 */
public interface InboxPayloadPlugin<T> {

    /**
     * @return The specific event type token supported by this business processor plugin.
     */
    InboxEventType getSupportedType();

    /**
     * Dual-purpose parser: executes early deserialization and immediate structural/business
     * validation directly on the input boundary to shield the database from poison pills.
     *
     * @param rawPayload Raw text payload received from the transport layer (Kafka/REST)
     * @return An Optional holding the typed POJO context, or Optional.empty() if the payload is malformed or invalid
     */
    Optional<T> validate(String rawPayload);

    /**
     * Core business logic execution hook triggered inside an isolated, non-blocking Java 21 Virtual Thread.
     *
     * @param event The immutable, pre-validated inbox event carrying the unwrapped domain context.
     * @throws Exception if any transient or business failure occurs during processing.
     */
    void process(InboxEvent<T> event) throws Exception;

    /**
     * Throughput cap constraint (TPS limit) applied dynamically per event definition or processing group.
     *
     * @return The maximum permitted transactions per second, where 0 represents uncapped execution.
     */
    default int getTpsLimit() {
        return 0; // Default to uncapped execution
    }

    /**
     * Resolves the throttling group identifier to aggregate distinct plugins under a shared rate limiter.
     *
     * @return The identifier string, defaulting to the event type naming string.
     */
    default String getRateLimitGroupId() {
        return getSupportedType().asString();
    }
}
