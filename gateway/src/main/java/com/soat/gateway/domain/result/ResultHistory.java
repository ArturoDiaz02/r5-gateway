package com.soat.gateway.domain.result;

import com.soat.gateway.domain.emission.EmissionStatus;

import java.time.Instant;
import java.util.Optional;

public record ResultHistory(int lastSequence, boolean lastProvisional) {

    public static ResultHistory empty() {
        return new ResultHistory(0, false);
    }

    public Optional<EmissionResult> resultFor(EmissionStatus target, String reference, String correlationId,
                                              String policyNumber, Instant occurredAt) {
        return switch (target) {
            case ISSUED -> Optional.of(next(ResultType.SUCCESS, null, true,
                    reference, correlationId, policyNumber, occurredAt));
            case REJECTED -> Optional.of(next(ResultType.ERROR, ResultReason.REJECTED, true,
                    reference, correlationId, null, occurredAt));
            case EXPIRED -> Optional.of(next(ResultType.ERROR, ResultReason.EXPIRED, false,
                    reference, correlationId, null, occurredAt));
            default -> Optional.empty();
        };
    }

    public ResultHistory after(EmissionResult result) {
        return new ResultHistory(result.sequence(), !result.definitive());
    }

    private EmissionResult next(ResultType type, ResultReason reason, boolean definitive, String reference,
                                String correlationId, String policyNumber, Instant occurredAt) {
        if (lastSequence > 0 && !(lastProvisional && definitive)) {
            throw new IllegalStateException("only a provisional result can be corrected, and only by a definitive one: "
                    + reference);
        }
        Integer corrects = lastProvisional ? lastSequence : null;
        return new EmissionResult(reference, lastSequence + 1, type, reason, definitive, corrects,
                policyNumber, occurredAt, correlationId);
    }
}
