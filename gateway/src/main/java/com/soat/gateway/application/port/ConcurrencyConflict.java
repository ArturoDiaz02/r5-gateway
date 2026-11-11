package com.soat.gateway.application.port;

public class ConcurrencyConflict extends RuntimeException {

    public ConcurrencyConflict(String message, Throwable cause) {
        super(message, cause);
    }
}
