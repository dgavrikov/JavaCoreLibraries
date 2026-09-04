package io.github.dgavrikov.core.kafka.exception;

public class ConsumerNotFoundException extends RuntimeException {
    public ConsumerNotFoundException(String message) {
        super(message);
    }
    public ConsumerNotFoundException(String message, Throwable throwable) {super(message, throwable);}
}
