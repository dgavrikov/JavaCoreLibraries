package io.github.dgavrikov.core.artemis.properties;

import java.util.Map;
import java.util.Collections;

public record ArtemisProperties(
        String brokerUrl,
        String user,
        String password,
        ConnectionPool pool,
        Map<String, Consumer> consumers,
        Producer producer
) {
    public ArtemisProperties {
        if (pool == null) pool = new ConnectionPool(10, 100, 100, false, 0);
        if (consumers == null) consumers = Collections.emptyMap();
        if (producer == null) producer = new Producer(Collections.emptyMap(), true, 0L, -1);
    }

    public record ConnectionPool(
            Integer maxConnections,
            Integer cacheProducers,
            Integer cacheConsumers,
            Boolean cacheConsumersEnabled,
            Integer globalConsumerWindowSize // Добавили сюда
    ) {
        public ConnectionPool {
            if (maxConnections == null) maxConnections = 10;
            if (cacheProducers == null) cacheProducers = 100;
            if (cacheConsumers == null) cacheConsumers = 100;
            if (cacheConsumersEnabled == null) cacheConsumersEnabled = false;
            if (globalConsumerWindowSize == null) globalConsumerWindowSize = 0;
        }
    }


    public record Consumer(
            Boolean enabled,
            String destination,
            Integer concurrency,
            Boolean enableVirtualThread,
            Long receiveTimeoutMs,
            String selector,
            Integer consumerWindowSize // Вынесли тюнинг Netty-буфера Artemis
    ) {
        public Consumer {
            if (enabled == null) enabled = true;
            if (concurrency == null) concurrency = 1;
            if (enableVirtualThread == null) enableVirtualThread = true;
            if (receiveTimeoutMs == null) receiveTimeoutMs = 1000L;
            if (consumerWindowSize == null) consumerWindowSize = 0;
        }
    }

    public record Producer(
            Map<String, String> destinations,
            Boolean deliveryPersistent,
            Long timeToLiveMs,
            Integer producerMaxRate
    ) {
        public Producer {
            if (destinations == null) destinations = Collections.emptyMap();
            if (deliveryPersistent == null) deliveryPersistent = true;
            if (timeToLiveMs == null) timeToLiveMs = 0L;
            if (producerMaxRate == null) producerMaxRate = -1;
        }
    }
}
