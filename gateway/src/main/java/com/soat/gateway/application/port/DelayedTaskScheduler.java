package com.soat.gateway.application.port;

import java.time.Duration;
import java.time.Instant;

public interface DelayedTaskScheduler {

    boolean scheduleStatusCheck(String reference, int attempt, Duration delay);

    boolean scheduleExpiration(String reference, Instant at);
}
