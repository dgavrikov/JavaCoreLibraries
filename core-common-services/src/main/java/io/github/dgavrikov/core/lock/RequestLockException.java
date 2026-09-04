package io.github.dgavrikov.core.lock;

public class RequestLockException extends RuntimeException {
    public RequestLockException(String message) {
        this(message, null);
    }

    public RequestLockException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
