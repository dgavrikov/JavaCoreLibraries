# Библиотека для взаимодействия с Apache ActiveMQ Artemis

Предоставляет легковесную, высокопроизводительную интеграцию с Apache ActiveMQ Artemis на базе спецификации **Jakarta 
Messaging (JMS 3.0)**. Разработана специально для высоконагруженных транзакционных систем. Поддерживает тотальный отказ 
от JNDI-оверхеда, гибкую конфигурацию очередей и нативную поддержку **виртуальных потоков Java 21 (Project Loom).**

## Как подключить библиотеку

### Добавить зависимость в pom.xml

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-artemis</artifactId>
</dependency>
```

## Конфигурация YAML

Ниже представлен пример структуры конфигурации с использованием кастомного префикса custom, оптимальными дефолтами для 
high-load и поддержкой переменной окружения. Полную версию конфигурационного файла можно найти в исходном репозитории.

```yaml
custom:
  artemis:
    broker-url: ${CUSTOM_ARTEMIS_URL:tcp://localhost:61616}
    pool:
      max-connections: 10
      global-consumer-window-size: 0
    consumers:
      orderProcessingGroup:
        destination: order.processing.queue
    producer:
      destinations:
        billing-out: billing.processing.queue
```

## Конфигурация классов Java

Для регистрации бинов используется `ArtemisConfigBuilder` и рекорд `ArtemisProperties`. Полный листинг конфигурации 
доступен в документации.

```java
@ConditionalOnProperty(name = "custom.artemis.broker-url")
@Configuration
@EnableJms
public class ArtemisConfigCustom {
    @Bean
    @ConfigurationProperties(prefix = "custom.artemis")
    public ArtemisProperties customArtemisProperties() {
        return new ArtemisProperties();
    }
    // Регистрация ConnectionFactory и ListenerContainerFactory через ArtemisConfigBuilder
}

```

## Пример реализации Producer

Для отправки сообщений используются базовый класс `AbstractArtemisProducerClient<V>`, обеспечивающий сериализацию и 
семантику At-Least-Once. Полные примеры реализации с поддержкой маскирования и логирования.

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dgavrikov.core.artemis.AbstractArtemisProducerClient;
import io.github.dgavrikov.core.artemis.exception.ProducerArtemisException;
import io.github.dgavrikov.core.artemis.properties.ArtemisProperties;
import io.github.dgavrikov.core.service.logging.MaskingLog;
import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Map;

// Декларативный прикладной контракт (KISS/SRP)
public interface CustomArtemisProduceClient {
    void orderEventSend(OrderEventDto request, Map<String, String> headers);
    void billingEventSend(BillingEventDto request, Map<String, String> headers);
}

@Component
@Slf4j
public class CustomArtemisProduceClientImpl extends AbstractArtemisProducerClient<String> implements CustomArtemisProduceClient {
    private final ArtemisProperties artemisProperties;

    public CustomArtemisProduceClientImpl(
            ConnectionFactory connectionFactory,
            ObjectMapper objectMapper,
            MaskingLog maskingLog,
            ArtemisProperties artemisProperties
    ) {
        super(connectionFactory, objectMapper, maskingLog, log);
        this.artemisProperties = artemisProperties;
    }

    @Override
    protected String getSystemName() {
        return "Customer processing system";
    }

    @Override
    public void orderEventSend(OrderEventDto request, Map<String, String> headers) {
        try {
            // Маскируем и логируем исходный объект на прикладном уровне
            maskingLog.debug(log, request, "Message body (Order): ");
            
            // Сериализуем DTO в JSON-строку
            String jsonPayload = objectMapper.writeValueAsString(request);
            String destination = artemisProperties.producer().destinations().get("order-events");
            
            // Вызов синхронной гарантированной отправки базовой либы
            this.send(destination, jsonPayload, headers);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order event request to JSON", e);
            throw new ProducerArtemisException("Failed to serialize request object to JSON.", e);
        }
    }

    @Override
    public void billingEventSend(BillingEventDto request, Map<String, String> headers) {
        try {
            maskingLog.debug(log, request, "Message body (Billing): ");
            String jsonPayload = objectMapper.writeValueAsString(request);
            String destination = artemisProperties.producer().destinations().get("billing-events");
            
            // Вызов асинхронного Fire-and-Forget режима (развернется в виртуальном потоке)
            this.sendAsync(destination, jsonPayload, headers);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize billing event request to JSON", e);
            throw new ProducerArtemisException("Failed to serialize request object to JSON.", e);
        }
    }
}

```

## Пример реализации Consumer

Для получения сообщений используются базовый класс `AbstractArtemisConsumer<V>`, обеспечивающий сериализацию и семантику 
At-Least-Once. Полные примеры реализации с поддержкой маскирования и логирования.

```java
import io.github.dgavrikov.core.artemis.AbstractArtemisConsumer;
import io.github.dgavrikov.core.service.logging.MaskingLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
@Slf4j
public class CustomArtemisConsumer extends AbstractArtemisConsumer<OrderEventDto> {

    public CustomArtemisConsumer(
            ObjectMapper objectMapper,
            MaskingLog maskingLog
    ) {
        // Жестко привязываем тип payload на этапе инициализации
        super(objectMapper, maskingLog, log, OrderEventDto.class);
    }

    @Override
    protected String getSystemName() {
        return "Order state processing worker";
    }

    // Связываем спринговый контейнер, настроенный через наш ArtemisConfigBuilder, с базовым обработчиком
    @JmsListener(
            destination = "#{@customArtemisProperties.consumers.get('orderProcessingGroup').destination()}",
            containerFactory = "customArtemisListenerContainerFactory",
            concurrency = "#{@customArtemisProperties.consumers.get('orderProcessingGroup').concurrency()}"
    )
    @Override
    public void onMessage(jakarta.jms.Message message) {
        // Делегируем выполнение базовому классу из либы. 
        // Он выполнит логирование, десериализацию в OrderEventDto, вызовет метод process() и сделает честный message.acknowledge()
        super.onMessage(message);
    }

    @Override
    protected void process(OrderEventDto payload, Map<String, String> headers) {
        // Сюда прилетает чистый, типизированный и десериализованный объект.
        // Выполняем бизнес-логику транзакционного ядра (например, RTC-цикл стейт-машины).
        log.info("Processing order event inside domain for ID: {}", payload.getOrderId());
    }
}

```
