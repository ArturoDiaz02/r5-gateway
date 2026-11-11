package com.soat.gateway.application.service;

import com.soat.gateway.application.port.EmissionProvider;
import com.soat.gateway.application.port.EmissionRecord;
import com.soat.gateway.application.port.EmissionRepository;
import com.soat.gateway.application.port.FailedEmissionHandler;
import com.soat.gateway.application.port.ProviderFailure;
import com.soat.gateway.domain.emission.Decision;
import com.soat.gateway.domain.emission.EmissionStatus;
import com.soat.gateway.domain.emission.ProviderNotice;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

public final class RunExpiration {

    private final EmissionRepository repository;
    private final EmissionProvider provider;
    private final DecisionApplier applier;
    private final FailedEmissionHandler handler;
    private final Clock clock;

    public RunExpiration(EmissionRepository repository, EmissionProvider provider, DecisionApplier applier,
                         FailedEmissionHandler handler, Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.applier = applier;
        this.handler = handler;
        this.clock = clock;
    }

    public void run(String reference) {
        applier.publishPending(reference);
        Instant now = clock.instant();
        Optional<EmissionRecord> record = repository.find(reference)
                .filter(r -> !r.emission().status().isFinal() && r.emission().sla().canExpire(now));
        if (record.isEmpty()) {
            return;
        }
        ProviderNotice finalCheck = check(reference);
        Decision decision = record.get().emission().expire(now, finalCheck);
        String policy = finalCheck != null ? finalCheck.policyNumber() : null;
        if (applier.apply(record.get(), decision, policy)
                && decision instanceof Decision.Transition transition
                && transition.to() == EmissionStatus.EXPIRED) {
            handler.expired(reference);
        }
        applier.publishPending(reference);
    }

    private ProviderNotice check(String reference) {
        try {
            return provider.checkStatus(reference).orElse(null);
        } catch (ProviderFailure e) {
            return null;
        }
    }
}
