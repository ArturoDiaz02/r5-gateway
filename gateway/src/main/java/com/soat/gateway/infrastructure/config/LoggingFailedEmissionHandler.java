package com.soat.gateway.infrastructure.config;

import com.soat.gateway.application.port.FailedEmissionHandler;
import com.soat.gateway.domain.emission.ReviewReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LoggingFailedEmissionHandler implements FailedEmissionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingFailedEmissionHandler.class);

    @Override
    public void reviewRequired(String reference) {
        LOG.warn("REVIEW_REQUIRED reference={}", reference);
    }

    @Override
    public void expired(String reference) {
        LOG.warn("EXPIRED reference={}", reference);
    }

    @Override
    public void manualReview(String reference, ReviewReason reason) {
        LOG.warn("MANUAL_REVIEW reference={} reason={}", reference, reason);
    }

    @Override
    public void providerConflict(String reference) {
        LOG.warn("PROVIDER_CONFLICT reference={}", reference);
    }
}
