package com.soat.gateway.domain.emission;

public record ProviderNotice(ProviderStatus status, Long amountCents, String policyNumber) {

    public ProviderNotice {
        if (amountCents != null && amountCents <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amountCents);
        }
    }

    public ProviderNotice(ProviderStatus status, Long amountCents) {
        this(status, amountCents, null);
    }
}
