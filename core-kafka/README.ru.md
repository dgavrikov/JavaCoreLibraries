# Библиотека для взаимодействия с Apache Kafka

Предоставляет надежную интеграцию с Apache Kafka, разработанную для enterprise-приложений. Поддерживает динамическую настройку групп потребителей, безопасность через SSL/TLS, гибкую конфигурацию продюсеров и нативную поддержку **виртуальных потоков Java 21**.

## Как подключить библиотеку

### Добавить зависимость в pom.xml

```xml
<dependency>
    <groupId>com.github.dgavrikov.core</groupId>
    <artifactId>core-kafka</artifactId>
</dependency>
```

## Конфигурация YAML

Ниже представлен пример структуры конфигурации с использованием кастомного корневого префикса (`custom`). Параметры поддерживают переопределение через переменные окружения и содержат оптимальные дефолтные значения.

```yaml
# Корневой префикс конфигурации
custom:
  system-code: ${CUSTOM_SYSTEM_CODE}
  kafka:
    bootstrap-servers: ${CUSTOM_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

    # Настройки безопасности SSL/TLS транспортного уровня
    ssl:
      enabled: ${CUSTOM_KAFKA_SSL_ENABLED:true}
      key-store:
        type: ${CUSTOM_KAFKA_SSL_KEY_STORE_TYPE:PKCS12}
        password: ${CUSTOM_KAFKA_SSL_KEY_STORE_PASSWORD:keystore_password}
        location: ${CUSTOM_KAFKA_SSL_KEY_STORE_LOCATION:file:/path/to/keystore.p12}
      trust-store:
        type: ${CUSTOM_KAFKA_SSL_TRUST_STORE_TYPE:PKCS12}
        password: ${CUSTOM_KAFKA_SSL_TRUST_STORE_PASSWORD:truststore_password}
        location: ${CUSTOM_KAFKA_SSL_TRUST_STORE_LOCATION:file:/path/to/truststore.p12}
      key:
        password: ${CUSTOM_KAFKA_SSL_KEY_PASSWORD:key_password}

    # Настройки групп потребителей (Map<String, Consumer> consumers)
    consumers:
      paymentGroup: # Ключ-идентификатор в Map
        enabled: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_ENABLED:true}
        enable-auto-commit: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_ENABLE_AUTO_COMMIT:false}
        topics: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_TOPICS:payment-events-topic}
        group-id: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_GROUP_ID:\){spring.application.name}__payment-service-group}
        auto-offset-reset: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_AUTO_OFFSET_RESET:earliest}
        listener-concurrency: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_LISTENER_CONCURRENCY:1}
        max-poll-interval-ms: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_MAX_POLL_INTERVAL_MS:300000}
        max-poll-records: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_MAX_POLL_RECORDS:100}
        session-timeout-ms: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_SESSION_TIMEOUT_MS:45000}
        heartbeat-interval-ms: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_HEARTBEAT_INTERVAL_MS:10000}
        retry-attempts: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_RETRY_ATTEMPTS:10}
        retry-backoff-ms: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_RETRY_BACKOFF_MS:2000}
        enable-virtual-thread: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_ENABLE_VIRTUAL_THREAD:true}
        properties: # Map<String, String> низкоуровневых свойств потребителя
          session.timeout.ms: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_PROPERTIES_SESSION_TIMEOUT_MS:45000}
          max.partition.fetch.bytes: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_PROPERTIES_MAX_PARTITION_FETCH_BYTES:1048576}

    # Настройки продюсера (публикатора сообщений)
    producer:
      acks: ${CUSTOM_KAFKA_PRODUCER_ACKS:all}
      retries: ${CUSTOM_KAFKA_PRODUCER_RETRIES:3}
      topics: # Map<String, String> маппинга логических имен событий на физические топики
        order-events: ${CUSTOM_KAFKA_PRODUCER_TOPICS_ORDER_EVENTS:orders-v1-topic}
        billing-events: ${CUSTOM_KAFKA_PRODUCER_TOPICS_BILLING_EVENTS:billing-v2-topic}
      properties: # Map<String, String> низкоуровневой оптимизации работы продюсера
        compression.type: ${CUSTOM_KAFKA_PRODUCER_PROPERTIES_COMPRESSION_TYPE:snappy}
        linger.ms: ${CUSTOM_KAFKA_PRODUCER_PROPERTIES_LINGER_MS:20}
```

## Конфигурация классов Java

Для регистрации инфраструктурных бинов создайте конфигурационный класс, использующий `KafkaConfigBuilder` и `KafkaProperties` из библиотеки.

### Вариант 1: Конфигурация для работы со строками (JSON) — 95% кейсов

```java
import com.github.dgavrikov.core.kafka.config.KafkaConfigBuilder;
import com.github.dgavrikov.core.kafka.properties.KafkaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;

@ConditionalOnProperty(name = "custom.kafka.bootstrap-servers")
@Configuration
@EnableKafka
public class KafkaConfigCustom {
    public static final String CUSTOM_PAYMENT_GROUP = "paymentGroup";

    @Value("${custom.system-code}")
    private String systemCode;

    @Bean
    @ConfigurationProperties(prefix = "custom.kafka")
    public KafkaProperties customKafkaProperties() {
        return new KafkaProperties();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> customListenerContainerFactoryPaymentGroup(
            KafkaProperties customKafkaProperties,
            MeterRegistry meterRegistry
    ) {
        return KafkaConfigBuilder.buildKafkaListenerContainerFactory(
                customKafkaProperties,
                meterRegistry,
                CUSTOM_PAYMENT_GROUP,
                systemCode,
                StringDeserializer.class,      // Класс десериализатора ключа
                StringDeserializer.class);     // Класс десериализатора значения
    }

    @Bean
    public KafkaTemplate<String, String> customKafkaTemplate(
            KafkaProperties customKafkaProperties,
            MeterRegistry meterRegistry
    ) {
        return KafkaConfigBuilder.buildKafkaTemplate(
                customKafkaProperties,
                meterRegistry,
                StringSerializer.class,        // Класс сериализатора ключа
                StringSerializer.class);       // Класс сериализатора значения
    }
}
```

### Вариант 2: Конфигурация для работы с сырыми байтами (byte[])

Если вашему сервису необходимо отправлять или принимать бинарные данные (файлы, Protobuf, Avro), используйте `ByteArraySerializer` и `ByteArrayDeserializer`:

```java
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

@ConditionalOnProperty(name = "custom.kafka.bootstrap-servers")
@Configuration
@EnableKafka
public class KafkaConfigBytesCustom {
    public static final String BYTES_GROUP = "bytesSectionName";

    @Value("${custom.system-code}")
    private String systemCode;

    @Bean
    @ConfigurationProperties(prefix = "custom.kafka")
    public KafkaProperties customKafkaProperties() {
        return new KafkaProperties();
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> bytesListenerContainerFactory(
            KafkaProperties customKafkaProperties,
            MeterRegistry meterRegistry
    ) {
        return KafkaConfigBuilder.buildKafkaListenerContainerFactory(
                customKafkaProperties,
                meterRegistry,
                BYTES_GROUP,
                systemCode,
                StringDeserializer.class,       // Класс десериализатора ключа
                ByteArrayDeserializer.class);   // Класс десериализатора значения (byte[])
    }

    @Bean
    public KafkaTemplate<String, byte[]> bytesKafkaTemplate(
            KafkaProperties customKafkaProperties,
            MeterRegistry meterRegistry
    ) {
        return KafkaConfigBuilder.buildKafkaTemplate(
                customKafkaProperties,
                meterRegistry,
                StringSerializer.class,         // Класс сериализатора ключа
                ByteArraySerializer.class);     // Класс сериализатора значения (byte[])
    }
}
```

## Пример реализации Producer (Продюсера)

Для отправки сообщений через компоненты библиотеки рекомендуется наследоваться от базового абстрактного класса `AbstractKafkaProducerClient<K, V>`. Он инкапсулирует логику синхронной/асинхронной отправки, веерную маршрутизацию на несколько топиков через `;`, потокобезопасность, маскирование данных и базовое взаимодействие с `KafkaTemplate`.

### Вариант 1: Стандартный продюсер для текстовых/JSON сообщений (95% кейсов)

Для классического стриминга JSON-событий объявите компонент с дженерик-типами `<String, String>`. Сериализация бизнес-моделей (DTO в JSON) выполняется на прикладном уровне перед вызовом методов библиотеки, что сохраняет чистоту архитектуры:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dgavrikov.core.kafka.AbstractKafkaProducerClient;
import com.github.dgavrikov.core.kafka.exception.ProducerKafkaException;
import com.github.dgavrikov.core.kafka.properties.KafkaProperties;
import com.github.dgavrikov.core.service.logging.MaskingLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

public interface CustomKafkaProduceClient {
    void orderEventSend(OrderEventDto request, Map<String, String> headers, String messageKey);

    void billingEventSend(BillingEventDto request, Map<String, String> headers, String messageKey);
}

@Component
@Slf4j
public class CustomKafkaProduceClientImpl extends AbstractKafkaProducerClient<String, String>
        implements CustomKafkaProduceClient {

    private final KafkaProperties customKafkaProperties;
    private final ObjectMapper objectMapper;
    private final MaskingLog maskingLog;

    public CustomKafkaProduceClientImpl(
            KafkaTemplate<String, String> customKafkaTemplate,
            MaskingLog maskingLog,
            KafkaProperties customKafkaProperties,
            ObjectMapper objectMapper
    ) {
        super(customKafkaTemplate, maskingLog, log);
        this.customKafkaProperties = customKafkaProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected String getSystemName() {
        return "Customer payment system";
    }

    @Override
    public void orderEventSend(OrderEventDto request, Map<String, String> headers, String messageKey) {
        try {
            // Сериализация DTO в строку на уровне приложения (KISS/SRP)
            String jsonPayload = objectMapper.writeValueAsString(request);
            
            // Логируем исходный объект
            maskingLog.debug(log, request, "Message body: ");

            // Компилятор строго гарантирует, что в метод sendMessage передается только String
            this.sendMessage(getOrderEventsTopic(), messageKey, jsonPayload, headers);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize order event request to JSON", e);
            throw new ProducerKafkaException("Failed to serialize request object to JSON.", e);
        }
    }

    @Override
    public void billingEventSend(BillingEventDto request, Map<String, String> headers, String messageKey) {
        try {
            maskingLog.debug(log, request, "Message body: ");
            String jsonPayload = objectMapper.writeValueAsString(request);
            this.sendMessage(getBillingEventsTopic(), messageKey, jsonPayload, headers);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize billing event request to JSON", e);
            throw new ProducerKafkaException("Failed to serialize request object to JSON.", e);
        }
    }

    private String getOrderEventsTopic() {
        return customKafkaProperties.getProducer().getTopics().get("order-events");
    }

    private String getBillingEventsTopic() {
        return customKafkaProperties.getProducer().getTopics().get("billing-events");
    }
}
```

### Вариант 2: Бин-продюсер для работы с сырыми байтами (byte[]) — оставшиеся 5% кейсов

Для сервисов, отправляющих файлы, изображения, Protobuf или Avro сегменты, объявите компонент с типами `<String, byte[]>`. Это полностью минует текстовые сериализаторы и обеспечивает железную безопасность типов на этапе компиляции:

```java
import com.github.dgavrikov.core.kafka.AbstractKafkaProducerClient;
import com.github.dgavrikov.core.kafka.AbstractKafkaProducerClient;
import com.github.dgavrikov.core.kafka.properties.KafkaProperties;
import com.github.dgavrikov.core.service.logging.MaskingLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class BinaryReportProducerClient extends AbstractKafkaProducerClient<String, byte[]> {

    private final KafkaProperties customKafkaProperties;

    public BinaryReportProducerClient(
            KafkaTemplate<String, byte[]> bytesKafkaTemplate, // Внедряем шаблон для работы с byte[]
            MaskingLog maskingLog,
            KafkaProperties customKafkaProperties
    ) {
        super(bytesKafkaTemplate, maskingLog, log);
        this.customKafkaProperties = customKafkaProperties;
    }

    @Override
    protected String getSystemName() {
        return "Reporting Binary Export System";
    }

    public void sendPdfReport(byte[] pdfBytes, String fileId, Map<String, String> headers) {
        String targetTopic = customKafkaProperties.getProducer().getTopics().get("report-binary-events");

        log.debug("Message body (bytes size): {}", bytes.length);
        
        // Контроль на этапе компиляции: разрешен только byte[]. 
        // Попытка передать DTO или обычную строку вызовет ошибку компиляции.
        this.sendMessage(targetTopic, fileId, pdfBytes, headers);
    }
}
```

## Примеры реализации Subscriber (Подписчика)

Библиотека поддерживает две модели потребления сообщений: обработку записей по отдельности (одиночный режим) или пачками (пакетный режим).

### Подписчик для одиночной обработки сообщений

Обрабатывает ровно одно сообщение из Kafka за один вызов poll.

```java
import com.github.dgavrikov.core.kafka.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class CustomKafkaConsumer implements KafkaConsumer<String> {

    @Value("${custom.kafka.consumers.paymentGroup.topics}")
    private String topicNames;

    @KafkaListener(
            id = "#{@customKafkaProperties.consumers.paymentGroup.topics}",
            topics = "#{@customKafkaProperties.consumers.paymentGroup.topics}",
            groupId = "#{@customKafkaProperties.consumers.paymentGroup.groupId}",
            containerFactory = "customListenerContainerFactoryPaymentGroup",
            autoStartup = "#{@customKafkaProperties.consumers.paymentGroup.enabled}"
    )
    @Override
    public void consumerRecord(ConsumerRecord<?, String> record, Acknowledgment ack) {
        // Выполнение бизнес-логики
        ack.acknowledge();
    }
}
```

### Подписчик для пакетной обработки сообщений (Batch)

Обрабатывает пачку сообщений, полученных за один вызов poll, что позволяет достичь максимальной пропускной способности.

```java
import com.github.dgavrikov.core.kafka.KafkaConsumerBatch;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class CustomKafkaConsumerBatch implements KafkaConsumerBatch<String> {

    @Value("${custom.kafka.consumers.paymentGroup.topics}")
    private String topicNames;

    @KafkaListener(
            id = "#{@customKafkaProperties.consumers.paymentGroup.topics}",
            topics = "#{@customKafkaProperties.consumers.paymentGroup.topics}",
            groupId = "#{@customKafkaProperties.consumers.paymentGroup.groupId}",
            containerFactory = "customListenerContainerFactoryPaymentGroup",
            autoStartup = "#{@customKafkaProperties.consumers.paymentGroup.enabled}",
            batch = "true"
    )
    @Override
    public void consumerRecords(ConsumerRecords<?, String> records, Acknowledgment ack) {
        // Выполнение пакетной бизнес-логики
        ack.acknowledge();
    }
}
```
