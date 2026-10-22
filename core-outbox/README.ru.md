# Библиотека транзакционного лога событий (Core Transactional Outbox)

Предоставляет надежную, изолированную и высокопроизводительную реализацию паттерна **Transactional Outbox**, разработанную 
специально для высоконагруженных процессинговых и брокерских систем. Гарантирует доставку сообщений **At-Least-Once** без 
enterprise-оверхеда, тяжелых AOP-аспектов и Spring-магии.Полностью оптимизирована под **Java 21 Virtual Threads** 
(Zero Thread-Pinning / Lock-Free конструкции) и предоставляет встроенные механизмы **Backpressure Guard** и **Изолированного 
Rate Limiting** по типам получателей.

---

## Архитектурные принципы платформы (Staff Design)

1. **Explicit Thread Separation:** Раздельные пулы потоков (`TaskScheduler`) для критического пути отправки (`Low-Latency`) 
и тяжелых фоновых задач СУБД (`Recovery/Purge`). Очистка базы никогда не затормозит отправку в брокер.
2. **Backpressure Guard:** Автоматическая защита СУБД. Если in-memory очередь заполнена более чем на 50%, фоновый 
полировщик (`Recovery`) засыпает и не генерирует паразитную нагрузку на базу данных.Lock-Free
3. **Split-Batching Update:** Минимизация сетевых `round-trip` до БД. Статусы успешных отправок обновляются одной пачкой 
через эффективный `WHERE id IN (:ids)` запрос, снижая нагрузку на сеть и дисковую подсистему в 50 раз.

---

## Добавить зависимость в pom.xml

Библиотека поставляется как автономный автоконфигурируемый стартер и требует только базовый драйвер СУБД и `spring-jdbc` 
в конечном приложении.

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-outbox</artifactId>
</dependency>
```

---

## Конфигурация YAML (application.yml)

Все параметры полностью переопределяемы через переменные окружения контейнера (12-Factor App). Настройки по умолчанию 
зашиты в Java Records на уровне компиляции.

```yaml
io:
  github:
    dgavrikov:
      core:
        outbox:
          # Внутренняя low-latency очередь для быстрой разгрузки транзакционных потоков
          in-memory-queue:
            capacity: ${OUTBOX_IN_MEMORY_QUEUE_CAPACITY:10000}

          # Настройки батчинга для отправки в брокер (Kafka/RabbitMQ)
          batch-publisher:
            initial-delay-ms: ${OUTBOX_BATCH_PUBLISHER_INITIAL_DELAY_MS:500}
            scan-memory-queue-interval-delay-ms: ${OUTBOX_BATCH_PUBLISHER_SCAN_INTERVAL_MS:25}
            batch-size: ${OUTBOX_BATCH_PUBLISHER_BATCH_SIZE:50}

          # Процесс восстановления, который поднимает из БД «хвосты»
          recovery-props:
            initial-delay-ms: ${OUTBOX_RECOVERY_INITIAL_DELAY_MS:5000}
            recovery-interval-delay-ms: ${OUTBOX_RECOVERY_INTERVAL_DELAY_MS:60000}
            batch-size: ${OUTBOX_RECOVERY_BATCH_SIZE:100}
            time-depth-sec: ${OUTBOX_RECOVERY_TIME_DEPTH_SEC:30}

          # Очистка архивных/успешно отправленных данных
          partitioning:
            manage-cron-expression: "${OUTBOX_PARTITIONING_MANAGE_CRON_EXPRESSION:0 15 0 * * *}"
            cleanup-cron-expression: "${OUTBOX_PARTITIONING_CLEANUP_CRON_EXPRESSION:0 0 1 * * *}"
            count-days-soft-delete: ${OUTBOX_CLEANUP_COUNT_DAYS_SOFT_DELETE:3}
            count-days-hard-delete: ${OUTBOX_CLEANUP_COUNT_DAYS_HARD_DELETE:5}
            days-behind: ${OUTBOX_PARTITIONING_DAYS_BEHIND:7}
            days-ahead: ${OUTBOX_PARTITIONING_DAYS_AHEAD:7}
```

---

## Схема СУБД (Liquibase Миграция)

Рекомендуется подключить этот файл в ваш основной `db.changelog-master.yaml` через механизм `include`. Индексы используют 
оптимизацию частичных индексов PostgreSQL (Partial Indexes), что полностью исключает деградацию производительности основной 
таблицы при миллионных объемах логов.

### Порядок подключения.

1. Изоляция устаревшей схемы данных

Если вы обновляетесь с версии 1.x, необходимо переименовать старую несекционированную таблицу, чтобы освободить системное имя для мастер-таблицы новой архитектуры.

```yaml
databaseChangeLog:
  - include:
    file: "db/changelog/core-outbox/db.changelog-outbox-1.0-rename.yml"
```

2. Развертывание партиционированной структуры

Подключите базовый скрипт для создания мастер-таблицы с декларативным секционированием по диапазону дат 
`(PARTITION BY RANGE (created_at))` и дефолтной секции (`outbox_events_default`).

```yaml
databaseChangeLog:
  - include:
    file: "db/changelog/core-outbox/db.changelog-outbox-v3.0-partitions.yml"

```

3. Перенос незавершенных транзакционных логов

Если в старой таблице остались необработанные события (статус `NEW`), подключите скрипт миграции. Он атомарно перенесет 
активный хвост сообщений в новую партиционированную структуру для предотвращения потери данных.

```yaml
databaseChangeLog:
  - include:
    file: "db/changelog/core-outbox/db.changelog-outbox-v3.0-migration.yml"

```

---

## Пример реализации точки интеграции (OutboxPayloadPlugin)

Реализуйте этот интерфейс в прикладном или инфраструктурном слое вашего сервиса для конкретной внешней системы (например, Kafka Producer).

```java
package io.github.dgavrikov.examples.outbox;

import io.github.dgavrikov.core.outbox.model.OutboxEvent;
import io.github.dgavrikov.core.outbox.model.OutboxEventType;
import io.github.dgavrikov.core.outbox.model.OutboxPayloadPlugin;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

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
    public String createPayload(AccountDto sourceData) {
        try {
            return objectMapper.writeValueAsString(sourceData);
        } catch (Exception e) {
            throw new RuntimeException("Payload serialization error", e);
        }
    }

    @Override
    public String extractKeyId(AccountDto sourceData) {
        return sourceData.getNumber();
    }

    @Override
    public void sendEvent(OutboxEvent outboxEvent) {
        // Физический пуш в брокер (Критический путь)
        kafkaTemplate.send("account-events-topic", outboxEvent.aggregateId(), outboxEvent.payload());
    }

    @Override
    public int getTpsLimit() {
        return 250; // Жесткий Rate Limit под данную внешнюю систему (250 сообщений в секунду)
    }

    @Override
    public String getRateLimitGroupId() {
        return "kafka-cluster-account"; // Позволяет группировать разные плагины под один лимитер
    }
}
```

---

## Использование в бизнес-сервисе

Сохранение события происходит через OutboxPlatformCoordinator строго внутри границ активной бизнес-транзакции. Событие 
попадет в in-memory очередь отправки **только после успешного коммита транзакции в БД**, полностью исключая фантомные отправки.

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
        // 1. Изменение бизнес-состояния (БД)
        Account account = accountRepository.save(new Account(dto.number(), dto.holder()));
        
        // 2. Декларативная фиксация события в Outbox. 
        // Метод запишет строку в outbox_events и зарегистрирует TransactionSynchronization.afterCommit()
        outboxCoordinator.saveEvent(
                account.getId().toString(), 
                () -> "ACCOUNT_CREATED", 
                new AccountCreatedEvent(account.getNumber(), account.getHolder())
        );
    }
}

```