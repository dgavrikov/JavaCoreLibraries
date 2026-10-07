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

/**
 * The primary platform facade and transaction orchestrator for the incoming message ingestion pipeline.
 * Encapsulates the entire ingestion lifecycle, including early validation bounds checking,
 * atomic deduplication via native database primary keys, and post-commit synchronization with the
 * internal streaming memory queue. Shields the transport layer entirely from processing and registry management.
 */
@Slf4j
public class InboxPlatformCoordinator {

    /**
     * Internal streaming buffer utilizing wildcards to handle heterogeneous domain payloads.
     */
    private final BlockingQueue<InboxEvent<?>> inboxMemoryQueue;

    /**
     * Localized system component map binding string event tags to individual infrastructure processing plugins.
     */
    private final Map<String, InboxPayloadPlugin<?>> pluginRegistry;

    /**
     * Primary database data access engine wrapper hook.
     */
    private final InboxRepository inboxRepository;

    /**
     * Initializes the entry point coordinator facade instance and automatically constructs the
     * isolated internal extension plugin taxonomy dictionary map.
     */
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
     * Universal standalone facade method tailored specifically for transport-level consumption layers (e.g., Kafka Consumers, REST Callbacks).
     * Fully orchestrates poison pill validation isolation, native primary key table-level deduplication, and safe
     * post-commit asynchronous queuing to guarantee stable data streaming metrics under high concurrent stress.
     *
     * @param messageId     The natural unique identifier string extracted from the upstream transport layer (e.g., Kafka Record Key / UUID).
     * @param eventTypeName The exact string discriminator name matching the targeted plugin interface type entry.
     * @param rawPayload    The unparsed, incoming message string representation block (typically raw JSON wire data).
     * @param headers       Extensible flat map containing metadata headers for tracing and context extraction.
     * @return true if the event successfully satisfies all validation rules, records cleanly in the database, and queues for processing;
     * false if the message is caught as a duplicate key pattern or discarded as an un-parseable poison pill threat.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public boolean coordinate(String messageId, String eventTypeName, String rawPayload, Map<String, String> headers) {
        // 1. Resolve the matching extension handler component out of the localized registry map
        InboxPayloadPlugin<?> plugin = pluginRegistry.get(eventTypeName);
        if (plugin == null) {
            log.error("No registered core-inbox plugin found for event type: [{}]. Dropping message.", eventTypeName);
            return false;
        }

        // 2. Perform early boundary validation to trap and isolate corrupt payload strings before touching the database
        Optional<?> domainContextOpt = plugin.validate(rawPayload);
        if (domainContextOpt.isEmpty()) {
            log.error("Poison pill detected for messageId: [{}], type: [{}]. Message dropped before DB persist.",
                    messageId, eventTypeName);
            return false;
        }

        // 3. Assemble the immutable type-safe container record carrying the pre-warmed domain object
        InboxEvent<?> inboxEvent = InboxEvent.builder()
                .messageId(messageId)
                .eventType(plugin.getSupportedType())
                .payload(rawPayload)
                .domainContext(domainContextOpt.get()) // POJO готов для in-memory буфера
                .headers(headers != null ? headers : Map.of())
                .build();

        // 4. Force atomic persistence into the storage engine utilizing native constraints as the primary deduplication mechanism
        boolean isInserted = inboxRepository.save(inboxEvent);
        if (!isInserted) {
            log.debug("Duplicate message detected and skipped at DB primary-key layer: {}", messageId);
            return false;
        }

        // 5. Synchronize memory state transformations strictly against current active Spring TX transaction boundaries
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    offerToMemoryQueue(inboxEvent);
                }
            });
        } else {
            // Safe fallback path for auto-commit non-transactional transport endpoints
            offerToMemoryQueue(inboxEvent);
        }

        return true;
    }

    /**
     * Places the verified message event record directly inside the streaming low-latency pipeline storage array.
     * Implements implicit reactive backpressure behavior by maintaining data inside PostgreSQL should memory allocations hit limits.
     */
    private void offerToMemoryQueue(InboxEvent<?> event) {
        boolean queued = inboxMemoryQueue.offer(event);
        if (!queued) {
            // Buffer capacity bound reached. Row is abandoned in memory but remains committed as 'PROCESSING' inside the DB.
            // The distributed background recovery engine will safely sweep, unlock, and process it during a later cycle.
            log.warn("Inbox In-Memory buffer is FULL. Event {} left in DB for async Recovery Engine sweep.", event.messageId());
        }
    }
}
