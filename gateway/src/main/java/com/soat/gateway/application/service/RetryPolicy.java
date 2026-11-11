package com.soat.gateway.application.service;

import java.time.Duration;

public record RetryPolicy(int maxAttempts, Duration delay) {
}
