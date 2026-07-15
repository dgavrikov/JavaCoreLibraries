package com.github.dgavrikov.core.kafka.exception;

public class ProducerKafkaException extends RuntimeException {
    public ProducerKafkaException(String message) {
        super(message);
    }

    public ProducerKafkaException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
