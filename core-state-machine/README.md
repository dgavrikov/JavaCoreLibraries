# Lightweight Declarative Finite State Machine Library (Core State Machine)

Provides a robust, high-performance, and fully stateless finite state machine architecture designed for enterprise-grade applications. It supports declarative transition graph definitions leveraging Java Records, native run-to-completion (RTC) cycle execution, notification interceptors, and strict compile-time type safety with zero runtime overhead and minimal heap allocation under extreme High Load conditions.

## Add Dependency to pom.xml

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-state-machine</artifactId>
</dependency>
```

## YAML Configuration Reference

The workflow scheduler settings support flexible configuration of polling intervals and batch retrieval limits for "sleeping" or ready-for-retry application requests.

```yaml
# Application-level workflow scheduler configuration
custom:
  scheduler:
    account-open:
      enabled: \${CUSTOM_SCHEDULER_ACCOUNT_OPEN_ENABLED:true}
      order-basket-count: \${CUSTOM_SCHEDULER_ACCOUNT_OPEN_BASKET_COUNT:100}
      execute-interval-cron: \${CUSTOM_SCHEDULER_ACCOUNT_OPEN_CRON:0/5 * * * * ?}
  
  # Retry pipeline intervals and limits configuration
  workflow-properties:
    open-account-retry:
      IEVT_ORDER_CREATE_SEND:
        interval: \${CUSTOM_WORKFLOW_RETRY_INTERVAL_SECONDS:1800} # 30 minutes
        count: \${CUSTOM_WORKFLOW_RETRY_MAX_COUNT:5}
```

## Java Configuration

To register the infrastructure singleton engine and declarative process maps, define a configuration class within the infrastructure layer.

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

## Domain Model Implementation Example

The domain model acts as a pure data object implementing the core `ContextData<ID, S>` interface. It remains completely decoupled from Hibernate or boilerplate type-casting checks (`instanceof`) by leveraging Java Record Pattern Matching.

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

## EventHandler Implementation Example

Business handlers are isolated within the application layer (`Application`), package-private protected, and leverage strong runtime context typing to eliminate manual casting overhead.

```java
package com.github.dgavrikov.examples.domain.order.handler;

@Component
@RequiredArgsConstructor
@Slf4j
class AccountRequestKafkaEventHandler implements EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> {

    private final AnotherSystemKafkaProducer kafkaProducer; 

    @Override
    public void handle(SmRuntimeContext<Long, AccountOrder.Status, AccountOrder> context) {
        // Strongly-typed access without casting (KISS)
        AccountOrder order = context.getData(); 
        log.info("{}: Starting Kafka push for request #{}", getName(), order.getId());

        try {
            kafkaProducer.sendRequest(order.getId(), order.getAccount(), order.getTraceInfo());
            context.send(); // Asynchronous continuation break — the core engine flushes state and clears memory
        } catch (Exception ex) {
            context.fail("Kafka delivery error: " + ex.getMessage());
        }
    }

    @Override
    public String getName() {
        return "Sending account registration request to external system";
    }
}
```

## Transition Map (Workflow) Implementation Example

The workflow is declared characteristically as a flat, self-documenting transition graph, completely eliminating the need for hundreds of legacy, scattered GoF State classes.

```java
package com.github.dgavrikov.examples.domain.order.workflow;

final class AccountWorkflow {

    static SmWorkflowRegistry<Long, AccountOrder.Status, AccountOrder> createOpenAccountRegistry(
            EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> accountRequestKafkaEventHandler,
            EventHandler<SmRuntimeContext<Long, AccountOrder.Status, AccountOrder>> accountProcessEventHandler
    ) {
        return new SmWorkflowRegistry<>(
                Workflow.OPEN_ACCOUNT.name(), // Define workflow name for distributed tracing
                Map.of(
                        // Step 1: NEW -> Send to Kafka
                        AccountOrder.Status.NEW, new StepDefinition<>(
                                AccountOrder.Status.NEW,
                                accountRequestKafkaEventHandler,
                                Map.of(
                                        ExecutionSignal.SEND, AccountOrder.Status.REQUEST, // Successfully sent — waiting in REQUEST status
                                        ExecutionSignal.FAIL, AccountOrder.Status.ERROR    // Delivery failed — transition to ERROR status
                                ),
                                true,  // notifyBefore: Notify Core Banking System prior to step execution
                                true  // notifyAfter: Notify regarding open request modification
                        ),

                        // Step 2: IN_PROCESS -> Finalize account opening
                        AccountOrder.Status.IN_PROCESS, new StepDefinition<>(
                                AccountOrder.Status.IN_PROCESS,
                                accountProcessEventHandler,
                                Map.of(
                                        ExecutionSignal.SUCCESS, AccountOrder.Status.DONE,
                                        ExecutionSignal.DEFER,   AccountOrder.Status.IN_PROCESS, // Rate-limit / Backpressure — sleep in current state
                                        ExecutionSignal.FAIL,    AccountOrder.Status.ERROR
                                ),
                                false,
                                true // notifyAfter: Send notification to the client about successful account activation!
                        )
                )
        );
    }
}
```

## Database Infrastructure Adapter Example

The database adapter implements `SmStorageAdapter`, encapsulating the low-level technical details of table updates and populating the domain model by reference without creating intermediate garbage.

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

    // Your actual database repository
    private final AccountOrderRepository repository;

    @Override
    public void changeState(
            AccountOrder accountOrder, AccountOrder.Status state, String reason, boolean clearDeferTime) {

        // 1. Perform a physical UPDATE in the database
        var entity = repository.updateStatusInDb(accountOrder.getId(), state.name(), reason, clearDeferTime ? null : "KEEP_DEFER");

        // Populate the model by reference without extra layers or change records!
        accountOrder.setState(entity.getState());
        accountOrder.setLastChangeTime(entity.getLastChangeTime());
    }

    @Override
    public void changeDeferTime(AccountOrder accountOrder, OffsetDateTime nextStart) {
        var entity = repository.updateDeferTimeInDb(accountOrder.getId(), nextStart);

        // Populate the model by reference. The status REMAINS THE SAME as the one we stumbled upon!
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

## Scheduler Integration

Fetching a batch of expired or ready-for-processing IDs is executed via a database index considering the non-blocking **deferred_until** field, completely eliminating idle polling cycles and parasitic CPU overhead.

```java
package com.github.dgavrikov.examples.infrastructure.schedule;

@Service
@RequiredArgsConstructor
public class AccountOrderScheduler {

    private final StateMachineEngine<Long, AccountOrder.Status> smEngine;
    private final SmWorkflowRegistry<Long, AccountOrder.Status, AccountOrder> openAccountRegistry;
    private final SmStorageAdapter<Long, AccountOrder.Status, AccountOrder> storageAdapter;
    private final AccountOrderRepository repository;

    @Scheduled(cron = "\${custom.scheduler.account-open.execute-interval-cron}")
    public void executeOpenAccountWf() {
        // High-performance SQL query: (deferred_until IS NULL OR deferred_until <= NOW)
        var orderIds = repository.findIdsForProcessing(
                openAccountRegistry.getSupportStates(), 
                OffsetDateTime.now(), 
                100
        );

        for (Long id : orderIds) {
            AccountOrder accountOrder = repository.findById(id);
            
            // Triggering the Run-To-Completion execution cycle
            smEngine.execute(accountOrder, openAccountRegistry, storageAdapter);
        }
    }
}
```
