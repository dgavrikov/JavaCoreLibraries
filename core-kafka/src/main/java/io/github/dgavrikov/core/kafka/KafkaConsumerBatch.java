package io.github.dgavrikov.core.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.springframework.kafka.support.Acknowledgment;

public interface KafkaConsumerBatch <V>{
    void consumerRecords(ConsumerRecords<?, V> records, Acknowledgment ack);
}
