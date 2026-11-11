package com.soat.gateway.domain.emission;

public enum EmissionStatus {
    REQUESTED,
    SENT,
    REVIEW_REQUIRED,
    SEND_FAILED,
    EXPIRED,
    ISSUED,
    REJECTED;

    public boolean isFinal() {
        return this == ISSUED || this == REJECTED;
    }
}
