package com.soat.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("gateway")
public record GatewayProperties(
        Provider provider,
        Hmac hmac,
        List<Duration> statusChecks,
        Retry retry,
        Consumers consumers) {

    public record Provider(String url, Duration timeout) {
    }

    public record Hmac(String current, String previous, Duration window) {
    }

    public record Retry(int maxAttempts, Duration delay, Duration internalErrorDelay) {
    }

    public record Consumers(int requests, int statusChecks, int expirations, int deadLetter) {
    }
}
