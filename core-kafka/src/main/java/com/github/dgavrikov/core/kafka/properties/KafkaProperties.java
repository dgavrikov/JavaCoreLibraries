package com.github.dgavrikov.core.kafka.properties;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class KafkaProperties {
    private String bootstrapServers;
    private Ssl ssl = new Ssl();
    protected Map<String, Consumer> consumers = new HashMap<>();
    private Producer producer = new Producer();

    @Data
    public static class Consumer {
        private Boolean enabled;
        private Boolean enableAutoCommit;
        private String topics;
        private String groupId;
        private String autoOffsetReset;
        private Integer listenerConcurrency;
        private Integer maxPollIntervalMs;
        private Integer maxPollRecords;
        private Integer sessionTimeoutMs = 45000;
        private Integer heartbeatIntervalMs = 10000;
        private long retryAttempts = 10;
        private long retryBackoffMs = 2000;
        private Boolean enableVirtualThread = false;
        private Map<String, String> properties = new HashMap<>();
    }

    @Data
    public static class Ssl {
        private Boolean enabled;
        private Store keyStore = new Store();
        private Store trustStore = new Store();
        private Key key = new Key();

        @Data
        public static class Key {
            private String password;
        }

        @Data
        public static class Store {
            private String type;
            private String password;
            private String location;
        }
    }

    @Data
    public static class Producer {
        private Map<String, String> topics = new HashMap<>();
        private String acks;
        private Integer retries;
        private Map<String, String> properties = new HashMap<>();
    }
}
