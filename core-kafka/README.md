# Apache Kafka Integration Library

Provides enterprise-ready, robust integration with Apache Kafka, featuring fully customizable consumer groups, SSL/TLS security, publisher configurations, and native support for **Java 21 Virtual Threads**.

## Installation

### Add Dependency to pom.xml

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-kafka</artifactId>
</dependency>
```

## YAML Configuration Reference

Below is a production-ready configuration structure using a customizable root prefix (`custom`). It features environment variable overrides and sensible fallback defaults.

```yaml
# Root configuration prefix
custom:
  system-code: ${CUSTOM_SYSTEM_CODE}
  kafka:
    bootstrap-servers: ${CUSTOM_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}

    # SSL/TLS Transport Security Settings
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

    # Consumer Groups Infrastructure Configuration (Map<String, Consumer> consumers)
    consumers:
      paymentGroup: # Unique Map Identifier key
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
        # Leverages Java 21 lightweight virtual threads for maximum concurrency performance
        enable-virtual-thread: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_ENABLE_VIRTUAL_THREAD:true}
        properties: # Map<String, String> for granular low-level consumer properties
          session.timeout.ms: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_PROPERTIES_SESSION_TIMEOUT_MS:45000}
          max.partition.fetch.bytes: ${CUSTOM_KAFKA_CONSUMERS_PAYMENT_GROUP_PROPERTIES_MAX_PARTITION_FETCH_BYTES:1048576}

    # Message Producer Configurations
    producer:
      acks: ${CUSTOM_KAFKA_PRODUCER_ACKS:all}
      retries: ${CUSTOM_KAFKA_PRODUCER_RETRIES:3}
      topics: # Map<String, String> routing logical event names to physical topics
        order-events: ${CUSTOM_KAFKA_PRODUCER_TOPICS_ORDER_EVENTS:orders-v1-topic}
        billing-events: ${CUSTOM_KAFKA_PRODUCER_TOPICS_BILLING_EVENTS:billing-v2-topic}
      properties: # Map<String, String> low-level producer optimization properties
        compression.type: ${CUSTOM_KAFKA_PRODUCER_PROPERTIES_COMPRESSION_TYPE:snappy}
        linger.ms: ${CUSTOM_KAFKA_PRODUCER_PROPERTIES_LINGER_MS:20}
```

## Java Configuration

To register the infrastructure beans, define a configuration class utilizing the library's `KafkaConfigBuilder` and `KafkaProperties`:

### Option 1: Configuration for Text Messages (JSON) — 95% of use cases

```java
import config.kafka.io.github.dgavrikov.core.KafkaConfigBuilder;
import properties.kafka.io.github.dgavrikov.core.KafkaProperties;
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

    @Value("\${custom.system-code}")
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
                StringDeserializer.class,      // Key deserializer class
                StringDeserializer.class);     // Value deserializer class
    }

    @Bean
    public KafkaTemplate<String, String> customKafkaTemplate(
            KafkaProperties customKafkaProperties,
            MeterRegistry meterRegistry
    ) {
        return KafkaConfigBuilder.buildKafkaTemplate(
                customKafkaProperties,
                meterRegistry,
                StringSerializer.class,        // Key serializer class
                StringSerializer.class);       // Value serializer class
    }
}
```

### Option 2: Configuration for Raw Binary Data (byte[])

If your service needs to send or consume binary data (e.g., files, Protobuf, Avro), use `ByteArraySerializer` and `ByteArrayDeserializer`:

```java
import config.kafka.io.github.dgavrikov.core.KafkaConfigBuilder;
import properties.kafka.io.github.dgavrikov.core.KafkaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
                StringDeserializer.class,       // Key deserializer class
                ByteArrayDeserializer.class);   // Value deserializer class (byte[])
    }

    @Bean
    public KafkaTemplate<String, byte[]> bytesKafkaTemplate(
            KafkaProperties customKafkaProperties,
            MeterRegistry meterRegistry
    ) {
        return KafkaConfigBuilder.buildKafkaTemplate(
                customKafkaProperties,
                meterRegistry,
                StringSerializer.class,         // Key serializer class
                ByteArraySerializer.class);     // Value serializer class (byte[])
    }
}
```

## Producer Implementation Example

To send messages using the library, inherit from the abstract base class `AbstractKafkaProducerClient<K, V>`. It fully encapsulates asynchronous/synchronous sending logic, multi-topic routing via `;`, thread safety, custom data masking, and error handling.

### Option 1: Standard Producer for Text/JSON Messages (95% of use cases)

For typical JSON event streaming, declare your producer with `<String, String>` generic types. The business-level serialization (DTO to JSON) should be performed at the application layer before calling the client:

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kafka.io.github.dgavrikov.core.AbstractKafkaProducerClient;
import exception.kafka.io.github.dgavrikov.core.ProducerKafkaException;
import properties.kafka.io.github.dgavrikov.core.KafkaProperties;
import logging.service.io.github.dgavrikov.core.MaskingLog;
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
        this.maskingLog = maskingLog;
    }

    @Override
    protected String getSystemName() {
        return "Customer payment system";
    }

    @Override
    public void orderEventSend(OrderEventDto request, Map<String, String> headers, String messageKey) {
        try {
            // Logging source object
            maskingLog.debug(log, request, "Message body: ");

            // Application-level serialization preserves Clean Architecture & KISS
            String jsonPayload = objectMapper.writeValueAsString(request);

            // The compiler ensures that only a String can be passed as a message object
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

### Option 2: Binary Producer for Raw Data (byte[]) — The remaining 5% of cases

For services transferring files, imagery, Protobuf, or Avro segments, declare the component with `<String, byte[]>` types. This bypasses text serializers completely and provides absolute compile-time safety:

```java
import kafka.io.github.dgavrikov.core.AbstractKafkaProducerClient;
import properties.kafka.io.github.dgavrikov.core.KafkaProperties;
import logging.service.io.github.dgavrikov.core.MaskingLog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Slf4j
public class BinaryReportProducerClient extends AbstractKafkaProducerClient<String, byte[]> {

    private final KafkaProperties customKafkaProperties;

    public BinaryReportProducerClient(
            KafkaTemplate<String, byte[]> bytesKafkaTemplate, // Injected byte[] template
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

        // Compile-time verification: Only byte[] is allowed. DTOs or plain Strings will cause a compilation error.
        this.sendMessage(targetTopic, fileId, pdfBytes, headers);
    }
}
```

## Subscriber Implementation Examples

The library supports two consumption models: processing messages individually (single record mode) or in batches (batch mode).

### Single Record Subscriber

Processes exactly one Kafka record per poll invocation.

```java
import kafka.io.github.dgavrikov.core.KafkaConsumer;
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
        // Business logic execution goes here
        ack.acknowledge();
    }
}
```

### Batch Records Subscriber

Processes a collection of Kafka records fetched within a single poll invocation, maximizing high-throughput delivery.

```java
import kafka.io.github.dgavrikov.core.KafkaConsumerBatch;
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
        // Bulk business logic execution goes here
        ack.acknowledge();
    }
}
```

