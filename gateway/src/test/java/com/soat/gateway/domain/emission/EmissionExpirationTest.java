package com.soat.gateway.domain.emission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;

import static com.soat.gateway.domain.emission.EmissionStatus.EXPIRED;
import static com.soat.gateway.domain.emission.EmissionStatus.ISSUED;
import static com.soat.gateway.domain.emission.EmissionStatus.REJECTED;
import static com.soat.gateway.domain.emission.EmissionStatus.SENT;
import static org.assertj.core.api.Assertions.assertThat;

class EmissionExpirationTest {

    private static final long PREMIUM = 89_130_000L;
    private static final Instant REQUESTED_AT = Instant.parse("2026-11-09T14:00:00Z");
    private static final Instant EXPIRATION = Instant.parse("2026-11-10T13:00:00Z");

    private static Emission in(EmissionStatus status) {
        return new Emission("SOAT-883214", status, PREMIUM, REQUESTED_AT);
    }

    private static Decision transition(EmissionStatus from, EmissionStatus to) {
        return new Decision.Transition(from, to, TransitionMatrix.allowedOrigins(to));
    }

    @Test
    void prematureExpirationIsIgnored() {
        assertThat(in(SENT).expire(EXPIRATION.minus(Duration.ofSeconds(1)), null))
                .isEqualTo(new Decision.Ignore(IgnoreReason.PREMATURE_EXPIRATION));
    }

    @Test
    void expiresExactlyAtTheBoundary() {
        assertThat(in(SENT).expire(EXPIRATION, null)).isEqualTo(transition(SENT, EXPIRED));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"REQUESTED", "SENT", "REVIEW_REQUIRED", "SEND_FAILED"})
    void expiresWithoutProviderAnswer(EmissionStatus status) {
        assertThat(in(status).expire(EXPIRATION, null)).isEqualTo(transition(status, EXPIRED));
    }

    @Test
    void approvedFinalCheckIssues() {
        assertThat(in(SENT).expire(EXPIRATION, new ProviderNotice(ProviderStatus.APPROVED, PREMIUM)))
                .isEqualTo(transition(SENT, ISSUED));
    }

    @Test
    void rejectedFinalCheckRejects() {
        assertThat(in(SENT).expire(EXPIRATION, new ProviderNotice(ProviderStatus.REJECTED, PREMIUM)))
                .isEqualTo(transition(SENT, REJECTED));
    }

    @Test
    void pendingFinalCheckExpires() {
        assertThat(in(SENT).expire(EXPIRATION, new ProviderNotice(ProviderStatus.PENDING, null)))
                .isEqualTo(transition(SENT, EXPIRED));
    }

    @Test
    void finalCheckWithAmountMismatchExpires() {
        assertThat(in(SENT).expire(EXPIRATION, new ProviderNotice(ProviderStatus.APPROVED, PREMIUM + 1)))
                .isEqualTo(transition(SENT, EXPIRED));
    }

    @ParameterizedTest
    @EnumSource(value = EmissionStatus.class, names = {"ISSUED", "REJECTED", "EXPIRED"})
    void expiringFinalOrAlreadyExpiredHasNoEffect(EmissionStatus status) {
        assertThat(in(status).expire(EXPIRATION, null)).isEqualTo(new Decision.Ignore(IgnoreReason.STALE_NOTICE));
    }
}
