# Библиотека транзакционного лога входящих сообщений (Core Transactional Inbox)

Высокопроизводительная реализация паттерна **Transactional Inbox** для высоконагруженных систем на базе **Java 21 Virtual Threads**, обеспечивающая обработку **At-Least-Once** без тяжелого enterprise-оверхеда.

---

## Архитектурные принципы (Staff Design)

1. **Explicit Thread Separation:** Раздельные пулы потоков для low-latency обработки in-memory буфера и фоновых задач СУБД.
2. **Backpressure Guard:** Защита СУБД от перегрузки при заполнении очереди более чем на 50%.
3. **Split-Batching Update & Zero DB Read:** Отказ от чтений на счастливом пути и пакетное обновление статусов через `WHERE message_id IN (:ids)`.

---

## Добавить зависимость в pom.xml

Библиотека поставляется как автономный автоконфигурируемый стартер и требует только базовый драйвер СУБД и `spring-jdbc` в конечном приложении.

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-inbox</artifactId>
</dependency>
```

---

## Конфигурация YAML (application.yml)

Все параметры полностью переопределяемы через переменные окружения контейнера (12-Factor App). Настройки по умолчанию зашиты в Java Records на уровне компиляции.

```yaml
io:
  github:
    dgavrikov:
      core:
        inbox:
          # Внутренняя low-latency очередь для быстрой разгрузки транспортных потоков (Kafka консьюмеров)
          in-memory-queue:
            capacity: \${INBOX_IN_MEMORY_QUEUE_CAPACITY:10000}

          # Настройки батчинга для выгребания из памяти и процессинга на виртуальных потоках
          worker-props:
            initial-delay-ms: \${INBOX_WORKER_INITIAL_DELAY_MS:500}
            scan-memory-queue-interval-delay-ms: \${INBOX_WORKER_SCAN_INTERVAL_MS:25}
            batch-size: \${INBOX_WORKER_BATCH_SIZE:50}

          # Распределенный движок восстановления (поднятие «зависших» PROCESSING и FAILED хвостов)
          recovery-props:
            initial-delay-ms: \${INBOX_RECOVERY_INITIAL_DELAY_MS:5000}
            recovery-interval-delay-ms: \${INBOX_RECOVERY_INTERVAL_DELAY_MS:60000}
            batch-size: \${INBOX_RECOVERY_BATCH_SIZE:100}
            time-depth-sec: \${INBOX_RECOVERY_TIME_DEPTH_SEC:30}

          # Очистка архивных / успешно обработанных входящих сообщений
          cleanup-props:
            cron-expression: "\${INBOX_CLEANUP_CRON_EXPRESSION:0 0 0 * * *}"
            depth-in-hour: \${INBOX_CLEANUP_DEPTH_IN_HOUR:72}
            batch-size: \${INBOX_CLEANUP_BATCH_SIZE:10000}
```

---

## Схема СУБД (Liquibase Миграция)

Рекомендуется подключить этот файл в ваш основной `db.changelog-master.yaml` через механизм `include`. Индексы используют оптимизацию **частичных индексов PostgreSQL (Partial Indexes)** под стратегию `UNION ALL` рекавери-движка, что полностью исключает `Sequential Scan` и деградацию производительности таблицы `inbox_events` при миллионных объемах. Первичным ключом выступает нативный `message_id`, что гарантирует дедупликацию на уровне ограничений СУБД.

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

        # Индекс 1: Для моментального поиска зависших в обработке инбоксов (Zero DB Read Recovery)
        - createIndex:
            indexName: idx_inbox_recovery_processing
            tableName: inbox_events
            columns:
              - column:
                  name: updated_at
              - column:
                  name: next_execution_at
            where: "status = 'PROCESSING'"

        # Индекс 2: Для поиска упавших по экспоненциальному бэкаффу
        - createIndex:
            indexName: idx_inbox_recovery_failed
            tableName: inbox_events
            columns:
              - column:
                  name: next_execution_at
            where: "status = 'FAILED'"

        # Индекс 3: Высокоэффективная партиционированная очистка исторических логов
        - createIndex:
            indexName: idx_inbox_purge_historical
            tableName: inbox_events
            columns:
              - column:
                  name: updated_at
            where: "status = 'PROCESSED'"
```

---

## Пример реализации точки интеграции (InboxPayloadPlugin)

Реализуйте этот интерфейс в прикладном или доменном слое вашего сервиса для десериализации входящего payload и выполнения чистой бизнес-логики без магии Spring AOP, рефлексии и прокси-оберток. Метод `process` гарантированно исполняется внутри изолированного рантайма на **Java 21 Virtual Threads**.

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
        // Точка выполнения чистой бизнес-логики в изолированном виртуальном потоке
        accountService.handleAccountCreation(domainContext);
    }

    @Override
    public int getTpsLimit() {
        return 500; // Выделенный лимит пропускной способности (500 TPS) для защиты данной доменной группы
    }

    @Override
    public String getRateLimitGroupId() {
        return "account-processing-group"; // Позволяет объединять разные типы событий под один lock-free лимитер
    }
}
```

---

## Использование в транспортном слое (Kafka Consumer)

Первичная фиксация входящего сообщения и его мгновенная дедупликация на native-ограничениях первичного ключа происходят через `InboxPlatformCoordinator` внутри границ активной транзакции (транспорта или ручной бизнес-сессии). Сообщение попадет в low-latency in-memory очередь для асинхронной обработки виртуальными потоками **строго после успешного коммита транзакции в БД**, полностью исключая race conditions и утерю сообщений при падении пода.

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
    @Transactional // Гарантирует атомарную фиксацию в СУБД со статусом PROCESSING
    public void onMessage(ConsumerRecord<String, String> record) {
        
        // 1. Формируем неизменяемый инфраструктурный InboxEvent
        InboxEvent inboxEvent = InboxEvent.builder()
                .messageId(record.key()) // Идентификатор из Кафки — наш Primary Key для дедупликации
                .eventType(() -> "ACCOUNT_CREATED")
                .payload(record.value())
                .headers(Map.of("partition", String.valueOf(record.partition())))
                .build();

        // 2. Координируем запись.
        // Метод выполнит нативный INSERT ... ON CONFLICT DO NOTHING.
        // При дубликате метод вернет false, мгновенно прерывая выполнение.
        // При успехе — зарегистрирует TransactionSynchronization.afterCommit() для пуша в in-memory очередь.
        boolean isUnique = inboxCoordinator.coordinate(inboxEvent);
        
        if (!isUnique) {
            // Сообщение-дубликат отсечено на уровне СУБД. Никаких повторных вызовов бизнес-логики.
            return;
        }
        
        // Транзакция завершается, коммитится, и воркер асинхронно забирает событие на выполнение в Virtual Threads
    }
}
```
