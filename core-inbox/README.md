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

---

## Extension Point Implementation Example (InboxPayloadPlugin)

Implement this interface within your application or domain layer to handle incoming event validation and execute pure business logic. The `process` method execution is decoupled from transport runtimes and runs seamlessly on **Java 21 Virtual Threads** with no Spring AOP proxy overhead, accepting a fully prepared, strongly-typed domain context embedded inside the event record.

```java
package io.github.dgavrikov.examples.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxEventType;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Optional;

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
    public Optional<AccountDto> validate(String rawPayload) {
        try {
            // Combines deserialization and structural business validation at the entry point
            AccountDto dto = objectMapper.readValue(rawPayload, AccountDto.class);
            
            if (dto.getNumber() == null || dto.getNumber().isBlank()) {
                return Optional.empty(); // Safeguard against malformed business data
            }
            
            return Optional.of(dto);
        } catch (Exception e) {
            // Any syntax failure (Poison Pill) is safely intercepted before hitting the database
            return Optional.empty();
        }
    }

    @Override
    public void process(InboxEvent<AccountDto> event) throws Exception {
        // Core domain business logic execution within an isolated Virtual Thread runtime.
        // The domain context is pre-extracted and available natively via the immutable record.
        accountService.handleAccountCreation(event.domainContext());
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

Initial message registration, validation, and immediate database-level idempotency checks execute seamlessly through the `InboxPlatformCoordinator` acting as a single platform facade. The consumer layer is entirely isolated from internal processing mechanics, serialization internals, or registry management. The engine guarantees a low-latency in-memory push via `TransactionSynchronization.afterCommit()`, completely mitigating phantom processing risks.

```java
package io.github.dgavrikov.examples.listener;

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

    @KafkaListener(topics = "account-events-topic", groupId = "core-inbox-account-group")
    @Transactional // Forces atomic persistence in DB within the shared transport transaction context
    public void onMessage(ConsumerRecord<String, String> record) {

        // Delegate raw wire data to the single platform facade.
        // All validation, poison pill filtering, and deduplication logic are encapsulated within.
        boolean accepted = inboxCoordinator.coordinate(
                record.key(), // Kafka record key acts as the unique messageId for native primary key deduplication
                "ACCOUNT_CREATED",
                record.value(),
                Map.of("partition", String.valueOf(record.partition()))
        );

        if (!accepted) {
            // The method returns false gracefully for both duplicates and Poison Pills.
            // We just exit normally, allowing Kafka to safely commit the partition offset.
            return;
        }
    }
}
```

---
