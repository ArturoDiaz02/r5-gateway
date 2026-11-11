package com.soat.gateway.domain.sla;

import java.time.Duration;
import java.time.Instant;

public record SlaDeadlines(Instant requestedAt) {

    private static final Duration SLA = Duration.ofHours(24);
    private static final Duration EXPIRATION_LEAD = Duration.ofHours(1);

    public Instant deadline() {
        return requestedAt.plus(SLA);
    }

    public Instant expirationInstant() {
        return deadline().minus(EXPIRATION_LEAD);
    }

    public boolean canExpire(Instant now) {
        return !now.isBefore(expirationInstant());
    }
}
