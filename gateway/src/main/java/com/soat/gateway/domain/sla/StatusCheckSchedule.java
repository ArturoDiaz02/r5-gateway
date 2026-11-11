package com.soat.gateway.domain.sla;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public record StatusCheckSchedule(List<Duration> delays) {

    public StatusCheckSchedule {
        delays = List.copyOf(delays);
    }

    public static StatusCheckSchedule defaults() {
        return new StatusCheckSchedule(List.of(
                Duration.ofSeconds(5),
                Duration.ofMinutes(1),
                Duration.ofMinutes(10),
                Duration.ofMinutes(20),
                Duration.ofMinutes(30)));
    }

    public int attempts() {
        return delays.size();
    }

    public Duration delay(int attempt) {
        if (attempt < 1 || attempt > attempts()) {
            throw new IllegalArgumentException("attempt out of range: " + attempt);
        }
        return delays.get(attempt - 1);
    }

    public Optional<Integer> next(int attempt) {
        return attempt < attempts() ? Optional.of(attempt + 1) : Optional.empty();
    }
}
