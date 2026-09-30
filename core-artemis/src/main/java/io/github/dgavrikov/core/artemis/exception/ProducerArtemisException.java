package io.github.dgavrikov.core.artemis.exception;

public class ProducerArtemisException extends RuntimeException {

    public ProducerArtemisException(String message, Throwable throwable) {
        super(message, throwable);
    }

    public ProducerArtemisException(String message) {
        super(message);
    }
}
