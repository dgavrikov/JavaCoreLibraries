# Core Transactional Outbox Library

Provides a robust, isolated, and high-performance implementation of the **Transactional Outbox** pattern, specifically
designed for high-throughput processing and brokerage systems. It guarantees **At-Least-Once** event delivery with zero
enterprise overhead, completely eliminating heavy AOP aspects and Spring proxy-magic.

The library is fully optimized for **Java 21 Virtual Threads** (ensuring Zero Thread-Pinning through Lock-Free structures)
and features built-in **Priority Lane Isolation**, **Backpressure Guard**, and **Isolated Rate Limiting** mapped per event destination.

---

## Core Architectural Principles (Staff Design)

1. **Priority Lane Isolation:** Traffic is partitioned into 3 independent lanes (`HIGH`, `MEDIUM`, `LOW`) backed by dedicated fixed-size queues (`ArrayBlockingQueue`) and isolated worker threads. Slow downstream consumers with low RPS or background event storms in `LOW` will never block critical transactions in `HIGH`.
2. **Explicit Thread Separation:** Distinct execution pools (`TaskScheduler`) are registered for the low-latency critical
   path (event publishing) and heavy background database tasks (recovery/purge). Database maintenance routines will never
   block broker event ingestion.
3. **Backpressure Guard:** Automated database protection. If all priority in-memory queues exceed 50% capacity utilization,
   the background polling worker (Recovery) yields execution cycles, eliminating parasitic database overhead during high load.
   When capacity is available, recovery drains events strictly in descending priority order (`priority DESC`).
4. **Split-Batching Update:** Network round-trip minimization. Successful execution statuses are updated via a single
   native PostgreSQL batch query (`WHERE id = ANY (CAST(:ids AS BIGINT[]))`), reducing query parser and disk I/O strain by orders of magnitude.

---

## Add Dependency to pom.xml

The library operates as a self-contained, auto-configuring starter, requiring only a primary database driver and
`spring-jdbc` in the target microservice application.

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-outbox</artifactId>
</dependency>
```

---

## YAML Configuration Reference (application.yml)

All parameters are fully exposed and override-ready via container environment variables (12-Factor App compliant).
Compile-time default configurations are enforced natively within Java Records (`OutboxProperties`).

Each priority lane (`high-lane`, `medium-lane`, `low-lane`) exposes independent memory queue capacities,
scheduling intervals, and batch drain sizes:

```yaml
io:
  github:
    dgavrikov:
      core:
        outbox:
          in-memory-queue:
            capacity: \${OUTBOX_IN_MEMORY_QUEUE_CAPACITY:10000}
          publisher-props:
            high-lane:
              queue-capacity: \${OUTBOX_PUBLISHER_HIGH_QUEUE_CAPACITY:5000}
              initial-delay-ms: \${OUTBOX_PUBLISHER_HIGH_INITIAL_DELAY_MS:1000}
              scan-interval-delay-ms: \${OUTBOX_PUBLISHER_HIGH_SCAN_INTERVAL_MS:25}
              batch-size: \${OUTBOX_PUBLISHER_HIGH_BATCH_SIZE:100}
            medium-lane:
              queue-capacity: \${OUTBOX_PUBLISHER_MEDIUM_QUEUE_CAPACITY:10000}
              initial-delay-ms: \${OUTBOX_PUBLISHER_MEDIUM_INITIAL_DELAY_MS:2000}
              scan-interval-delay-ms: \${OUTBOX_PUBLISHER_MEDIUM_SCAN_INTERVAL_MS:250}
              batch-size: \${OUTBOX_PUBLISHER_MEDIUM_BATCH_SIZE:100}
            low-lane:
              queue-capacity: \${OUTBOX_PUBLISHER_LOW_QUEUE_CAPACITY:5000}
              initial-delay-ms: \${OUTBOX_PUBLISHER_LOW_INITIAL_DELAY_MS:3000}
              scan-interval-delay-ms: \${OUTBOX_PUBLISHER_LOW_SCAN_INTERVAL_MS:1000}
              batch-size: \${OUTBOX_PUBLISHER_LOW_BATCH_SIZE:100}
          recovery-props:
            initial-delay-ms: \${OUTBOX_RECOVERY_INITIAL_DELAY_MS:5000}
            recovery-interval-delay-ms: \${OUTBOX_RECOVERY_INTERVAL_DELAY_MS:60000}
            batch-size: \${OUTBOX_RECOVERY_BATCH_SIZE:100}
            time-depth-sec: \${OUTBOX_RECOVERY_TIME_DEPTH_SEC:30}
          cleanup-props:
            cron-expression: "\${OUTBOX_CLEANUP_CRON_EXPRESSION:0 0 0 * * *}"
            depth-in-hour: \${OUTBOX_CLEANUP_DEPTH_IN_HOUR:72}
            batch-size: \${OUTBOX_CLEANUP_BATCH_SIZE:10000}
```

---

## Database Schema (Liquibase Migration)

The library ships with a built-in native PostgreSQL changelog (`db/changelog/core-outbox/postgresql-outbox-v1.yml`).
Include this file into your primary `db.changelog-master.yaml` using the native `include` element:

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/core-outbox/postgresql-outbox-v1.yml
```

Database indexes leverage **PostgreSQL Partial Indexes** aligned with event priority ordering
(`priority DESC, updated_at ASC, id ASC WHERE status = 'NEW'`), preventing table performance degradation
at multimillion-row scale while guaranteeing priority-first recovery for critical events.

---
## Extension Point Implementation Example (OutboxPayloadPlugin)

Implement this interface within your service's application or infrastructure layer to bind a concrete external client
target (e.g., an active Kafka Producer). Processing priority is configured at the plugin level via `getPriority()`,
preserving strict **FIFO** ordering for all events sharing the same partition routing key (`keyId`).

```java
package io.github.dgavrikov.examples.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxEventType;
import io.github.dgavrikov.core.outbox.model.OutboxPayloadPlugin;
import io.github.dgavrikov.core.outbox.model.OutboxPriority;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccountCreatedKafkaPlugin implements OutboxPayloadPlugin<AccountDto> {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public OutboxEventType getSupportedType() {
        return () -> "ACCOUNT_CREATED";
    }

    @Override
    public OutboxPriority getPriority() {
        // Routes events to the isolated high-priority lane (defaults to MEDIUM)
        return OutboxPriority.HIGH;
    }

    @Override
    public String extractKeyId(AccountDto sourceData) {
        return sourceData.getNumber();
    }

    @Override
    public String createPayload(AccountDto sourceData) {
        try {
            return objectMapper.writeValueAsString(sourceData);
        } catch (Exception e) {
            throw new RuntimeException("Payload serialization error", e);
        }
    }

    @Override
    public void sendEvent(OutboxEvent outboxEvent) {
        // Physical ingestion to broker cluster (Critical Path)
        kafkaTemplate.send("account-events-topic", outboxEvent.keyId(), outboxEvent.payload());
    }

    @Override
    public int getTpsLimit() {
        return 250; // Hardcoded target system throttling constraint (250 events per second max)
    }

    @Override
    public String getRateLimitGroupId() {
        return "kafka-cluster-account"; // Groups distinct plugin instances under a unified limiter instance
    }
}
```
---
## Domain Service Integration Example

Event state recording executes safely through the `OutboxPlatformCoordinator` strictly inside active business transaction
boundary limits. The engine routes the event into the target priority in-memory queue (`HIGH`, `MEDIUM`, or `LOW`)
strictly via `TransactionSynchronization.afterCommit()`, eliminating phantom data transmissions.

```java
package io.github.dgavrikov.examples.service;

import io.github.dgavrikov.core.outbox.service.OutboxPlatformCoordinator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountService {

    private final AccountRepository accountRepository;
    private final OutboxPlatformCoordinator outboxCoordinator;

    @Transactional
    public void createAccount(AccountDto dto) {
        // 1. Mutate application domain business state (DB write context)
        Account account = accountRepository.save(new Account(dto.number(), dto.holder()));
        
        // 2. Declarative Event logging capture via Transactional Outbox layer.
        // The coordinator automatically extracts keyId, payload, headers, and priority from the plugin,
        // persists the record into outbox_events, and schedules queuing strictly afterCommit()
        outboxCoordinator.saveEvent(
                () -> "ACCOUNT_CREATED", 
                new AccountCreatedEvent(account.getNumber(), account.getHolder())
        );
    }
}
```

> **Note:** For scenarios where the payload and transport headers are assembled beforehand at the application layer
> (outside a specific entity lifecycle), use the `outboxCoordinator.savePrebuiltEvent(...)` overloads with an explicit
> or default (`MEDIUM`) priority.
