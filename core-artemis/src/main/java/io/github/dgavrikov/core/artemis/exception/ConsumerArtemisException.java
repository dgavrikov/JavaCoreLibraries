package io.github.dgavrikov.core.artemis.exception;

public class ConsumerArtemisException extends RuntimeException {
    public ConsumerArtemisException(String message) {
        super(message);
    }

    public ConsumerArtemisException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
