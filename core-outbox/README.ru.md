# Библиотека транзакционного лога событий (Core Transactional Outbox)

Предоставляет надежную, изолированную и высокопроизводительную реализацию паттерна **Transactional Outbox**, разработанную
специально для высоконагруженных процессинговых и брокерских систем. Гарантирует доставку сообщений **At-Least-Once** без
enterprise-оверхеда, тяжелых AOP-аспектов и Spring-магии. Полностью оптимизирована под **Java 21 Virtual Threads**
(Zero Thread-Pinning / Lock-Free конструкции) и предоставляет встроенные механизмы **Priority Lane Isolation**,
**Backpressure Guard** и **Изолированного Rate Limiting** по типам получателей.

---

## Архитектурные принципы платформы (Staff Design)

1. **Priority Lane Isolation (Изолированные полосы приоритетов):** Трафик разделяется на 3 независимых контура (`HIGH`, `MEDIUM`, `LOW`) с выделенными очередями (`ArrayBlockingQueue`) и отдельными потоками обработки. Медленные потребители с низким RPS или шторм фоновых событий в `LOW` никогда не заблокируют критичные транзакции в `HIGH`.
2. **Explicit Thread Separation:** Раздельные пулы потоков (`TaskScheduler`) для критического пути отправки (`Low-Latency`)
   и тяжелых фоновых задач СУБД (`Recovery/Purge`). Очистка базы никогда не затормозит отправку в брокер.
3. **Backpressure Guard:** Автоматическая защита СУБД. Если все приоритетные in-memory очереди заполнены более чем на 50%, фоновый
   поллер (`Recovery`) засыпает и не генерирует паразитную нагрузку на базу данных. При наличии свободного места восстановление вычитывает события строго по убыванию приоритета (`priority DESC`).
4. **Split-Batching Update:** Минимизация сетевых `round-trip` до БД. Статусы успешных отправок обновляются одной пачкой
   через нативный PostgreSQL-запрос `WHERE id = ANY (CAST(:ids AS BIGINT[]))`, снижая нагрузку на парсер запросов и дисковую подсистему в десятки раз.

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
зашиты в Java Records (`OutboxProperties`) на уровне компиляции.

Для каждой приоритетной полосы (`high-lane`, `medium-lane`, `low-lane`) задаются независимые емкость очереди в памяти,
тайминги запуска и размер вычитываемого батча:

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

## Схема СУБД (Liquibase Миграция)

Библиотека поставляется со встроенным нативным скриптом для PostgreSQL (`db/changelog/core-outbox/postgresql-outbox-v1.yml`).
Подключите его в ваш основной `db.changelog-master.yaml` через механизм `include`:

```yaml
databaseChangeLog:
  - include:
      file: db/changelog/core-outbox/postgresql-outbox-v1.yml
```

Индексы используют оптимизацию **частичных индексов PostgreSQL (Partial Indexes)** с учетом приоритета доставки
(`priority DESC, updated_at ASC, id ASC WHERE status = 'NEW'`), исключая деградацию производительности таблицы `outbox_events`
при миллионных объемах и гарантируя первоочередное восстановление критичных событий.

---

## Пример реализации точки интеграции (OutboxPayloadPlugin)

Реализуйте этот интерфейс в прикладном или инфраструктурном слое вашего сервиса для конкретной внешней системы (например, Kafka Producer).
Приоритет обработки задается на уровне плагина через `getPriority()`, что гарантирует строгий порядок доставки (**FIFO**) для всех событий одного ключа партиционирования (`keyId`).

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
        // Направляем событие в выделенную высокоприоритетную полосу (по умолчанию MEDIUM)
        return OutboxPriority.HIGH;
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
        kafkaTemplate.send("account-events-topic", outboxEvent.keyId(), outboxEvent.payload());
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

Сохранение события происходит через `OutboxPlatformCoordinator` строго внутри границ активной бизнес-транзакции. Событие
попадет в целевую приоритетную in-memory очередь (`HIGH`, `MEDIUM` или `LOW`) **только после успешного коммита транзакции в БД**,
полностью исключая фантомные отправки.

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
        // Координатор сам извлечет keyId, payload, headers и приоритет из плагина, 
        // запишет строку в outbox_events и зарегистрирует отправку в нужную очередь строго afterCommit()
        outboxCoordinator.saveEvent(
                () -> "ACCOUNT_CREATED", 
                new AccountCreatedEvent(account.getNumber(), account.getHolder())
        );
    }
}
```

> **Примечание:** Для сценариев, где payload и заголовки собираются заранее на уровне приложения (вне жизненного цикла
> конкретной сущности), используйте перегрузки `outboxCoordinator.savePrebuiltEvent(...)` с явным или дефолтным (`MEDIUM`) приоритетом.
