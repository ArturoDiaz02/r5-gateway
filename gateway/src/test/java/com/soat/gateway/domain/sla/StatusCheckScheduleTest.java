package com.soat.gateway.domain.sla;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusCheckScheduleTest {

    private final StatusCheckSchedule schedule = StatusCheckSchedule.defaults();

    @ParameterizedTest
    @CsvSource({"1, PT5S", "2, PT1M", "3, PT10M", "4, PT20M", "5, PT30M"})
    void defaultDelays(int attempt, Duration expected) {
        assertThat(schedule.delay(attempt)).isEqualTo(expected);
    }

    @Test
    void thereAreFiveAttempts() {
        assertThat(schedule.attempts()).isEqualTo(5);
    }

    @Test
    void nextAttempt() {
        assertThat(schedule.next(1)).contains(2);
        assertThat(schedule.next(4)).contains(5);
    }

    @Test
    void isExhaustedAfterTheFifth() {
        assertThat(schedule.next(5)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    void attemptOutOfRange(int attempt) {
        assertThatThrownBy(() -> schedule.delay(attempt)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delaysAreConfigurable() {
        StatusCheckSchedule fast = new StatusCheckSchedule(List.of(Duration.ofMillis(100), Duration.ofMillis(200)));

        assertThat(fast.delay(2)).isEqualTo(Duration.ofMillis(200));
        assertThat(fast.next(2)).isEmpty();
    }
}
