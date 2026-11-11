package com.soat.gateway.application.port;

public class ProviderFailure extends RuntimeException {

    public ProviderFailure(String message) {
        super(message);
    }

    public ProviderFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
