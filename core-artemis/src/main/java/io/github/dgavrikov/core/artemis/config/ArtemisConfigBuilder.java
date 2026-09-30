package io.github.dgavrikov.core.artemis.config;

import io.github.dgavrikov.core.artemis.properties.ArtemisProperties;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import lombok.experimental.UtilityClass;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.jms.support.destination.DynamicDestinationResolver;

@UtilityClass
public class ArtemisConfigBuilder {

    public static ConnectionFactory buildConnectionFactory(ArtemisProperties properties) {
        try {
            ActiveMQConnectionFactory amqFactory = new ActiveMQConnectionFactory(
                    properties.brokerUrl(),
                    properties.user(),
                    properties.password()
            );

            amqFactory.setProducerMaxRate(properties.producer().producerMaxRate());

            amqFactory.setConsumerWindowSize(properties.pool().globalConsumerWindowSize());

            CachingConnectionFactory cachingFactory = new CachingConnectionFactory(amqFactory);
            cachingFactory.setSessionCacheSize(properties.pool().cacheProducers());
            cachingFactory.setCacheConsumers(properties.pool().cacheConsumersEnabled());

            return cachingFactory;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Artemis ConnectionFactory", e);
        }
    }

    public static DefaultJmsListenerContainerFactory buildListenerContainerFactory(
            ConnectionFactory connectionFactory,
            ArtemisProperties.Consumer consumerProps
    ) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        // Гарантируем At-Least-Once семантику обработки
        factory.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);

        if (Boolean.TRUE.equals(consumerProps.enableVirtualThread())) {
            SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor(consumerProps.destination() + "-artemis-vt-");
            executor.setVirtualThreads(true);
            factory.setTaskExecutor(executor);
            factory.setConcurrency("1-" + consumerProps.concurrency());
        } else {
            factory.setConcurrency(String.valueOf(consumerProps.concurrency()));
        }

        factory.setReceiveTimeout(consumerProps.receiveTimeoutMs());
        factory.setDestinationResolver(new DynamicDestinationResolver());

        return factory;
    }
}
