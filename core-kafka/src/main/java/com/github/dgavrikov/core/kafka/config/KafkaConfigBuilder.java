package com.github.dgavrikov.core.kafka.config;

import com.github.dgavrikov.core.kafka.properties.KafkaProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.experimental.UtilityClass;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.backoff.FixedBackOff;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@UtilityClass
public class KafkaConfigBuilder {
    public static <K, V> KafkaTemplate<K, V> buildKafkaTemplate(
            KafkaProperties kafkaProperties,
            MeterRegistry meterRegistry,
            Class<?> keySerializer,
            Class<?> valueSerializer
    ) {
        Map<String, Object> props = new HashMap<>();
        configureProducer(props, kafkaProperties, keySerializer, valueSerializer);
        configureSecurityProps(props, kafkaProperties.getSsl());

        var factory = new DefaultKafkaProducerFactory<K, V>(props);
        factory.addListener(new MicrometerProducerListener<>(meterRegistry));
        return new KafkaTemplate<>(factory);
    }

    public static <K, V> ConcurrentKafkaListenerContainerFactory<K, V> buildKafkaListenerContainerFactory(
            KafkaProperties kafkaProperties,
            MeterRegistry meterRegistry,
            String section,
            String codeSystem,
            Class<?> keyDeserializer,
            Class<?> valueDeserializer
    ) {
        var consumerProps = kafkaProperties.getConsumers().get(section);
        Map<String, Object> props = new HashMap<>();

        configureConsumer(props, consumerProps, kafkaProperties.getBootstrapServers(), keyDeserializer, valueDeserializer);
        configureSecurityProps(props, kafkaProperties.getSsl());

        var factory = new DefaultKafkaConsumerFactory<K, V>(props);
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        return createContainerFactory(factory, consumerProps, codeSystem);
    }

    private static <K, V> ConcurrentKafkaListenerContainerFactory<K, V> createContainerFactory(
            ConsumerFactory<K, V> consumerFactory,
            KafkaProperties.Consumer consumerProps,
            String codeSystem
    ) {
        var factory = new ConcurrentKafkaListenerContainerFactory<K, V>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(consumerProps.getListenerConcurrency());

        if (consumerProps.getEnableVirtualThread()) {
            var executor = new SimpleAsyncTaskExecutor(consumerProps.getTopics() + "-vt-");
            executor.setVirtualThreads(true);
            factory.getContainerProperties().setListenerTaskExecutor(executor);
        } else {
            var executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(consumerProps.getListenerConcurrency());
            executor.setMaxPoolSize(consumerProps.getListenerConcurrency() + 2);
            executor.setThreadNamePrefix(consumerProps.getTopics() + "-");
            executor.initialize();
            factory.getContainerProperties().setListenerTaskExecutor(executor);
        }

        factory.setConcurrency(consumerProps.getListenerConcurrency());

        factory.getContainerProperties().setAuthExceptionRetryInterval(Duration.ofSeconds(10L));
        factory.setCommonErrorHandler(errorHandler(consumerProps));

        if (!Boolean.TRUE.equals(consumerProps.getEnableAutoCommit()))
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        factory.getContainerProperties().setMicrometerTags(Map.of("code_system", codeSystem));
        return factory;
    }

    private static void configureProducer(Map<String, Object> target,
                                          KafkaProperties kafkaProperties,
                                          Class<?> keySer,
                                          Class<?> valSer) {
        target.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());
        target.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySer);
        target.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valSer);
        target.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false);
        if (kafkaProperties.getProducer() != null) {
            target.putAll(kafkaProperties.getProducer().getProperties());
        }
    }

    private static void configureConsumer(Map<String, Object> target,
                                          KafkaProperties.Consumer consumer,
                                          String bootstrap,
                                          Class<?> keyDes,
                                          Class<?> valDes) {
        target.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        target.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDes);
        target.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valDes);
        target.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        target.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, consumer.getEnableAutoCommit());
        target.put(ConsumerConfig.GROUP_ID_CONFIG, consumer.getGroupId());
        target.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, consumer.getAutoOffsetReset());
        target.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, consumer.getMaxPollIntervalMs());
        target.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, consumer.getMaxPollRecords());
        target.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, consumer.getSessionTimeoutMs());
        target.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, consumer.getHeartbeatIntervalMs());
        if (consumer.getProperties() != null)
            target.putAll(consumer.getProperties());
    }

    private static void configureSecurityProps(Map<String, Object> target, KafkaProperties.Ssl ssl) {
        if (ssl == null || !Boolean.TRUE.equals(ssl.getEnabled())) return;

        target.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL");
        configureStore(target,
                SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG,
                SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG,
                SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, ssl.getTrustStore());
        configureStore(target,
                SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG,
                SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG,
                SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, ssl.getKeyStore());

        if (ssl.getKey() != null && ssl.getKey().getPassword() != null)
            target.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, ssl.getKey().getPassword());

        target.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "");
    }

    private static void configureStore(
            Map<String, Object> target,
            String loc,
            String pass,
            String type,
            KafkaProperties.Ssl.Store store) {
        if (store == null) return;
        Optional.ofNullable(store.getLocation()).ifPresent(l -> target.put(loc, new File(l).getAbsolutePath()));
        Optional.ofNullable(store.getPassword()).ifPresent(p -> target.put(pass, p));
        Optional.ofNullable(store.getType()).ifPresent(t -> target.put(type, t));
    }

    private static DefaultErrorHandler errorHandler(KafkaProperties.Consumer consumer) {
        var fixedBackOff = new FixedBackOff(consumer.getRetryBackoffMs(), consumer.getRetryAttempts());
        var errorHandler = new DefaultErrorHandler((consumerRecord, e) -> {
        }, fixedBackOff);
        errorHandler.setLogLevel(KafkaException.Level.DEBUG);
        errorHandler.addNotRetryableExceptions(NullPointerException.class);
        return errorHandler;
    }
}
