# Библиотека легковесной декларативной машины состояний (Core State Machine)

Предоставляет надежную, высокопроизводительную и полностью Stateless-архитектуру конечного автомата, разработанную 
для enterprise-приложений. Поддерживает декларативное описание графов переходов на базе Java Records, встроенное 
сквозное Run-To-Completion (RTC) выполнение, интерцепторы нотификаций и жесткий контроль типов (Type Safety) на этапе 
компиляции без оверхеда и аллокаций памяти в High Load.

## Добавить зависимость в pom.xml

```yaml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-state-machine</artifactId>
</dependency>
```

## Конфигурация YAML

Параметры планировщика поддерживают гибкую настройку интервалов проверки и пакетной вычитки «уснувших» или готовых к 
ретраю заявок.

```yaml
# Прикладные настройки планировщика воркфлоу
custom:
  scheduler:
    account-open:
      enabled: ${CUSTOM_SCHEDULER_ACCOUNT_OPEN_ENABLED:true}
      order-basket-count: ${CUSTOM_SCHEDULER_ACCOUNT_OPEN_BASKET_COUNT:100}
      execute-interval-cron: ${CUSTOM_SCHEDULER_ACCOUNT_OPEN_CRON:0/5 * * * * ?}
  
  # Настройки интервалов и лимитов для ретрай-пайплайнов
  workflow-properties:
    open-account-retry:
      IEVT_ORDER_CREATE_SEND:
        interval: ${CUSTOM_WORKFLOW_RETRY_INTERVAL_SECONDS:1800} # 30 минут
        count: ${CUSTOM_WORKFLOW_RETRY_MAX_COUNT:5}

```

## Конфигурация классов **Java**

Для регистрации инфраструктурного синглтон-движка и декларативных карт процессов создайте конфигурационный класс в слое 
инфраструктуры.

```java
package com.github.dgavrikov.examples.infrastructure.config;

@Configuration
public class AccountStateMachineConfig {

    @Bean(name = "openAccountWorkflowRegistry")
    public SmWorkflowRegistry<Long, AccountOrder.Status, AccountOrder> openAccountWorkflowRegistry(
            EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> accountRequestKafkaEventHandler,
            EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> accountProcessEventHandler
    ) {
        return AccountWorkflow.createOpenAccountRegistry(
                accountRequestKafkaEventHandler, 
                accountProcessEventHandler
        );
    }

    @Bean
    public StateMachineEngine<Long, AccountOrder.Status> accountStateMachineEngine(
            SmNotifyService<Long, AccountOrder.Status, AccountOrder> accountNotifyService,
            SpanMicrometer spanMicrometer
    ) {
        return new StateMachineEngine<>(accountNotifyService, spanMicrometer);
    }
}

```

## Пример реализации Доменной Модели

Доменная модель является чистым дата-объектом, реализует интерфейс `ContextData<ID, S>` ядра и полностью изолирована 
от Hibernate или костылей угадывания типов (`instanceof`) благодаря Java Record Pattern Matching.

```java
package com.github.dgavrikov.examples.domain.model;

@Builder
@Getter
public class AccountOrder implements ContextData<Long, AccountOrder.Status> {
    private final Long id;
    private final String traceInfo;
    private final String account;
    private OffsetDateTime openDate;

    @Setter private Status state;
    private Integer retryCount;
    private OffsetDateTime lastChangeTime;

    public enum Status implements State {
        NEW, // start
        REQUEST, // send to kafka another system
        IN_PROCESS, // receive accept
        ERROR, // error from receive denied or in_process
        DONE // account open or close complete
    }

}

```

## Пример реализации **EventHandler** (Обработчика шага)

Бизнес-хэндлеры изолированы в прикладном слое (`Application`), package-private защищены и используют сильную типизацию 
рантайм-контекста без принудительных кастов.

```java
package com.github.dgavrikov.examples.domain.order.handler;

@Component
@RequiredArgsConstructor
@Slf4j
class AccountRequestKafkaEventHandler implements EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> {

    private final AnotherSystemKafkaProducer kafkaProducer; 

    @Override
    public void handle(SmRuntimeContext<Long, AccountOrder.Status, AccountOrder> context) {
        // Прямой типизированный доступ без кастов (KISS)
        AccountOrder order = context.getData(); 
        log.info("{}: Starting Kafka push for request #{}", getName(), order.getId());

        try {
            kafkaProducer.sendRequest(order.getId(), order.getAccount(), order.getTraceInfo());
            context.send(); // Асинхронный разрыв — ядро зафиксирует стейт и очистит память
        } catch (Exception ex) {
            context.fail("Kafka delivery error: " + ex.getMessage());
        }
    }

    @Override
    public String getName() {
        return "Отправка запроса на регистрацию счета в стороннюю систему";
    }
}

```

## Пример реализации Карты переходов **(Workflow)**

Воркфлоу объявляется декларативно в виде плоского и наглядного графа, полностью заменяя собой сотни старых разрозненных 
классов-состояний GoF State.

```java
package com.github.dgavrikov.examples.domain.order.workflow;

final class AccountWorkflow {

    static SmWorkflowRegistry<Long, AccountOrder.Status, AccountOrder> createOpenAccountRegistry(
            EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> accountRequestKafkaEventHandler,
            EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> accountProcessEventHandler
    ) {
        return new SmWorkflowRegistry<>(
                Workflow.OPEN_ACCOUNT.name(), // Задаем имя воркфлоу для трейсинга
                Map.of(
                        // Шаг 1: NEW -> Отправка в кафку
                        AccountOrder.Status.NEW, new StepDefinition<>(
                                AccountOrder.Status.NEW,
                                accountRequestKafkaEventHandler,
                                Map.of(
                                        ExecutionSignal.SEND, AccountOrder.Status.REQUEST, // Успешно отправили — ждем в статусе REQUEST
                                        ExecutionSignal.FAIL, AccountOrder.Status.ERROR    // Упали при отправке — статус ERROR
                                ),
                                true,  // notifyBefore: Уведомить АБС перед стартом
                                true  // notifyAfter: Уведомить об изменении заявки на открытие
                        ),

                        // Шаг 2: IN_PROCESS -> Финализация открытия счета
                        AccountOrder.Status.IN_PROCESS, new StepDefinition<>(
                                AccountOrder.Status.IN_PROCESS,
                                accountProcessEventHandler,
                                Map.of(
                                        ExecutionSignal.SUCCESS, AccountOrder.Status.DONE,
                                        ExecutionSignal.DEFER,   AccountOrder.Status.IN_PROCESS, // Перегрузка — спим в текущем статусе
                                        ExecutionSignal.FAIL,    AccountOrder.Status.ERROR
                                ),
                                false,
                                true // notifyAfter: Отправить уведомление клиенту об успешном открытии!
                        )
                )
        );
    }
}

```

## Пример реализации Инфраструктурного адаптера БД

Адаптер СУБД реализует SmStorageAdapter, скрывая грязные технические детали апдейтов таблиц и наполняя доменную модель 
по ссылке без создания промежуточного мусора.

```java
package com.github.dgavrikov.examples.infrastructure.db.adapter;

import sm.io.github.dgavrikov.core.SmStorageAdapter;
import com.github.dgavrikov.examples.domain.domain.model.AccountOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Component
@RequiredArgsConstructor
public class AccountOrderStorageAdapter implements SmStorageAdapter<Long, AccountOrder.Status, AccountOrder> {

    // Твой реальный репозиторий работы с базой данных
    private final AccountOrderRepository repository;

    @Override
    public void changeState(
            AccountOrder accountOrder, AccountOrder.Status state, String reason, boolean clearDeferTime) {

        // 1. Делаем физический UPDATE в СУБД
        var entity = repository.updateStatusInDb(accountOrder.getId(), state.name(), reason, clearDeferTime ? null : "KEEP_DEFER");

        // Наполняем модель по ссылке без лишних прослоек и рекордов изменений!
        accountOrder.setState(entity.getState());
        accountOrder.setLastChangeTime(entity.getLastChangeTime());
    }

    @Override
    public void changeDeferTime(AccountOrder accountOrder, OffsetDateTime nextStart) {
        var entity = repository.updateDeferTimeInDb(accountOrder.getId(), nextStart);

        // Наполняем модель по ссылке. Статус ОСТАЕТСЯ ТЕМ ЖЕ, на котором споткнулись!
        accountOrder.setRetryCount(entity.getRetruCount());
        accountOrder.setLastChangeTime(entity.getLastChangeTime());
    }

    @Override
    public void incrementRetryCount(AccountOrder accountOrder) {
        int updatedCount = repository.incrementRetryCountInDb(accountOrder.getId());

        accountOrder.setRetryCount(updatedCount);
    }
}
```

## Интеграция с планировщиком **(Scheduler)**

Вычитка пачки проснувшихся или готовых к обработке ID выполняется СУБД-индексом с учетом неблокирующего **deferred_until**, 
исключая холостые циклы и паразитную нагрузку на CPU.

```java
package com.github.dgavrikov.examples.infrastructure.schedule;

@Service
@RequiredArgsConstructor
public class AccountOrderScheduler {

    private final StateMachineEngine<Long, AccountOrder.Status> smEngine;
    private final SmWorkflowRegistry<Long, AccountOrder.Status, AccountOrder> openAccountRegistry;
    private final SmStorageAdapter<Long, AccountOrder.Status, AccountOrder> storageAdapter;
    private final AccountOrderRepository repository;

    @Scheduled(cron = "${custom.scheduler.account-open.execute-interval-cron}")
    public void executeOpenAccountWf() {
        // Высокопроизводительный SQL-запрос: (deferred_until IS NULL OR deferred_until <= NOW)
        var orderIds = repository.findIdsForProcessing(
                openAccountRegistry.getSupportStates(), 
                OffsetDateTime.now(), 
                100
        );

        for (Long id : orderIds) {
            AccountOrder accountOrder = repository.findById(id);
            
            // Запуск Run-To-Completion цикла
            smEngine.execute(accountOrder, openAccountRegistry, storageAdapter);
        }
    }
}

```