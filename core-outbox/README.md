# Core Transactional Outbox Library

Provides a robust, isolated, and high-performance implementation of the **Transactional Outbox** pattern, specifically 
designed for high-throughput processing and brokerage systems. It guarantees **At-Least-Once** event delivery with zero 
enterprise overhead, completely eliminating heavy AOP aspects and Spring proxy-magic.

The library is fully optimized for **Java 21 Virtual Threads** (ensuring Zero Thread-Pinning through Lock-Free structures) 
and features built-in **Backpressure Guard** and **Isolated Rate Limiting** mapped per event destination.

---

## Core Architectural Principles (Staff Design)

1. **Explicit Thread Separation:** Distinct execution pools (TaskScheduler) are registered for the low-latency critical 
path (event publishing) and heavy background database tasks (recovery/purge). Database maintenance routines will never 
block broker event ingestion.
2. **Backpressure Guard:** Automated database protection. If the in-memory queue utilization exceeds 50%, the background 
polling worker (Recovery) yields execution cycles, eliminating parasitic database overhead during high load.
3. **Split-Batching Update:** Network round-trip minimization. Successful execution statuses are updated via a single 
batch `WHERE id IN (:ids)` query, reducing network and disk I/O strain up to 50x.

---

## Add Dependency to pom.xml

The library operates as a self-contained, auto-configuring starter, requiring only a primary database driver and 
spring-jdbc in the target microservice application.

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-outbox</artifactId>
</dependency>
```

## YAML Configuration Reference (application.yml)

All parameters are fully exposed and overriding-ready via container environment variables (12-Factor App compliant). 
Compile-time default configurations are enforced natively within Java Records.

```yaml
io:
  github:
    dgavrikov:
      core:
        outbox:
          # Low-latency internal queue for instant transactional database thread offloading
          in-memory-queue:
            capacity: ${OUTBOX_IN_MEMORY_QUEUE_CAPACITY:10000}

          # Batch publisher configurations for broker dispatching (Kafka/RabbitMQ)
          batch-publisher:
            initial-delay-ms: ${OUTBOX_BATCH_PUBLISHER_INITIAL_DELAY_MS:500}
            scan-memory-queue-interval-delay-ms: ${OUTBOX_BATCH_PUBLISHER_SCAN_INTERVAL_MS:25}
            batch-size: ${OUTBOX_BATCH_PUBLISHER_BATCH_SIZE:50}

          # Recovery worker configuration for polling abandoned/stale events from the DB
          recovery-props:
            initial-delay-ms: ${OUTBOX_RECOVERY_INITIAL_DELAY_MS:5000}
            recovery-interval-delay-ms: ${OUTBOX_RECOVERY_INTERVAL_DELAY_MS:60000}
            batch-size: ${OUTBOX_RECOVERY_BATCH_SIZE:100}
            time-depth-sec: ${OUTBOX_RECOVERY_TIME_DEPTH_SEC:30}

          # Historical/successfully sent data purging pipeline settings
          partitioning:
            manage-cron-expression: "${OUTBOX_PARTITIONING_MANAGE_CRON_EXPRESSION:0 15 0 * * *}"
            cleanup-cron-expression: "${OUTBOX_PARTITIONING_CLEANUP_CRON_EXPRESSION:0 0 1 * * *}"
            count-days-soft-delete: ${OUTBOX_CLEANUP_COUNT_DAYS_SOFT_DELETE:3}
            count-days-hard-delete: ${OUTBOX_CLEANUP_COUNT_DAYS_HARD_DELETE:5}
            days-behind: ${OUTBOX_PARTITIONING_DAYS_BEHIND:7}
            days-ahead: ${OUTBOX_PARTITIONING_DAYS_AHEAD:7}
```

---

## Database Schema (Liquibase Migration)

t is recommended to include this file in your main db.changelog-master.yaml using the include mechanism. The indexes 
leverage **PostgreSQL Partial Indexes** optimization, completely eliminating performance degradation of the core outbox 
table under high-throughput workloads with millions of records.

### Deployment & Migration Sequence

1. Legacy Schema Isolation

If migrating from version 1.x, you must first rename the legacy unpartitioned table. This frees up the canonical relational identifier for the new master table layout.

```yaml
databaseChangeLog:
  - include:
      file: "db/changelog/core-outbox/db.changelog-outbox-1.0-rename.yml"
```

2. Partitioned Core Infrastructure Deployment

Include the schema changelog to provision the master table configured with declarative range partitioning 
(`PARTITION BY RANGE (created_at)`), alongside its fallback default partition (`outbox_events_default`).

```yaml
databaseChangeLog:
  - include:
      file: "db/changelog/core-outbox/db.changelog-outbox-v3.0-partitions.yml"
```

3. In-Flight Event Data Migration

If the legacy table contains unprocessed transaction logs (status `NEW`), enable the migration script. This execution pass atomically moves the active event tail into the new partitioned layout to eliminate data loss.

```yaml
databaseChangeLog:
  - include:
      file: "db/changelog/core-outbox/db.changelog-outbox-v3.0-migration.yml"
```

---

## Extension Point Implementation Example (OutboxPayloadPlugin)

Implement this interface within your service's application or infrastructure layer to bind a concrete external client 
target (e.g., an active Kafka Producer).

```java
package io.github.dgavrikov.examples.outbox;

import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxEventType;
import io.github.dgavrikov.core.outbox.model.OutboxPayloadPlugin;
import lombok.RequiredArgsConstructor;
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
        kafkaTemplate.send("account-events-topic", outboxEvent.aggregateId(), outboxEvent.payload());
    }

    @Override
    public int getTpsLimit() {
        return 250; // Hardcoded target system throttling constraint (250 events per second max)
    }

    @Override
    public String getRateLimitGroupId() {
        return "kafka-cluster-account"; // Groups distinct plugin instances under a unified limiter thread pool
    }
}

```

---

## Domain Service Integration Example

vent state recording executes safely through the `OutboxPlatformCoordinator` strictly inside active business transaction 
boundary limits. The engine delegates the event to the processing in-memory queue via `TransactionSynchronization.afterCommit()`, 
mitigating phantom data transmissions.

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
        
        // 2. Declarative Event logging capture via Transactional Outbox layer
        outboxCoordinator.saveEvent(
                account.getId().toString(), 
                () -> "ACCOUNT_CREATED", 
                new AccountCreatedEvent(account.getNumber(), account.getHolder())
        );
    }
}

```