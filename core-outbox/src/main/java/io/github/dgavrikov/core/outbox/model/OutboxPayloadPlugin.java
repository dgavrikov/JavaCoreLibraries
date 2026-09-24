package io.github.dgavrikov.core.outbox.model;

import java.util.Map;

/**
 * The core extension contract for infrastructure plugins handling specific event types.
 * Applications implement this interface to define payload serialization, routing key extraction,
 * transport metadata assembly, and physical message dispatching logic.
 *
 * @param <T> the type of the business context object used as the source of truth for the event
 */
public interface OutboxPayloadPlugin<T> {

    /**
     * Returns the unique event type descriptor supported by this plugin.
     *
     * @return the associated {@link OutboxEventType}
     */
    OutboxEventType getSupportedType();

    /**
     * Serializes the strictly typed business context into a raw string payload (typically JSON or Protobuf).
     * This method can be invoked both inside direct pipelines or via external application orchestrators.
     *
     * @param sourceData the business context instance
     * @return the serialized string representation of the event body
     */
    String createPayload(T sourceData);

    /**
     * Physical transport dispatcher. Executes the low-level delivery logic to the message broker or remote API.
     * Must be designed to handle transport-specific exceptions gracefully without dropping the batch thread execution.
     *
     * @param outboxEvent the completely populated outbox event log entry retrieved from the pipeline
     */
    void sendEvent(OutboxEvent outboxEvent);

    /**
     * Extracts the routing or partition key from the business context.
     * This key is critical for maintaining strict FIFO guarantees in partition-based brokers like Kafka.
     *
     * @param sourceData the business context instance
     * @return the non-null string routing key identifier
     */
    default String extractKeyId(T sourceData) {
        return null;
    }

    /**
     * Assembles transport-level metadata headers from the business context.
     * Default implementation returns an empty immutable map.
     *
     * @param sourceData the business context instance
     * @return a map of flat string key-value transport headers
     */
    default Map<String, String> createHeaders(T sourceData) {
        return Map.of();
    }

    /**
     * Defines the Transactions Per Second (TPS) throughput ceiling for this specific plugin or group.
     *
     * @return the maximum allowed TPS value; 0 or negative completely disables rate limiting
     */
    default int getTpsLimit() {
        // 0 - No rate limit by default
        return 0;
    }

    /**
     * Specifies the scheduling isolation group identifier for throttling.
     * Multiple event types can share the same Group ID to share a unified rate limiter instance.
     *
     * @return the rate limiting group string identifier; defaults to the event type name
     */
    default String getRateLimitGroupId() {
        return getSupportedType().asString();
    }
}
