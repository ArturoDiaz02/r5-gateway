package com.soat.gateway.infrastructure.messaging;

public class InvalidMessage extends IllegalArgumentException {

    public InvalidMessage(String message, Throwable cause) {
        super(message, cause);
    }
}
