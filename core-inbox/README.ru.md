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

---

## Пример реализации точки интеграции (InboxPayloadPlugin)

Реализуйте этот интерфейс в прикладном или доменном слое вашего сервиса для валидации входящего payload и выполнения чистой бизнес-логики без магии Spring AOP, рефлексии и прокси-оберток. Метод `process` гарантированно исполняется внутри изолированного рантайма на **Java 21 Virtual Threads** и принимает полностью готовый, типизированный контекст.

```java
package io.github.dgavrikov.examples.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.inbox.model.InboxEvent;
import io.github.dgavrikov.core.inbox.model.InboxEventType;
import io.github.dgavrikov.core.inbox.model.InboxPayloadPlugin;
import lombok.RequiredArgsConstructor;
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
            // Совмещаем десериализацию и базовую структурную проверку на самом входе
            AccountDto dto = objectMapper.readValue(rawPayload, AccountDto.class);
            
            if (dto.getNumber() == null || dto.getNumber().isBlank()) {
                return Optional.empty(); // Защита от бизнес-мусора
            }
            
            return Optional.of(dto);
        } catch (Exception e) {
            // Любой синтаксический сбой (Poison Pill) отсекается до похода в базу данных
            return Optional.empty();
        }
    }

    @Override
    public void process(InboxEvent<AccountDto> event) throws Exception {
        // Точка выполнения чистой бизнес-логики в изолированном виртуальном потоке.
        // Доменный контекст уже распакован и доступен внутри иммутабельного рекорда.
        accountService.handleAccountCreation(event.domainContext());
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
    @Transactional // Гарантирует атомарную фиксацию в СУБД внутри общей транзакции
    public void onMessage(ConsumerRecord<String, String> record) {
        
        // Передаем сырые данные единственному фасаду платформы. 
        // Вся магия десериализации, валидации и дедупликации скрыта внутри.
        boolean accepted = inboxCoordinator.coordinate(
                record.key(), 
                "ACCOUNT_CREATED", 
                record.value(), 
                Map.of("partition", String.valueOf(record.partition()))
        );
        
        if (!accepted) {
            // Метод вернет false как при дубликате, так и при Poison Pill.
            // Мы просто мягко выходим из метода, позволяя Кафке закоммитить оффсет.
            return;
        }
    }
}

```

