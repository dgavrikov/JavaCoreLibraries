# Apache ActiveMQ Artemis Integration Library

Provides enterprise-ready, lightweight, and robust integration with Apache ActiveMQ Artemis based on the Jakarta Messaging (JMS 3.0) specification. Designed specifically for high-throughput, low-latency transactional processing systems. It features a complete elimination of JNDI overhead, customizable queue routing, and native support for Java 21 Virtual Threads (Project Loom).

## Installation

### Add Dependency to pom.xml

```xml
<dependency>
    <groupId>io.github.dgavrikov</groupId>
    <artifactId>core-artemis</artifactId>
</dependency>
```

## YAML Configuration Reference

The library uses a customizable root prefix (custom) supporting environment variable overrides and fallback defaults optimized for virtual threads. The full production-ready configuration structure (including broker connection settings, connection pools, consumer destinations, and producer mappings) can be found in the referenced documentation.

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

## Java Configuration

To register infrastructure beans and build connection/listener factories using ArtemisConfigBuilder and ArtemisProperties, define a configuration class annotated with @Configuration, @EnableJms, and conditional property checks. The complete configuration class implementation is available in the source reference.

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
}

```

## Producer Implementation Example

To send messages asynchronously or synchronously via virtual threads, inherit from AbstractArtemisProducerClient<V>. Serialization should be handled at the application layer before invoking the client. The full implementation example of CustomArtemisProduceClientImpl is provided in the reference.

## Subscriber Implementation Example

Message consumption is handled by inheriting from AbstractArtemisConsumer<V> which enforces strict Session.CLIENT_ACKNOWLEDGE semantics—acknowledging messages only upon successful business logic execution. The complete consumer class implementation can be reviewed in the referenced documentation.

