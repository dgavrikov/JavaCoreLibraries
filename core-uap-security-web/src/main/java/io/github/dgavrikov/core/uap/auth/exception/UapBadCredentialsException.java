package io.github.dgavrikov.core.uap.auth.exception;

public class UapBadCredentialsException extends RuntimeException {
    public UapBadCredentialsException(String message) {
        super(message);
    }

    public UapBadCredentialsException(String message, Throwable cause) {
        super(message, cause);
    }
}
