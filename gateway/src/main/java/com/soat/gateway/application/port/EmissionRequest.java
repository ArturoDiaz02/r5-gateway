package com.soat.gateway.application.port;

import java.time.Instant;

public record EmissionRequest(
        String reference,
        String correlationId,
        Instant requestedAt,
        long quotedPremiumCents,
        String plate) {
}
