package com.soat.gateway.domain.emission;

import com.soat.gateway.domain.sla.SlaDeadlines;

import java.time.Instant;

import static com.soat.gateway.domain.emission.EmissionStatus.EXPIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.ISSUED;
import static com.soat.gateway.domain.emission.EmissionStatus.REJECTED;
import static com.soat.gateway.domain.emission.EmissionStatus.REVIEW_REQUIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.SEND_FAILED;
import static com.soat.gateway.domain.emission.EmissionStatus.SENT;

public record Emission(String reference, EmissionStatus status, long quotedPremiumCents, Instant requestedAt) {

    public SlaDeadlines sla() {
        return new SlaDeadlines(requestedAt);
    }

    public Decision expire(Instant now, ProviderNotice finalCheck) {
        if (!sla().canExpire(now)) {
            return new Decision.Ignore(IgnoreReason.PREMATURE_EXPIRATION);
        }
        if (finalCheck != null && apply(finalCheck) instanceof Decision.Transition transition) {
            return transition;
        }
        return decide(EXPIRED);
    }

    public Decision confirmSent() {
        return decide(SENT);
    }

    public Decision registerSendFailure() {
        return decide(SEND_FAILED);
    }

    public Decision exhaustStatusChecks() {
        return decide(REVIEW_REQUIRED);
    }

    public Decision apply(ProviderNotice notice) {
        return switch (notice.status()) {
            case APPROVED -> applyOutcome(ISSUED, REJECTED, notice);
            case REJECTED -> applyOutcome(REJECTED, ISSUED, notice);
            case PENDING -> new Decision.Ignore(IgnoreReason.PENDING_HAS_NO_EFFECT);
            case UNKNOWN -> new Decision.Ignore(IgnoreReason.UNKNOWN_PROVIDER_STATUS);
        };
    }

    public Emission afterDecision(Decision decision) {
        return decision instanceof Decision.Transition transition
                ? new Emission(reference, transition.to(), quotedPremiumCents, requestedAt)
                : this;
    }

    private Decision applyOutcome(EmissionStatus target, EmissionStatus opposite, ProviderNotice notice) {
        if (status == opposite) {
            return new Decision.Ignore(IgnoreReason.PROVIDER_CONFLICT);
        }
        if (target == ISSUED && !status.isFinal()) {
            if (notice.amountCents() == null) {
                return new Decision.ManualReview(ReviewReason.AMOUNT_MISSING);
            }
            if (notice.amountCents() != quotedPremiumCents) {
                return new Decision.ManualReview(ReviewReason.AMOUNT_MISMATCH);
            }
        }
        return decide(target);
    }

    private Decision decide(EmissionStatus target) {
        return TransitionMatrix.allows(status, target)
                ? new Decision.Transition(status, target, TransitionMatrix.allowedOrigins(target))
                : new Decision.Ignore(IgnoreReason.STALE_NOTICE);
    }
}
