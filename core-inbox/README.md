# Core Transactional Inbox Library

Provides a robust, isolated, and high-performance implementation of the **Transactional Inbox** pattern, specifically designed for high-throughput transactional, processing, and brokerage systems. It guarantees **At-Least-Once** event ingestion, native database-level deduplication, and execution with zero enterprise overhead, completely eliminating heavy AOP aspects and Spring proxy-magic.

The library is fully optimized for **Java 21 Virtual Threads** via thread-per-task executors and features a built-in **Backpressure Guard** and **Isolated Rate Limiting** to protect domain handlers from unpredictable traffic spikes.

---

## Core Architectural Principles (Staff Design)

1. **Explicit Thread Separation:** Distinct execution pools (`TaskScheduler`) are registered for the low-latency critical path (in-memory queue draining and processing) and heavy background database tasks (`Recovery`/`Purge`). Database maintenance routines will never block inbound message ingestion.
2. **Backpressure Guard:** Automated database and connection pool (`HikariCP`) protection. If the in-memory queue utilization exceeds 50%, the background distributed replication and recovery worker (`Recovery Engine`) yields execution cycles, completely eliminating parasitic `FOR UPDATE SKIP LOCKED` database overhead during high load.
3. **Split-Batching Update & Zero DB Read:** Complete elimination of database reads on the happy path by writing events directly in the `PROCESSING` state. To minimize network round-trips, successful execution statuses are updated via a single batch `WHERE message_id IN (:ids)` query, reducing network and disk I/O strain up to 50x.

---

## Add Dependency to pom.xml

The library operates as a self-contained, auto-configuring starter, requiring only a primary database driver and `spring-jdbc` in the target microservice application.

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-inbox</artifactId>
</dependency>
```

## YAML Configuration Reference (application.yml)

All parameters are fully exposed and overriding-ready via container environment variables (12-Factor App compliant). Type-safe, compile-time default configurations are enforced natively within Java Records.

```yaml
io:
  github:
    dgavrikov:
      core:
        inbox:
          # Low-latency internal buffer for fast offloading of inbound transport threads (Kafka Consumers)
          in-memory-queue:
            capacity: \${INBOX_IN_MEMORY_QUEUE_CAPACITY:10000}

          # Batch worker configurations for memory queue draining and processing on Virtual Threads
          worker-props:
            initial-delay-ms: \${INBOX_WORKER_INITIAL_DELAY_MS:500}
            scan-memory-queue-interval-delay-ms: \${INBOX_WORKER_SCAN_INTERVAL_MS:25}
            batch-size: \${INBOX_WORKER_BATCH_SIZE:50}

          # Distributed recovery worker settings for pulling stale 'PROCESSING' or expired 'FAILED' events
          recovery-props:
            initial-delay-ms: \${INBOX_RECOVERY_INITIAL_DELAY_MS:5000}
            recovery-interval-delay-ms: \${INBOX_RECOVERY_INTERVAL_DELAY_MS:60000}
            batch-size: \${INBOX_RECOVERY_BATCH_SIZE:100}
            time-depth-sec: \${INBOX_RECOVERY_TIME_DEPTH_SEC:30}

          # Historical/successfully processed inbox log purging pipeline settings
          cleanup-props:
            cron-expression: "\${INBOX_CLEANUP_CRON_EXPRESSION:0 0 0 * * *}"
            depth-in-hour: \${INBOX_CLEANUP_DEPTH_IN_HOUR:72}
            batch-size: \${INBOX_CLEANUP_BATCH_SIZE:10000}
```

---

## Database Schema (Liquibase Migration)

It is recommended to include this script into your primary `db.changelog-master.yaml` using the native `include` element. Database indexes utilize PostgreSQL Partial Indexes tailored for the `UNION ALL` recovery-engine strategy to prevent sequential scan performance issues at scale, while `message_id` serves as the primary key for deduplication.

```yaml
databaseChangeLog:
  - changeSet:
      id: core-inbox-create-messages-table-v2
      author: d.gavrikov
      comment: Create unified system table for Transactional Inbox with high-load partial indexes
      changes:
        - createTable:
            tableName: inbox_events
            columns:
              - column:
                  name: message_id
                  type: VARCHAR(64)
                  constraints:
                    primaryKey: true
                    nullable: false
              - column:
                  name: event_type
                  type: VARCHAR(64)
                  constraints:
                    nullable: false
              - column:
                  name: payload
                  type: JSONB
                  constraints:
                    nullable: false
              - column:
                  name: headers
                  type: JSONB
                  constraints:
                    nullable: true
              - column:
                  name: status
                  type: VARCHAR(64)
                  defaultValue: "PROCESSING"
                  constraints:
                    nullable: false
              - column:
                  name: retry_count
                  type: INT
                  defaultValue: 0
                  constraints:
                    nullable: false
              - column:
                  name: reason
                  type: VARCHAR(1000)
                  constraints:
                    nullable: true
              - column:
                  name: next_execution_at
                  type: TIMESTAMP WITH TIME ZONE
                  defaultValueComputed: NOW()
                  constraints:
                    nullable: false
              - column:
                  name: created_at
                  type: TIMESTAMP WITH TIME ZONE
                  defaultValueComputed: NOW()
                  constraints:
                    nullable: false
              - column:
                  name: updated_at
                  type: TIMESTAMP WITH TIME ZONE
                  defaultValueComputed: NOW()
                  constraints:
                    nullable: false

        # Index 1: Designed for lightning-fast scanning of stale inboxes (Zero DB Read Recovery path)
        - createIndex:
            indexName: idx_inbox_recovery_processing
            tableName: inbox_events
            columns:
              - column:
                  name: updated_at
              - column:
                  name: next_execution_at
            where: "status = 'PROCESSING'"

        # Index 2: Optimized for polling failed events managed by exponential backoff scheduling
        - createIndex:
            indexName: idx_inbox_recovery_failed
            tableName: inbox_events
            columns:
              - column:
                  name: next_execution_at
            where: "status = 'FAILED'"

        # Index 3: Highly efficient partial index for batched purging of historical processed logs
        - createIndex:
            indexName: idx_inbox_purge_historical
            tableName: inbox_events
            columns:
              - column:
                  name: updated_at
            where: "status = 'PROCESSED'"
```

---

## Extension Point Implementation Example (InboxPayloadPlugin)

Implement this interface within your application or domain layer to handle incoming event deserialization and execute pure business logic. The `process` method execution is decoupled from transport runtimes and runs seamlessly on **Java 21 Virtual Threads** with no Spring AOP proxy overhead.

```java
package io.github.dgavrikov.examples.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxEventType;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountCreatedInboxPlugin implements InboxPayloadPlugin<AccountDto> {

    private final AccountService accountService;
    private final ObjectMapper objectMapper;

    @Override
    public InboxEventType getSupportedType() {
        return () -> "ACCOUNT_CREATED";
    }

    @Override
    public AccountDto deserialize(String rawPayload) {
        try {
            return objectMapper.readValue(rawPayload, AccountDto.class);
        } catch (Exception e) {
            throw new RuntimeException("Inbox payload de-serialization error", e);
        }
    }

    @Override
    public void process(InboxEvent event, AccountDto domainContext) throws Exception {
        // Core domain business logic execution within an isolated Virtual Thread runtime
        accountService.handleAccountCreation(domainContext);
    }

    @Override
    public int getTpsLimit() {
        return 500; // Dedicated target throttling constraint (500 events per second max) to protect core business logic
    }

    @Override
    public String getRateLimitGroupId() {
        return "account-processing-group"; // Blends distinct plugin components under a single lock-free rate limiter
    }
}
```

---

## Transport Ingestion Layer Integration Example (Kafka Consumer)

Initial message registration and immediate database-level idempotency checks execute through the `InboxPlatformCoordinator` safely within active transaction boundary limits. The engine delegates the event to the low-latency processing in-memory queue via `TransactionSynchronization.afterCommit()`, completely mitigating phantom data processing and event drop risks if a pod crashes.

```java
package io.github.dgavrikov.examples.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.service.InboxPlatformCoordinator;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AccountKafkaConsumer {

    private final InboxPlatformCoordinator inboxCoordinator;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "account-events-topic", groupId = "core-inbox-account-group")
    @Transactional // Forces atomic persistence in DB with initial 'PROCESSING' state
    public void onMessage(ConsumerRecord<String, String> record) {
        
        // 1. Build immutable infrastructure InboxEvent
        InboxEvent inboxEvent = InboxEvent.builder()
                .messageId(record.key()) // Kafka record key acts as the Primary Key for immediate deduplication
                .eventType(() -> "ACCOUNT_CREATED")
                .payload(record.value())
                .headers(Map.of("partition", String.valueOf(record.partition())))
                .build();

        // 2. Coordinate persistence and deduplication
        // The method triggers a native INSERT ... ON CONFLICT DO NOTHING block.
        // Returns false on duplicates, cutting off redundant execution instantly.
        // On success, registers TransactionSynchronization.afterCommit() to push into the in-memory queue.
        boolean isUnique = inboxCoordinator.coordinate(inboxEvent);
        
        if (!isUnique) {
            // Duplicate event detected and safely dropped at DB layer. Domain logic won't be invoked.
            return;
        }
        
        // Transaction completes and commits, allowing background workers to safely process on Virtual Threads
    }
}
```

---
