package com.soat.gateway.domain.result;

import java.time.Instant;

public record EmissionResult(
        String reference,
        int sequence,
        ResultType type,
        ResultReason reason,
        boolean definitive,
        Integer correctsSequence,
        String policyNumber,
        Instant occurredAt,
        String correlationId) {
}
