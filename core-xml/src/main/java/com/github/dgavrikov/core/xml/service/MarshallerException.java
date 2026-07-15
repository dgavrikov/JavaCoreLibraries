package com.github.dgavrikov.core.xml.service;

public class MarshallerException extends RuntimeException {
    public MarshallerException(String message) {
        super(message);
    }
    public MarshallerException(String message, Throwable throwable){
        super(message, throwable);
    }
}
