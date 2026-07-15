package com.github.dgavrikov.core.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.support.Acknowledgment;

public interface KafkaConsumer<V> {
    void consumerRecord(ConsumerRecords<?, V> record, Acknowledgment ack);
}
