package com.soat.gateway.domain.sla;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SlaDeadlinesTest {

    private static final SlaDeadlines SLA = new SlaDeadlines(Instant.parse("2026-11-09T14:00:00Z"));

    @Test
    void deadlineIsTwentyFourHoursLater() {
        assertThat(SLA.deadline()).isEqualTo(Instant.parse("2026-11-10T14:00:00Z"));
    }

    @Test
    void expirationIsOneHourBeforeTheDeadline() {
        assertThat(SLA.expirationInstant()).isEqualTo(Instant.parse("2026-11-10T13:00:00Z"));
    }

    @Test
    void cannotExpireEarly() {
        assertThat(SLA.canExpire(SLA.expirationInstant().minus(Duration.ofSeconds(1)))).isFalse();
    }

    @Test
    void canExpireFromTheExpirationInstant() {
        assertThat(SLA.canExpire(SLA.expirationInstant())).isTrue();
        assertThat(SLA.canExpire(SLA.deadline().plus(Duration.ofDays(1)))).isTrue();
    }
}
