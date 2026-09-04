package io.github.dgavrikov.core.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.dgavrikov.core.kafka.exception.ProducerKafkaException;

import java.util.Map;

public interface KafkaProducer<K, V> {
    void sendMessage(String topics,
                     K recordKey,
                     V messageObject,
                     Map<String, String> header) throws ProducerKafkaException, JsonProcessingException;

    void sendFastMessage(String topics,
                         K recordKey,
                         V messageObject,
                         Map<String, String> header) throws ProducerKafkaException, JsonProcessingException;

}